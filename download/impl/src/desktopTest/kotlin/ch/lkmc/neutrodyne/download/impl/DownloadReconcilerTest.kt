// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadReconcilerTest {
    @Test fun mergePreservesTheOnlyFileAcrossRollbackAndMoveThenReplaceDefersCurrentDeletion() =
        runTest {
            EngineRig(this).use { f ->
                val ids =
                    f.seed(2) { row ->
                        row.copy(
                            state = DownloadState.COMPLETED,
                            totalBytes = 4096,
                            relativePath = "saved-${row.episodeId}.mp3",
                            finalUri = "file:///old/saved-${row.episodeId}.mp3",
                        )
                    }
                val loser = ids[0]
                val winner = ids[1]
                f.dao.removeUnclaimed(listOf(winner))
                val rig = ReconcileRig(f)
                val row = f.row(loser)
                val path = checkNotNull(row.relativePath)
                val uri = checkNotNull(row.finalUri)
                rig.write(path)
                rig.index.put(loser, uri)
                f.db.playSessionDao().ensureNow(f.clock.now())
                f.db.playSessionDao().write(checkNotNull(f.db.playSessionDao().get()).copy(currentEpisodeId = loser))
                val playback =
                    ch.lkmc.neutrodyne.core.testing
                        .FakePlaybackBeforeUnsubscribe()
                val completed = mutableListOf<Long>()
                val lifecycle =
                    DownloadLifecycleImpl(
                        f.db,
                        rig.controller,
                        rig.storage,
                        rig.index,
                        playback,
                        f.engine.claimMutex,
                        Dispatchers.Unconfined,
                        { completed += it.episodeId },
                    )
                lifecycle.beforeEpisodeMerge(loser, winner)
                assertNull(rig.index.localUriOrNull(loser))
                assertTrue(rig.deletes.entries().isNotEmpty())
                lifecycle.afterEpisodeMerges(listOf(loser, winner)) // The database attempt rolled back.
                assertEquals(uri, rig.index.localUriOrNull(loser))
                assertNotNull(f.files.stat("old", path))
                assertTrue(rig.deletes.entries().isEmpty())
                lifecycle.beforeEpisodeMerge(loser, winner)
                f.db.podcastMergeDao().moveDownload(loser, winner)
                f.db.podcastMergeDao().moveCurrentEpisode(loser, winner)
                lifecycle.afterEpisodeMerges(listOf(loser, winner))
                assertNull(rig.index.localUriOrNull(loser))
                assertEquals(uri, rig.index.localUriOrNull(winner))
                assertEquals(row.copy(episodeId = winner), f.row(winner))
                assertNotNull(f.files.stat("old", path))
                assertEquals(listOf(loser, winner), completed)
                assertEquals(2, playback.calls.size)
                lifecycle.beforeLibraryReplace()
                assertNull(f.dao.byEpisode(winner))
                assertNull(rig.index.localUriOrNull(winner))
                assertNotNull(f.files.stat("old", path))
                assertEquals(
                    null,
                    f.db
                        .episodeStateDao()
                        .byEpisode(winner)
                        ?.downloadDismissedAt,
                )
                rig.deletes.drain(null)
                assertNull(f.files.stat("old", path))
                assertTrue(rig.deletes.entries().isEmpty())
            }
        }

    @Test fun repeatedReadinessLoadKeepsPublishedSnapshot() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed { it.copy(state = DownloadState.COMPLETED, finalUri = "file:///first.mp3") }.single()
                val rig = ReconcileRig(f)
                rig.reconciler.initialLoad()
                assertEquals("file:///first.mp3", rig.index.localUriOrNull(id))
                rig.index.put(id, "file:///new.mp3")
                rig.reconciler.initialLoad()
                assertEquals("file:///new.mp3", rig.index.localUriOrNull(id))
            }
        }

    @Test fun deleteArrivingImmediatelyAfterRenameStillJournalsAndRemovesFinalFile() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed().single()
                f.faults.afterMove = {
                    f.engine.stopRunner(RunnerToken("desk:test"), StopIntent.Delete(DeleteReason.USER))
                }
                f.engine.drain(drainRequest())
                assertNull(f.dao.byEpisode(id))
                assertEquals(listOf("journal:$id", "delete:$id"), f.events)
                val directories = f.files.list("old", "").filter { it.directory }
                assertTrue(directories.all { f.files.list("old", it.path).isEmpty() })
            }
        }

    @Test fun controllerDeletesRowAndIndexButKeepsCurrentFileUntilSessionChanges() =
        runTest {
            EngineRig(this).use { f ->
                val id =
                    f
                        .seed {
                            it.copy(
                                state = DownloadState.COMPLETED,
                                totalBytes = 4096,
                                relativePath = "playing.mp3",
                                finalUri = "file:///old/playing.mp3",
                            )
                        }.single()
                val rig = ReconcileRig(f)
                rig.write("playing.mp3")
                f.db.playSessionDao().ensureNow(f.clock.now())
                f.db.playSessionDao().write(checkNotNull(f.db.playSessionDao().get()).copy(currentEpisodeId = id))
                rig.index.put(id, "file:///old/playing.mp3")
                val storage = rig.storage
                val controller = rig.controller
                controller.delete(listOf(id), DeleteReason.USER)
                assertNull(f.dao.byEpisode(id))
                assertNull(rig.index.localUriOrNull(id))
                assertNotNull(f.files.stat("old", "playing.mp3"))
                assertTrue(rig.deletes.entries().isNotEmpty())
                assertNotNull(
                    f.db
                        .episodeStateDao()
                        .byEpisode(id)
                        ?.downloadDismissedAt,
                )
                rig.deletes.drain(null)
                assertNull(f.files.stat("old", "playing.mp3"))
                rig.write("Podcast [p1]/Orphan [e99].mp3")
                rig.reconciler.reconcile()
                assertEquals(4096L, storage.observeStorage().first().orphanBytes)
                assertEquals(OrphanDeleteResult.Deleted(4096), controller.deleteOrphanFiles())
                assertNull(f.files.stat("old", "Podcast [p1]/Orphan [e99].mp3"))
            }
        }

    @Test fun processDeathTrimsOnlyTheTornTailBeforeResume() =
        transferTest { part ->
            part.append(mediaBytes(200_000), 200_000)
            val prepared =
                transfer.prepare(
                    row.copy(lastStopReason = DownloadStopReasons.PROCESS_DEATH, etag = "\"tag\""),
                    part,
                )
            assertEquals(200_000L - 64 * 1024, (prepared as Prepared.Go).plan.offset)
            assertEquals(200_000L - 64 * 1024, part.length())
            assertEquals(0, server.requestCount)
        }

    @Test fun crashAfterRenameBeforeCompletionIsAdoptedWithoutTransfer() =
        runTest {
            EngineRig(this).use { f ->
                val id =
                    f
                        .seed {
                            it.copy(state = DownloadState.DOWNLOADING, runnerToken = "desk:dead", totalBytes = 4096)
                        }.single()
                val source =
                    f.db
                        .episodeDao()
                        .downloadSources(listOf(id))
                        .single()
                val part = f.files.openPart("old", DownloadPaths.part(id))
                part.append(mediaBytes(), 4096)
                val final =
                    f.verification.finish(
                        part,
                        source,
                        TransferPlan(id, f.row(id).sourceRef, 0, null, null),
                        TransferResult.Complete(4096, "audio/mpeg", "\"tag\"", null, MediaKind.MP3),
                    )
                assertEquals(DownloadState.DOWNLOADING, f.row(id).state)
                val rig = ReconcileRig(f)
                rig.reconciler.reconcile()
                f.source.beforePrepare = { row, _ -> f.verification.adopt(row, source, null) }
                f.engine.drain(drainRequest())
                assertEquals(DownloadState.COMPLETED, f.row(id).state)
                assertEquals(final.relativePath, f.row(id).relativePath)
                assertTrue(f.source.calls.isEmpty())
                assertEquals(final.finalUri, f.indexed[id])
            }
        }

    @Test fun lookupIsMemoryOnlyBeforeLoadWhileDatabaseIsHeld() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed { it.copy(state = DownloadState.COMPLETED, finalUri = "file:///old/file") }.single()
                val index = LocalMediaIndexImpl()
                assertNull(index.localUriOrNull(id))
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holder =
                    async {
                        f.engine.claimMutex.withLock {
                            started.complete(Unit)
                            release.await()
                        }
                    }
                started.await()
                val rig = ReconcileRig(f, index)
                val load = async { rig.reconciler.initialLoad() }
                assertNull(index.localUriOrNull(id))
                assertFalse(load.isCompleted)
                release.complete(Unit)
                holder.await()
                load.await()
                assertEquals("file:///old/file", index.localUriOrNull(id))
                rig.deletes.journal(listOf(f.row(id)))
                rig.store.fail = true
                index.remove(id)
                assertFailsWith<IOException> { ReconcileRig(f, index, store = rig.store).reconciler.initialLoad() }
                assertEquals("file:///old/file", index.localUriOrNull(id))
            }
        }

    @Test fun changesPublishOnlyActualMemoryTransitions() =
        runTest {
            val index = LocalMediaIndexImpl()
            val changed = async(start = CoroutineStart.UNDISPATCHED) { index.changes.first() }
            index.put(1, "file:///one")
            assertEquals(setOf(1L), changed.await())
            index.replace(mapOf(2L to "file:///two"))
            assertNull(index.localUriOrNull(1))
            assertEquals("file:///two", index.localUriOrNull(2))
            index.remove(2)
            assertNull(index.localUriOrNull(2))
        }

    @Test fun staleTokensResetOnceAndRegisteredOwnersSurviveParallelPasses() =
        runTest {
            EngineRig(this).use { f ->
                val stale =
                    f
                        .seed {
                            it.copy(
                                state = DownloadState.DOWNLOADING,
                                runnerToken = "desk:dead",
                                attempt = 3,
                            )
                        }.single()
                val live = f.seed { it.copy(state = DownloadState.RESOLVING, runnerToken = "desk:live") }.single()
                val rig = ReconcileRig(f, live = { setOf("desk:live") })
                List(4) { async(Dispatchers.Default) { rig.reconciler.reconcile() } }.awaitAll()
                assertEquals(DownloadState.QUEUED, f.row(stale).state)
                assertEquals(3, f.row(stale).attempt)
                assertEquals(DownloadStopReasons.PROCESS_DEATH, f.row(stale).lastStopReason)
                assertEquals("desk:live", f.row(live).runnerToken)
                val late = f.seed { it.copy(state = DownloadState.DOWNLOADING, runnerToken = "desk:late") }.single()
                rig.reconciler.resetOrphanedRunners()
                assertEquals("desk:late", f.row(late).runnerToken)
            }
        }

    @Test fun missingLengthAndUnavailableRootsRemoveIndexThenRemountRewritesUri() =
        runTest {
            EngineRig(this).use { f ->
                val id =
                    f
                        .seed {
                            it.copy(
                                state = DownloadState.COMPLETED,
                                totalBytes = 4096,
                                relativePath = "Podcast [p1]/File [e1].mp3",
                                finalUri = "file:///wrong",
                            )
                        }.single()
                val rig = ReconcileRig(f)
                rig.write("Podcast [p1]/File [e1].mp3")
                rig.reconciler.initialLoad()
                rig.reconciler.reconcile()
                assertEquals("file:///old/Podcast%20%5Bp1%5D/File%20%5Be1%5D.mp3", rig.index.localUriOrNull(id))
                f.roots.available.remove("old")
                rig.reconciler.reconcile()
                assertEquals(DownloadError.STORAGE_UNAVAILABLE, f.row(id).lastError)
                assertNull(rig.index.localUriOrNull(id))
                f.roots.available.add("old")
                rig.reconciler.reconcile()
                assertEquals(DownloadState.COMPLETED, f.row(id).state)
                rig.write("Podcast [p1]/File [e1].mp3", byteArrayOf(1))
                rig.reconciler.reportFileMissing(id)
                assertEquals(DownloadState.MISSING, f.row(id).state)
                assertNull(f.row(id).lastError)
                assertNull(rig.index.localUriOrNull(id))
            }
        }

    @Test fun journalSurvivesEveryCrashBoundaryAndProtectsNewOwners() =
        runTest {
            for (boundary in JournalBoundary.entries) {
                EngineRig(this).use { f ->
                    val id = f.seed { it.copy(relativePath = "folder/file|name.mp3") }.single()
                    val rig = ReconcileRig(f)
                    val row = f.row(id)
                    rig.write(checkNotNull(row.relativePath))
                    if (boundary == JournalBoundary.WRITE_FAILED) {
                        rig.store.fail = true
                        assertFailsWith<IOException> { rig.deletes.journal(listOf(row)) }
                        assertNotNull(f.dao.byEpisode(id))
                        return@use
                    }
                    rig.deletes.journal(listOf(row))
                    if (boundary != JournalBoundary.BEFORE_REMOVAL) f.dao.removeUnclaimed(listOf(id))
                    if (boundary == JournalBoundary.REDOWNLOADED) {
                        f.dao.insert(row.copy(state = DownloadState.COMPLETED))
                    }
                    if (boundary == JournalBoundary.UNMOUNTED) f.roots.available.remove("old")
                    val restarted = ReconcileRig(f, store = rig.store)
                    restarted.deletes.drain(if (boundary == JournalBoundary.PLAYING) id else null)
                    f.roots.available.add("old")
                    val deferred = boundary in setOf(JournalBoundary.PLAYING, JournalBoundary.UNMOUNTED)
                    assertEquals(deferred, rig.deletes.entries().isNotEmpty(), boundary.name)
                    val retained = boundary != JournalBoundary.AFTER_REMOVAL
                    assertEquals(retained, f.files.stat("old", checkNotNull(row.relativePath)) != null, boundary.name)
                    // At process restart the persisted current ID has no surviving playback pin.
                    restarted.reconciler.initialLoad()
                    val owned = boundary in setOf(JournalBoundary.BEFORE_REMOVAL, JournalBoundary.REDOWNLOADED)
                    assertEquals(owned, f.files.stat("old", checkNotNull(row.relativePath)) != null, boundary.name)
                }
            }
        }

    private enum class JournalBoundary {
        WRITE_FAILED,
        BEFORE_REMOVAL,
        AFTER_REMOVAL,
        PLAYING,
        UNMOUNTED,
        REDOWNLOADED,
    }

    @Test fun stalePartsClearValidatorsUnreferencedPartsGoAndOrphansNeedExplicitAction() =
        runTest {
            EngineRig(this).use { f ->
                val id =
                    f
                        .seed {
                            it.copy(
                                state = DownloadState.PAUSED,
                                downloadedBytes = 4096,
                                etag = "tag",
                                lastModified = "date",
                                tempPath = ".partial/1.part",
                            )
                        }.single()
                val rig = ReconcileRig(f)
                rig.write(".partial/1.part")
                rig.write(".partial/999.part")
                rig.write("Podcast [p1]/Orphan [e99].mp3")
                rig.write("user/leave-me.txt")
                rig.reconciler.reconcile()
                val modified = assertNotNull(f.files.stat("old", ".partial/1.part")).modifiedAt
                assertNull(f.files.stat("old", ".partial/999.part"))
                rig.ageMs = modified + 14L * 24 * 60 * 60 * 1000 - f.clock.now()
                rig.reconciler.reconcile()
                assertNull(f.files.stat("old", ".partial/1.part"))
                assertNull(f.files.stat("old", ".partial/999.part"))
                assertEquals(DownloadState.PAUSED, f.row(id).state)
                assertEquals(0L, f.row(id).downloadedBytes)
                assertNull(f.row(id).etag)
                assertNull(f.row(id).lastModified)
                assertEquals(4096L, rig.reconciler.orphanBytes.value)
                assertNotNull(f.files.stat("old", "Podcast [p1]/Orphan [e99].mp3"))
                assertNotNull(f.files.stat("old", "user/leave-me.txt"))
            }
        }

    @Test fun activePartsAreNeverSweptWhileReconciliationAndDrainOverlap() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed().single()
                val rig = ReconcileRig(f, live = { setOf("desk:test") })
                f.source.action = { _, _, _ -> kotlinx.coroutines.awaitCancellation() }
                val runner = async { f.engine.drain(drainRequest()) }
                f.source.starts.receive()
                rig.write(DownloadPaths.part(id))
                rig.ageMs = 365L * 24 * 60 * 60 * 1000
                rig.reconciler.reconcile()
                assertNotNull(f.files.stat("old", DownloadPaths.part(id)))
                assertEquals("desk:test", f.row(id).runnerToken)
                runner.cancel()
                runner.join()
            }
        }
}

