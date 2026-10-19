// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CleanupPlannerTest {
    @Test fun deletesAtTwentyFourHoursButNotOneSecondEarlier() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val id = f.completed().single()
                val played = f.clock.now() - DAY_MS + 1000
                f.state(id, playedAt = played)
                f.planner.run(CleanupReason.PLAYED)
                assertNotNull(engine.dao.byEpisode(id))
                assertNotNull(f.index.localUriOrNull(id))
                assertNotNull(engine.files.stat("old", f.path(id)))

                f.clock.nowMs += 1000
                f.planner.run(CleanupReason.PLAYED)
                f.assertDeleted(id)
                assertEquals(
                    played,
                    engine.db
                        .episodeStateDao()
                        .byEpisode(id)
                        ?.playedAt,
                )
                assertNull(
                    engine.db
                        .episodeStateDao()
                        .byEpisode(id)
                        ?.downloadDismissedAt,
                )
            }
        }

    @Test fun immediateNeverAndFuturePlayedTimesUseEffectivePodcastPolicy() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val immediate = f.completed().single()
                val never = f.completed().single()
                val future = f.completed().single()
                f.policy(listOf(immediate, future), cleanupPolicy(deleteAfter = DeleteAfter.IMMEDIATELY))
                f.policy(listOf(never), cleanupPolicy(deleteAfter = DeleteAfter.NEVER))
                f.state(immediate, playedAt = f.clock.now())
                f.state(never, playedAt = f.clock.now() - 20 * DAY_MS)
                f.state(future, playedAt = f.clock.now() + 1)
                f.planner.run(CleanupReason.PLAYED)
                f.assertDeleted(immediate)
                assertNotNull(engine.dao.byEpisode(never))
                assertNotNull(engine.dao.byEpisode(future))
            }
        }

    @Test fun deletesPlayedManualAndAutoWithoutWritingOrClearingTombstones() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val manual = f.completed(lane = DownloadLane.MANUAL).single()
                val auto = f.completed().single()
                f.state(manual, playedAt = f.clock.now() - DAY_MS)
                f.state(auto, playedAt = f.clock.now() - DAY_MS, dismissedAt = 123)
                f.planner.run(CleanupReason.PLAYED)
                f.assertDeleted(manual)
                f.assertDeleted(auto)
                assertNull(
                    engine.db
                        .episodeStateDao()
                        .byEpisode(manual)
                        ?.downloadDismissedAt,
                )
                assertEquals(
                    123L,
                    engine.db
                        .episodeStateDao()
                        .byEpisode(auto)
                        ?.downloadDismissedAt,
                )
            }
        }

    @Test fun favouritesCurrentAndExactlyNextThreeProtectPlayedFiles() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val ids = f.completed(7, DownloadLane.MANUAL)
                for (id in ids) f.state(id, playedAt = f.clock.now() - DAY_MS)
                f.state(ids[0], playedAt = f.clock.now() - DAY_MS, favorite = true)
                f.current(ids[1])
                ids.drop(2).take(4).forEachIndexed { n, id ->
                    engine.db.queueDao().insert(queueEntryEntity(id, "a$n"))
                }
                f.planner.run(CleanupReason.PLAYED)
                for (id in ids.take(5)) {
                    assertNotNull(engine.dao.byEpisode(id))
                    assertNotNull(engine.files.stat("old", f.path(id)))
                }
                for (id in ids.drop(5)) f.assertDeleted(id)
            }
        }

    @Test fun unplayedManualAndStartedAutoAreProtectedEvenOutsideRollingWindow() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val manual = f.completed(4, DownloadLane.MANUAL)
                val auto = f.completed(4)
                f.policy(manual + auto, cleanupPolicy(enabled = true, keep = 1))
                for (id in auto.dropLast(1)) f.state(id, startedAt = 1)
                f.planner.run(CleanupReason.POLICY_CHANGED)
                for (id in manual + auto) assertNotNull(engine.dao.byEpisode(id))
                assertTrue(f.removed.isEmpty())
            }
        }

    @Test fun rollingWindowKeepsNewestByDateAndIdAndCountsProtectedSlots() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val ids = f.completed(7)
                f.policy(ids, cleanupPolicy(enabled = true, keep = 3))
                // Equal sort dates exercise the id tie-break. Newest favourite occupies a keep slot;
                // an old favourite extends the number retained, without evicting an in-window item.
                f.state(ids.last(), favorite = true)
                f.state(ids.first(), favorite = true)
                f.planner.run(CleanupReason.POLICY_CHANGED)
                val retained = listOf(ids.first()) + ids.takeLast(3)
                assertEquals(
                    retained.toSet(),
                    engine.dao
                        .allRows()
                        .map { it.episodeId }
                        .toSet(),
                )
                for (id in ids.subList(1, 4)) f.assertDeleted(id)
                assertTrue(
                    ids.all {
                        engine.db
                            .episodeStateDao()
                            .byEpisode(it)
                            ?.downloadDismissedAt == null
                    },
                )
            }
        }

    @Test fun rollingWindowProtectsCurrentAndNextThreeAndDoesNotReplaceTheirSlots() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val ids = f.completed(8)
                f.policy(ids, cleanupPolicy(enabled = true, keep = 2))
                f.current(ids[0])
                ids.subList(1, 5).forEachIndexed { n, id ->
                    engine.db.queueDao().insert(queueEntryEntity(id, "a$n"))
                }
                f.planner.run(CleanupReason.POLICY_CHANGED)
                val retained = ids.take(4) + ids.takeLast(2)
                assertEquals(
                    retained.toSet(),
                    engine.dao
                        .allRows()
                        .map { it.episodeId }
                        .toSet(),
                )
                for (id in ids.subList(4, 6)) f.assertDeleted(id)
            }
        }

    @Test fun disabledAutoStopsRollingButStillDeletesPlayedFiles() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val ids = f.completed(5)
                f.policy(ids, cleanupPolicy(enabled = false, keep = 1))
                f.state(ids.first(), playedAt = f.clock.now() - DAY_MS)
                f.planner.run(CleanupReason.POLICY_CHANGED)
                f.assertDeleted(ids.first())
                assertEquals(ids.drop(1), engine.dao.allRows().map { it.episodeId })
            }
        }

    @Test fun missingCapabilityUsesResolvedDeletePolicyWithoutRollingCleanup() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val ids = f.completed(3)
                f.policy(ids, cleanupPolicy(enabled = false, keep = 1).copy(source = SettingSource.NotSupported))
                f.state(ids[0], playedAt = f.clock.now() - DAY_MS)
                f.planner.run(CleanupReason.PLAYED)
                f.assertDeleted(ids[0])
                assertEquals(ids.drop(1), engine.dao.allRows().map { it.episodeId })
            }
        }

    @Test fun playedAutoPrecedesPlayedManualThenRollingFiles() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val auto = f.completed(3)
                val manual = f.completed(2, DownloadLane.MANUAL)
                val rolling = f.completed(3)
                f.policy(rolling, cleanupPolicy(enabled = true, keep = 1))
                f.state(auto[0], playedAt = f.clock.now() - 2 * DAY_MS)
                f.state(auto[1], playedAt = f.clock.now() - 3 * DAY_MS)
                f.state(auto[2], playedAt = f.clock.now() - 2 * DAY_MS)
                f.state(manual[0], playedAt = f.clock.now() - 5 * DAY_MS)
                f.state(manual[1], playedAt = f.clock.now() - 4 * DAY_MS)
                f.planner.run(CleanupReason.POLICY_CHANGED)
                assertEquals(listOf(auto[1], auto[0], auto[2]) + manual + rolling.dropLast(1).reversed(), f.removed)
            }
        }

    @Test fun protectionsChangedDuringJournalingAreRecheckedBeforeDeletion() =
        runTest {
            for (protection in Protection.entries) {
                EngineRig(this).use { engine ->
                    val f = CleanupRig(engine)
                    val id = f.completed().single()
                    val playedAt = f.clock.now() - DAY_MS
                    f.state(id, playedAt = playedAt)
                    f.store.beforeUpdate = {
                        f.store.beforeUpdate = {}
                        when (protection) {
                            Protection.FAVORITE -> f.state(id, playedAt = playedAt, favorite = true)
                            Protection.CURRENT -> f.current(id)
                            Protection.UP_NEXT -> engine.db.queueDao().insert(queueEntryEntity(id, "a0"))
                            Protection.UNPLAYED -> f.state(id)
                        }
                    }
                    f.planner.run(CleanupReason.PLAYED)
                    assertNotNull(engine.dao.byEpisode(id), protection.name)
                    assertNotNull(f.index.localUriOrNull(id), protection.name)
                    assertNotNull(engine.files.stat("old", f.path(id)), protection.name)
                    assertTrue(f.deletes.entries().isEmpty(), protection.name)
                }
            }
        }

    @Test fun currentFileAfterRemovalIsDeferredUntilAnOutgoingSessionPass() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val id = f.completed().single()
                f.state(id, playedAt = f.clock.now() - DAY_MS)
                f.beforeFinish = { rows ->
                    if (rows.isNotEmpty()) {
                        assertNull(engine.dao.byEpisode(id))
                        assertNull(f.index.localUriOrNull(id))
                        f.current(id)
                    }
                }
                f.planner.run(CleanupReason.PLAYED)
                assertNull(engine.dao.byEpisode(id))
                assertNotNull(engine.files.stat("old", f.path(id)))
                assertTrue(f.deletes.entries().any { it.episodeId == id && it.path == f.path(id) })
                assertNull(
                    engine.db
                        .episodeStateDao()
                        .byEpisode(id)
                        ?.downloadDismissedAt,
                )

                f.current(null)
                f.planner.run(CleanupReason.PLAYED)
                f.assertDeleted(id)
                assertTrue(f.deletes.entries().isEmpty())
            }
        }

    @Test fun unavailableRootsKeepDeletionJournalUntilRemount() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val id = f.completed().single()
                f.state(id, playedAt = f.clock.now() - DAY_MS)
                engine.roots.available.remove("old")
                f.planner.run(CleanupReason.PLAYED)
                assertNull(engine.dao.byEpisode(id))
                assertTrue(f.deletes.entries().isNotEmpty())
                engine.roots.available.add("old")
                assertNotNull(engine.files.stat("old", f.path(id)))
                f.planner.run(CleanupReason.PLAYED)
                f.assertDeleted(id)
                assertTrue(f.deletes.entries().isEmpty())
            }
        }

    @Test fun failedJournalDoesNotRemoveRowsOrIndexAndNextRunRetries() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val id = f.completed().single()
                f.state(id, playedAt = f.clock.now() - DAY_MS)
                f.store.fail = true
                assertFailsWith<IOException> { f.planner.run(CleanupReason.PLAYED) }
                assertNotNull(engine.dao.byEpisode(id))
                assertNotNull(f.index.localUriOrNull(id))
                assertNotNull(engine.files.stat("old", f.path(id)))
                f.store.fail = false
                f.planner.run(CleanupReason.PLAYED)
                f.assertDeleted(id)
            }
        }

    @Test fun failedTransactionRestoresIndexAndNextRunRetriesTheJournal() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val id = f.completed().single()
                f.state(id, playedAt = f.clock.now() - DAY_MS)
                engine.db.useWriterConnection { connection ->
                    connection.usePrepared(
                        "CREATE TRIGGER reject_cleanup BEFORE DELETE ON download" +
                            " BEGIN SELECT RAISE(ABORT, 'test rollback'); END",
                    ) { it.step() }
                }
                assertFails { f.planner.run(CleanupReason.PLAYED) }
                assertNotNull(engine.dao.byEpisode(id))
                assertNotNull(f.index.localUriOrNull(id))
                assertNotNull(engine.files.stat("old", f.path(id)))
                assertTrue(f.deletes.entries().isNotEmpty())
                engine.db.useWriterConnection { connection ->
                    connection.usePrepared("DROP TRIGGER reject_cleanup") { it.step() }
                }
                f.planner.run(CleanupReason.PLAYED)
                f.assertDeleted(id)
            }
        }

    @Test fun overlappingRunsAreSingleFlightAndDoNotDeleteTwice() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val id = f.completed().single()
                f.state(id, playedAt = f.clock.now() - DAY_MS)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var journalWrites = 0
                f.store.beforeUpdate = {
                    journalWrites++
                    if (journalWrites == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                val runs = List(4) { async { f.planner.run(CleanupReason.PLAYED) } }
                entered.await()
                runCurrent()
                assertEquals(1, journalWrites)
                release.complete(Unit)
                runs.awaitAll()
                f.assertDeleted(id)
                assertEquals(listOf(id), f.removed)
            }
        }

    @Test fun playedObserverWaitsThirtySecondsAndCoalescesChanges() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val ids = f.completed(2)
                f.policy(ids, cleanupPolicy(deleteAfter = DeleteAfter.IMMEDIATELY))
                val observer = backgroundScope.launch { f.planner.observePlayed() }
                runCurrent()
                f.state(ids[0], playedAt = f.clock.now())
                runCurrent()
                advanceTimeBy(20_000)
                f.state(ids[1], playedAt = f.clock.now())
                runCurrent()
                advanceTimeBy(29_999)
                runCurrent()
                assertEquals(2, engine.dao.allRows().size)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(engine.dao.allRows().isEmpty())
                assertEquals(ids, f.removed)
                observer.cancelAndJoin()
            }
        }

    @Test fun dailyPassSweepsPartsAtFourteenDaysAndReportsOrphansWithoutDeletingThem() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val restartable = listOf(DownloadState.PAUSED, DownloadState.QUEUED, DownloadState.FAILED)
                val ids =
                    engine.seed(3) { row ->
                        row.copy(
                            state = restartable[(row.requestedAt - 1).toInt()],
                            downloadedBytes = 4096,
                            tempPath = DownloadPaths.part(row.episodeId),
                            etag = "etag",
                            lastModified = "date",
                        )
                    }
                for (id in ids) f.write(DownloadPaths.part(id))
                val modifiedAt = assertNotNull(engine.files.stat("old", DownloadPaths.part(ids[0]))).modifiedAt
                f.clock.nowMs = modifiedAt + 14 * DAY_MS - 1
                f.write(".partial/999.part")
                f.write("Podcast [p1]/Orphan [e999].mp3")
                f.write("user-notes.txt")
                f.write(".partial/999.move")
                f.planner.run(CleanupReason.DAILY)
                for (id in ids) assertNotNull(engine.files.stat("old", DownloadPaths.part(id)))
                assertNull(engine.files.stat("old", ".partial/999.part"))
                f.clock.nowMs++
                f.planner.run(CleanupReason.DAILY)
                for (id in ids) {
                    assertNull(engine.files.stat("old", DownloadPaths.part(id)))
                    val row = engine.row(id)
                    assertEquals(0L, row.downloadedBytes)
                    assertNull(row.etag)
                    assertNull(row.lastModified)
                    assertTrue(row.state in setOf(DownloadState.PAUSED, DownloadState.QUEUED, DownloadState.FAILED))
                }
                assertEquals(
                    4096L,
                    f.storage
                        .observeStorage()
                        .first()
                        .orphanBytes,
                )
                assertNotNull(engine.files.stat("old", "Podcast [p1]/Orphan [e999].mp3"))
                assertNotNull(engine.files.stat("old", "user-notes.txt"))
                assertNotNull(engine.files.stat("old", ".partial/999.move"))
            }
        }

    @Test fun dailySweepDoesNotResetOrDeleteLiveTransferParts() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val id =
                    engine
                        .seed {
                            it.copy(
                                state = DownloadState.DOWNLOADING,
                                runnerToken = "live",
                                tempPath = DownloadPaths.part(it.episodeId),
                            )
                        }.single()
                f.write(DownloadPaths.part(id))
                val modifiedAt = assertNotNull(engine.files.stat("old", DownloadPaths.part(id))).modifiedAt
                f.clock.nowMs = modifiedAt + 100 * DAY_MS
                f.planner.run(CleanupReason.DAILY)
                assertNotNull(engine.files.stat("old", DownloadPaths.part(id)))
                assertEquals("live", engine.row(id).runnerToken)
                assertEquals(DownloadState.DOWNLOADING, engine.row(id).state)
            }
        }

    private enum class Protection { FAVORITE, CURRENT, UP_NEXT, UNPLAYED }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

