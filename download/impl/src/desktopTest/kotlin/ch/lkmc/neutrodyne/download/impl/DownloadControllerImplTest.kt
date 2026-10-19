// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.DownloadRootRegistration
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.AudioAlternate
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeDownloadRootRegistration
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RejectReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.ShareableFile
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import ch.lkmc.neutrodyne.download.api.StorageUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.DownloadError as Error
import ch.lkmc.neutrodyne.core.model.DownloadLane as Lane
import ch.lkmc.neutrodyne.core.model.DownloadState as State
import ch.lkmc.neutrodyne.core.model.WaitReason as Wait

class DownloadControllerImplTest {
    @Test fun requestStatesAndTombstones() =
        runTest {
            fixture { f ->
                for (state in State.entries) {
                    val id = f.episode()
                    val checkpoint =
                        f.insert(id, state).copy(
                            tempPath = "part",
                            relativePath = "old",
                            finalUri = "file:///old",
                            totalBytes = 100,
                            downloadedBytes = 50,
                            etag = "etag",
                            lastModified = "then",
                            attempt = 4,
                            integrityFailures = 1,
                        )
                    if (checkpoint.runnerToken == null) {
                        f.dao.updateUnclaimed(checkpoint, state)
                    } else {
                        f.dao.transition(checkpoint, state, checkNotNull(checkpoint.runnerToken))
                    }
                    f.db.episodeStateDao().upsert(
                        episodeStateEntity(id) { copy(downloadDismissedAt = 1, isFavorite = true) },
                    )
                    val result = assertIs<RequestResult.Queued>(f.controller.request(listOf(id, id), Lane.MANUAL, true))
                    val row = f.row(id)
                    assertEquals(if (state == State.COMPLETED) 1 else 0, result.alreadyPresent)
                    val resumed = state in setOf(State.PAUSED, State.FAILED, State.MISSING)
                    assertEquals(if (resumed) State.QUEUED else state, row.state)
                    assertNull(
                        f.db
                            .episodeStateDao()
                            .byEpisode(id)
                            ?.downloadDismissedAt,
                    )
                    assertTrue(
                        f.db
                            .episodeStateDao()
                            .byEpisode(id)
                            ?.isFavorite == true,
                    )
                    if (state == State.MISSING) {
                        assertEquals(0L, row.downloadedBytes)
                        assertEquals(
                            listOf(null, null, null, null, null),
                            listOf(row.tempPath, row.relativePath, row.finalUri, row.etag, row.lastModified),
                        )
                    } else {
                        assertEquals(50L, row.downloadedBytes)
                        assertEquals("etag", row.etag)
                    }
                    if (state == State.FAILED ||
                        state == State.MISSING
                    ) {
                        assertEquals(0, row.attempt + row.integrityFailures)
                    }
                }
            }
        }

    @Test fun duplicateCommandsCoverEveryState() =
        runTest {
            fixture { f ->
                val states = State.entries
                val commands =
                    listOf<Pair<suspend (List<Long>) -> Unit, List<State?>>>(
                        f.controller::pause to
                            listOf(
                                State.PAUSED,
                                State.PAUSED,
                                State.PAUSED,
                                State.PAUSED,
                                State.PAUSED,
                                State.COMPLETED,
                                State.MISSING,
                                State.FAILED,
                            ),
                        f.controller::resume to
                            listOf(
                                State.QUEUED,
                                State.RESOLVING,
                                State.DOWNLOADING,
                                State.QUEUED,
                                State.VERIFYING,
                                State.COMPLETED,
                                State.MISSING,
                                State.FAILED,
                            ),
                        f.controller::retry to
                            listOf(
                                State.QUEUED,
                                State.RESOLVING,
                                State.DOWNLOADING,
                                State.PAUSED,
                                State.VERIFYING,
                                State.COMPLETED,
                                State.MISSING,
                                State.QUEUED,
                            ),
                        f.controller::cancel to listOf(null, null, null, null, null, State.COMPLETED, null, null),
                    )
                for ((command, expected) in commands) {
                    for ((index, state) in states.withIndex()) {
                        val id = f.episode()
                        f.insert(id, state)
                        command(listOf(id, id))
                        command(listOf(id))
                        assertEquals(expected[index], f.dao.byEpisode(id)?.state, "$state")
                        if (expected[index] ==
                            null
                        ) {
                            assertEquals(
                                f.clock.now(),
                                f.db
                                    .episodeStateDao()
                                    .byEpisode(id)
                                    ?.downloadDismissedAt,
                            )
                        }
                    }
                }
            }
        }