internal class JournalTestStore : SettingStore {
    private val delegate = FakeSettingsRepository()
    private val mutex = Mutex()
    var fail = false
    var beforeUpdate: suspend () -> Unit = {}
    override val file = SettingsFile.DEVICE

    override fun <T : Any> observe(key: SettingKey<T>) = delegate.observe(key)

    override suspend fun <T : Any> get(key: SettingKey<T>) = delegate.get(key)

    override suspend fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ) {
        delegate.set(key, value)
    }

    override suspend fun <T : Any> update(
        key: SettingKey<T>,
        transform: (T) -> T,
    ) = mutex.withLock {
        if (fail) throw IOException("Injected journal failure")
        beforeUpdate()
        set(key, transform(get(key)))
    }

    override suspend fun reset(key: SettingKey<*>) = delegate.reset(key)
}

internal class ReconcileRig(
    val engine: EngineRig,
    val index: LocalMediaIndexImpl = LocalMediaIndexImpl(),
    val store: JournalTestStore = JournalTestStore(),
    live: () -> Set<String> = { emptySet() },
) {
    var ageMs = 0L
    private val clock =
        object : Clock by engine.clock {
            override fun now() = engine.clock.now() + ageMs
        }
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
            live,
            clock,
        )

    val storage by lazy {
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
            DownloadQuota(FakeSettingsRepository()),
        )
    }
    val controller by lazy {
        DownloadControllerImpl(
            engine.db,
            FakeSettingsRepository(),
            FakeNetworkMonitor(),
            engine.clock,
            engine.roots,
            engine.scheduler,
            engine.engine.claimMutex,
            engine.engine::stopTransfer,
            storage,
            FakePlaybackMediaLookup(),
            { false },
        )
    }

    suspend fun write(
        path: String,
        bytes: ByteArray = mediaBytes(),
    ) {
        engine.faults.fake.createDirectories(checkNotNull("/old/$path".toPath().parent))
        engine.faults.fake.write("/old/$path".toPath()) { write(bytes) }
    }
}

internal class TestArtwork : ArtworkStore {
    override fun pinnedFile(key: String): Path? = null

    override fun pin(
        ref: ArtworkRef,
        reason: PinReason,
        ownerId: Long,
    ) = Unit

    override fun unpin(
        key: String,
        reason: PinReason,
        ownerId: Long,
    ) = Unit

    override fun contentUri(
        key: String,
        version: Int,
    ) = "file:///artwork/$key"

    override fun isPinned(key: String) = false

    override fun pinnedPath(key: String): Path? = null

    override suspend fun collectGarbage() = 0
}