internal fun cleanupPolicy(
    enabled: Boolean = false,
    keep: Int = 3,
    deleteAfter: DeleteAfter = DeleteAfter.AFTER_24H,
) = EffectiveAutoDownload(enabled, keep, NetworkPolicy.UNMETERED, true, false, deleteAfter, SettingSource.AppDefault)

internal class CleanupRig(
    val engine: EngineRig,
) {
    val resolver = FakeEffectiveSettingsResolver()
    val clock = TestClock(engine.clock.now())
    val index = LocalMediaIndexImpl()
    val store = JournalTestStore()
    val settings = FakeSettingsRepository()
    val quota = DownloadQuota(settings)
    val deletes = DeferredDeletes(store, engine.files, engine.roots, engine.dao::allRows)
    val reconciler =
        DownloadReconciler(
            engine.db,
            engine.files,
            engine.roots,
            PlatformLocalUris(),
            index,
            deletes,
            engine.engine.claimMutex,
            { emptySet() },
            clock,
        )
    val storage =
        DownloadStorage(
            engine.db,
            engine.files,
            engine.roots,
            store,
            index,
            deletes,
            reconciler,
            engine.engine.claimMutex,
            FakePlaybackMediaLookup(),
            TestArtwork(),
            { _, _ -> null },
            quota,
        )
    val removed = mutableListOf<Long>()
    var beforeFinish: suspend (List<DownloadEntity>) -> Unit = {}
    private val controllerFiles =
        object : DownloadControllerFiles by storage {
            override suspend fun finishRemoval(rows: List<DownloadEntity>) {
                removed += rows.map { it.episodeId }
                beforeFinish(rows)
                storage.finishRemoval(rows)
            }
        }
    val planner =
        CleanupPlannerImpl(
            engine.db,
            resolver,
            clock,
            controllerFiles,
            deletes,
            index,
            reconciler,
            engine.engine.claimMutex,
            engine.dispatcher,
        )
    val controller =
        DownloadControllerImpl(
            engine.db,
            settings,
            FakeNetworkMonitor(),
            clock,
            engine.roots,
            engine.scheduler,
            engine.engine.claimMutex,
            engine.engine::stopTransfer,
            storage,
            FakePlaybackMediaLookup(),
            { false },
        )

    fun path(id: Long) = "Podcast [p1]/Episode [e$id].mp3"

    suspend fun completed(
        count: Int = 1,
        lane: DownloadLane = DownloadLane.AUTO,
    ): List<Long> {
        val ids =
            engine.seed(count) { row ->
                row.copy(
                    state = DownloadState.COMPLETED,
                    lane = lane,
                    totalBytes = 4096,
                    relativePath = path(row.episodeId),
                    finalUri = "file:///old/${path(row.episodeId)}",
                )
            }
        policy(ids, cleanupPolicy())
        for (id in ids) {
            write(path(id))
            index.put(id, checkNotNull(engine.row(id).finalUri))
        }
        return ids
    }

    suspend fun policy(
        ids: List<Long>,
        value: EffectiveAutoDownload,
    ) {
        val podcastIds =
            engine.db
                .episodeDao()
                .downloadSources(ids)
                .map { it.podcastId }
                .toSet()
        resolver.autoDownloadValues.value += podcastIds.associateWith { value }
    }

    suspend fun state(
        id: Long,
        playedAt: Long? = null,
        favorite: Boolean = false,
        startedAt: Long? = null,
        dismissedAt: Long? = null,
    ) {
        engine.db.episodeStateDao().upsert(
            episodeStateEntity(id, playedAt) {
                copy(isFavorite = favorite, startedAt = startedAt, downloadDismissedAt = dismissedAt)
            },
        )
    }

    suspend fun current(id: Long?) {
        engine.db.playSessionDao().ensureNow(clock.now())
        engine.db.playSessionDao().write(checkNotNull(engine.db.playSessionDao().get()).copy(currentEpisodeId = id))
    }

    suspend fun write(path: String) = ReconcileRig(engine).write(path)

    suspend fun assertDeleted(id: Long) {
        assertNull(engine.dao.byEpisode(id))
        assertNull(index.localUriOrNull(id))
        assertNull(engine.files.stat("old", path(id)))
    }
}