    @Test fun meteredDecisionIsReadOnlyAndExcludesUnactionableItems() =
        runTest {
            fixture { f ->
                val ids =
                    listOf(
                        f.episode {
                            copy(enclosureLength = 120000)
                        },
                        f.episode { copy(enclosureLength = 99, durationMs = 1500) },
                        f.episode(),
                    )
                val complete = f.episode()
                f.insert(complete, State.COMPLETED)
                f.insert(ids[1], State.PAUSED)
                f.db.episodeStateDao().upsert(episodeStateEntity(ids[0]) { copy(downloadDismissedAt = 1) })
                f.network.setStatus(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
                val before = f.dao.allRows()
                assertEquals(
                    RequestResult.NeedsMeteredDecision(3, 144000, 1),
                    f.controller.request(ids + ids[0] + complete + -1, Lane.MANUAL),
                )
                assertEquals(before, f.dao.allRows())
                assertEquals(
                    1L,
                    f.db
                        .episodeStateDao()
                        .byEpisode(ids[0])
                        ?.downloadDismissedAt,
                )
                assertTrue(f.scheduled.isEmpty())
                assertEquals(0, f.rootReads)
                assertFalse(f.settings.calls.any { it.startsWith("set(") })
                f.controller.request(ids, Lane.MANUAL, false)
                assertEquals(listOf(false, false, false), ids.map { f.row(it).allowMetered })
                assertIs<RequestResult.Queued>(f.controller.request(ids + complete, Lane.MANUAL))
                assertNull(
                    f.db
                        .episodeStateDao()
                        .byEpisode(ids[0])
                        ?.downloadDismissedAt,
                )
                val huge = (1..2).map { f.episode { copy(durationMs = Long.MAX_VALUE) } }
                val decision = assertIs<RequestResult.NeedsMeteredDecision>(f.controller.request(huge, Lane.MANUAL))
                assertEquals(Long.MAX_VALUE, decision.knownBytes)
                for (policy in listOf(ManualMeteredPolicy.NEVER, ManualMeteredPolicy.ALWAYS)) {
                    f.settings.set(DownloadSettingKeys.MANUAL_METERED, policy)
                    val id = f.episode()
                    f.controller.request(listOf(id), Lane.MANUAL)
                    assertEquals(policy == ManualMeteredPolicy.ALWAYS, f.row(id).allowMetered)
                }
            }
        }

    @Test fun rejectedSourcesAndPreferredAudio() =
        runTest {
            fixture { f ->
                val invalid =
                    Availability.entries
                        .filter { it != Availability.AVAILABLE }
                        .associate { availability ->
                            f.episode { copy(availability = availability) } to RejectReason.UNAVAILABLE
                        }.toMutableMap()
                invalid[f.episode { copy(enclosureUrl = null) }] = RejectReason.NOT_DOWNLOADABLE
                invalid[
                    f.episode {
                        copy(
                            enclosureUrl = "https://origin/e.M3U8?token",
                            enclosureType = "audio/mpeg",
                        )
                    },
                ] =
                    RejectReason.UNSUPPORTED_STREAM
                invalid[f.episode { copy(enclosureType = "APPLICATION/VND.APPLE.MPEGURL; charset=utf8") }] =
                    RejectReason.UNSUPPORTED_STREAM
                val podcast =
                    f.db.podcastDao().insertPodcast(
                        podcastEntity { copy(sourceType = SourceType.YOUTUBE_CHANNEL) },
                    )
                invalid[
                    f.db.episodeDao().insertStub(
                        episodeEntity(podcastId = podcast) { copy(externalMediaId = "video") },
                    ),
                ] =
                    RejectReason.YOUTUBE_NOT_SUPPORTED
                invalid[-1] = RejectReason.EPISODE_GONE
                assertEquals(
                    invalid,
                    assertIs<RequestResult.Queued>(f.controller.request(invalid.keys.toList(), Lane.MANUAL)).rejected,
                )
                val id = f.episode { copy(isVideo = true) }
                f.media.items.value =
                    mapOf(
                        id to
                            PlaybackMediaInfo(
                                id,
                                f.podcast,
                                "e",
                                "p",
                                ArtworkRef("m-p", null, 0),
                                SourceType.RSS,
                                Availability.AVAILABLE,
                                true,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                audioAlternate = AudioAlternate("https://origin/audio.mp3", "audio/mpeg", 500000),
                            ),
                    )
                assertEquals(
                    mapOf(id to RejectReason.UNAVAILABLE),
                    assertIs<RequestResult.Queued>(f.controller.request(listOf(id), Lane.AUTO)).rejected,
                )
                f.controller.request(listOf(id), Lane.MANUAL)
                assertEquals("https://origin/audio.mp3", f.row(id).sourceRef)
                assertEquals(500000L, f.row(id).estimatedBytes)
            }
        }

    @Test fun orderingPromotionRetryAndCheckpoints() =
        runTest {
            fixture { f ->
                val ids = (1..3).map { f.episode() }
                f.controller.request(ids.reversed() + ids[0], Lane.MANUAL)
                assertEquals(
                    ids.reversed(),
                    f.dao.candidates(listOf(Lane.MANUAL), Long.MAX_VALUE, true, true, true).map {
                        it.episodeId
                    },
                )
                val old =
                    f
                        .row(
                            ids[0],
                        ).copy(
                            lane = Lane.AUTO,
                            requireCharging = true,
                            tempPath = "part",
                            etag = "etag",
                            downloadedBytes = 50,
                        )
                f.dao.updateUnclaimed(old, old.state)
                f.network.setStatus(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
                assertIs<RequestResult.NeedsMeteredDecision>(f.controller.promote(ids[0]))
                assertEquals(old, f.row(ids[0]))
                f.settings.set(DownloadSettingKeys.MANUAL_METERED, ManualMeteredPolicy.ALWAYS)
                f.controller.promote(ids[0])
                val promoted = f.row(ids[0])
                assertEquals(listOf("part", "etag"), listOf(promoted.tempPath, promoted.etag))
                assertEquals(50L, promoted.downloadedBytes)
                assertEquals(Lane.MANUAL, promoted.lane)
                assertEquals(200, promoted.priority)
                assertTrue(promoted.allowMetered)
                assertFalse(promoted.requireCharging)
                val failed =
                    promoted.copy(
                        state = State.FAILED,
                        attempt = 7,
                        integrityFailures = 2,
                        lastError = Error.HTTP_SERVER,
                        lastHttpStatus = 503,
                    )
                f.dao.updateUnclaimed(failed, promoted.state)
                f.controller.retry(listOf(ids[0]))
                val retried = f.row(ids[0])
                assertEquals(0, retried.attempt + retried.integrityFailures)
                assertNull(retried.lastError)
                assertNull(retried.lastHttpStatus)
                assertEquals("etag", retried.etag)
                f.dao.updateUnclaimed(
                    retried.copy(waitReason = Wait.BACKOFF, nextAttemptAt = Long.MAX_VALUE),
                    retried.state,
                )
                f.controller.retry(listOf(ids[0]))
                assertNull(f.row(ids[0]).nextAttemptAt)
            }
        }

    @Test fun duplicateAdmissionAndClaimCommandRaces() =
        runTest {
            fixture { f ->
                repeat(8) { n ->
                    val id = f.episode()
                    coroutineScope {
                        (1..3)
                            .map {
                                async(
                                    Dispatchers.Default,
                                ) { f.controller.request(listOf(id, id), Lane.MANUAL) }
                            }.awaitAll()
                    }
                    assertEquals(1, f.dao.allRows().count { it.episodeId == id })
                    coroutineScope {
                        val claim =
                            async(Dispatchers.Default) {
                                f.mutex.lock()
                                try {
                                    f.dao.claim(id, "race-$n")
                                } finally {
                                    f.mutex.unlock()
                                }
                            }
                        val command =
                            async(Dispatchers.Default) {
                                if (n % 2 == 0) f.controller.pause(listOf(id)) else f.controller.cancel(listOf(id))
                            }
                        claim.await()
                        command.await()
                    }
                    assertEquals(if (n % 2 == 0) State.PAUSED else null, f.dao.byEpisode(id)?.state)
                    assertNull(f.dao.byEpisode(id)?.runnerToken)
                    assertEquals(0, f.dao.claim(id, "late"))
                }
            }
        }

    @Test fun deletionJournalAndUserOnlyTombstones() =
        runTest {
            fixture { f ->
                for (state in State.entries) {
                    val id = f.episode()
                    f.insert(id, state)
                    f.failJournal = true
                    val failure = runCatching { f.controller.delete(listOf(id), DeleteReason.USER) }.exceptionOrNull()
                    assertIs<IllegalStateException>(failure)
                    assertNotNull(f.dao.byEpisode(id))
                    assertNull(f.db.episodeStateDao().byEpisode(id))
                    f.failJournal = false
                    f.controller.delete(listOf(id, id), DeleteReason.LIFECYCLE)
                    assertNull(f.dao.byEpisode(id))
                    assertNull(
                        f.db
                            .episodeStateDao()
                            .byEpisode(id)
                            ?.downloadDismissedAt,
                    )
                    assertTrue(f.events.indexOf("journal:$id") < f.events.indexOf("finish:$id"))
                    f.insert(id, state)
                    f.controller.delete(listOf(id), DeleteReason.USER)
                    assertEquals(
                        f.clock.now(),
                        f.db
                            .episodeStateDao()
                            .byEpisode(id)
                            ?.downloadDismissedAt,
                    )
                }
            }
        }

    @Test fun rootChangesUseOwnerOrFileImplementation() =
        runTest {
            val owner = FakeDownloadRootRegistration()
            owner.changeResult = RootChangeResult.Applied
            fixture(owner) { f ->
                assertEquals(owner.registrationResult, f.controller.registerFolderRoot("/chosen"))
                assertEquals(
                    owner.changeResult,
                    f.controller.changeRoot(
                        "chosen",
                        RootChangeMode.MOVE_EXISTING,
                    ),
                )
            }
            assertEquals(listOf("/chosen"), owner.registrations)
            assertEquals(listOf("chosen" to RootChangeMode.MOVE_EXISTING), owner.changes)
            fixture { f ->
                assertEquals(
                    RootChangeResult.Applied,
                    f.controller.changeRoot("chosen", RootChangeMode.MOVE_EXISTING),
                )
            }
        }

    private suspend fun fixture(
        registration: DownloadRootRegistration? = null,
        block: suspend (Fixture) -> Unit,
    ) {
        val db = TestDb.inMemory()
        try {
            block(Fixture(db, db.podcastDao().insertPodcast(podcastEntity()), registration))
        } finally {
            db.close()
        }
    }

    private class Fixture(
        val db: NeutrodyneDatabase,
        val podcast: Long,
        registration: DownloadRootRegistration?,
    ) {
        val dao = db.downloadDao()
        val clock = TestClock()
        val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
        val settings = FakeSettingsRepository()
        val media = FakePlaybackMediaLookup()
        val mutex = Mutex()
        val scheduled = mutableListOf<Lane>()
        val events = mutableListOf<String>()
        var failJournal = false
        var rootReads = 0
        private val files =
            object : DownloadControllerFiles {
                override suspend fun prepareRemoval(rows: List<DownloadEntity>) {
                    check(!failJournal) { "journal unavailable" }
                    rows.forEach { row ->
                        assertNotNull(dao.byEpisode(row.episodeId), "row must exist when journaled")
                        events += "journal:${row.episodeId}"
                    }
                }

                override suspend fun finishRemoval(rows: List<DownloadEntity>) {
                    rows.forEach { row ->
                        assertNull(dao.byEpisode(row.episodeId), "commit must precede file cleanup")
                        events += "finish:${row.episodeId}"
                    }
                }

                override suspend fun reportFileMissing(episodeId: Long) {
                    events += "missing:$episodeId"
                }

                override fun observeStorage() = MutableStateFlow(StorageUsage("int", 0, 0, 0, null, null, false))

                override suspend fun changeRoot(
                    rootId: String,
                    mode: RootChangeMode,
                ) = RootChangeResult.Applied

                override suspend fun deleteOrphanFiles() = OrphanDeleteResult.Unavailable

                override suspend fun shareableFile(episodeId: Long): ShareableFile? = null
            }
        val controller =
            DownloadControllerImpl(
                db,
                settings,
                network,
                clock,
                object : StorageRoots {
                    override suspend fun current(): String {
                        rootReads++
                        return "int"
                    }

                    override suspend fun list(): List<StorageRootInfo> = emptyList()

                    override suspend fun resolve(rootId: String) = ResolvedRoot(rootId, "/downloads")

                    override suspend fun ensureSpace(
                        rootId: String,
                        requiredBytes: Long,
                    ): StorageFailure? = null

                    override suspend fun classify(
                        error: Throwable,
                        rootId: String,
                    ) = StorageFailure.IO
                },
                object : DownloadScheduler {
                    override suspend fun ensureScheduled(lane: Lane) {
                        scheduled += lane
                    }

                    override fun wake(lane: Lane) = Unit

                    override suspend fun scheduleWake(
                        lane: Lane,
                        earliestAt: Long?,
                        reason: Wait,
                    ) = Unit
                },
                mutex,
                { id, intent ->
                    val row = row(id)
                    val token = checkNotNull(row.runnerToken)
                    events += "stop:$id"
                    if (intent == StopIntent.Pause) {
                        assertEquals(
                            1,
                            dao.transition(
                                row.copy(state = State.PAUSED, lastStopReason = DownloadStopReasons.USER_PAUSE),
                                row.state,
                                token,
                            ),
                        )
                    } else {
                        assertEquals(1, dao.transition(row.copy(state = State.PAUSED), row.state, token))
                    }
                },
                files,
                media,
                { false },
                rootRegistration = registration,
            )

        suspend fun episode(block: EpisodeEntity.() -> EpisodeEntity = { this }): Long =
            db.episodeDao().insertStub(
                episodeEntity(podcastId = podcast) {
                    copy(enclosureUrl = "https://origin/audio.mp3", enclosureType = "audio/mpeg").block()
                },
            )

        suspend fun insert(
            id: Long,
            state: State = State.QUEUED,
        ): DownloadEntity {
            val row = downloadEntity(id, state).copy(runnerToken = if (state in ACTIVE_STATES) "owner:$id" else null)
            dao.insert(row)
            return row
        }

        suspend fun row(id: Long) = assertNotNull(dao.byEpisode(id))
    }

    private companion object {
        val ACTIVE_STATES = setOf(State.RESOLVING, State.DOWNLOADING, State.VERIFYING)
    }
}
