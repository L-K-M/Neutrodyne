// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.domain.SessionRevision
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class QueueProjectorSnapshotFenceTest {
    @Test fun aLateOlderGenerationDoesNotRegressTheInstalledWindow() =
        runTest {
            lateSnapshot(
                SnapshotAge.BEFORE_START,
            )
        }

    @Test fun aLateSameGenerationDoesNotRegressTheInstalledWindow() = runTest { lateSnapshot(SnapshotAge.DURING_START) }

    @Test fun metadataBurstsConflateWhileTheFreshQueueReadWaits() =
        runTest {
            val snapshots = MutableSharedFlow<VirtualQueue>(replay = 1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val media = FakePlaybackMediaLookup()
            lateinit var realMedia: PlaybackMediaLookup
            var trackReads = false
            var reads = 0
            val f =
                AdapterFixture(
                    this,
                    sessionStore = { real ->
                        object : PlaySessionStore by real {
                            override suspend fun virtualQueue(k: Int): VirtualQueue {
                                if (trackReads) {
                                    reads++
                                    entered.complete(Unit)
                                    release.await()
                                }
                                return real.virtualQueue(k)
                            }
                        }
                    },
                    mediaLookup = { real ->
                        realMedia = real
                        media
                    },
                    queueRepository = { real ->
                        object : QueueRepository by real {
                            override fun observeVirtualQueue(k: Int): Flow<VirtualQueue> = snapshots
                        }
                    },
                )
            try {
                val ids = f.seed(3)
                media.items.value = ids.associateWith { requireNotNull(realMedia.get(it)) }
                f.attach()
                f.start(ids)
                val snapshot = f.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
                trackReads = true
                snapshots.emit(snapshot)
                awaitAdapter { entered.isCompleted }
                assertEquals(1, reads)

                val titles = (0 until METADATA_BURST_SIZE).map { "Update $it" }
                for (title in titles) {
                    val row = requireNotNull(media.items.value[ids.first()])
                    media.items.value = media.items.value + (ids.first() to row.copy(title = title))
                    runCurrent()
                }
                assertEquals(1, reads, "Conflated signals must not fan out into pending IO jobs")
                release.complete(Unit)
                awaitAdapter {
                    f.player.currentMediaItem
                        ?.mediaMetadata
                        ?.title
                        ?.toString() == titles.last()
                }
                assertEquals(ids, f.player.ids())
                assertTrue(f.failures.isEmpty(), "${f.failures}")
            } finally {
                release.complete(Unit)
                f.close()
            }
        }

    private suspend fun TestScope.lateSnapshot(age: SnapshotAge) {
        val captured = CompletableDeferred<VirtualQueue>()
        val release = CompletableDeferred<Unit>()
        val repair = CompletableDeferred<Unit>()
        val subscribed = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<Unit>()
        val arrived = CompletableDeferred<Long>()
        val media = FakePlaybackMediaLookup()
        lateinit var realMedia: PlaybackMediaLookup
        var firstCollection = true
        var seededIds = emptySet<Long>()
        val f =
            AdapterFixture(
                this,
                sessionStore = { real ->
                    object : PlaySessionStore by real {
                        override suspend fun commitStart(
                            start: SessionStart,
                            now: Long,
                        ): Long {
                            val before = real.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
                            val generation = real.commitStart(start, now)
                            val during = real.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
                            captured.complete(if (age == SnapshotAge.BEFORE_START) before else during)
                            return generation
                        }

                        override suspend fun onTransition(
                            departure: Departure?,
                            arrivedEpisodeId: Long,
                            arrivedOrigin: QueueOrigin,
                            expected: SessionRevision,
                            now: Long,
                        ): Long? =
                            real.onTransition(departure, arrivedEpisodeId, arrivedOrigin, expected, now).also {
                                if (it != null) arrived.complete(arrivedEpisodeId)
                            }
                    }
                },
                mediaLookup = { real ->
                    realMedia = real
                    object : PlaybackMediaLookup by media {
                        override fun observe(episodeIds: Set<Long>) =
                            media.observe(episodeIds).onEach {
                                observed.complete(Unit)
                                if (episodeIds == seededIds) refreshed.complete(Unit)
                            }
                    }
                },
                queueRepository = { real ->
                    object : QueueRepository by real {
                        override fun observeVirtualQueue(k: Int): Flow<VirtualQueue> =
                            flow {
                                if (firstCollection) {
                                    firstCollection = false
                                    subscribed.complete(Unit)
                                    val snapshot = captured.await()
                                    release.await()
                                    emit(snapshot)
                                    repair.await()
                                    emitAll(real.observeVirtualQueue(k))
                                } else {
                                    emitAll(real.observeVirtualQueue(k))
                                }
                            }
                    }
                },
            )
        try {
            val ids = f.seed(3)
            media.items.value = ids.associateWith { requireNotNull(realMedia.get(it)) }
            f.attach()
            awaitAdapter { subscribed.isCompleted }
            f.start(ids)
            assertEquals(ids, f.player.ids())

            val markerTitle = "Projected late read"
            val current = requireNotNull(media.items.value[ids.first()])
            media.items.value = media.items.value + (ids.first() to current.copy(title = markerTitle))

            // A coherent committed read may finish delivery after the explicit install.
            seededIds = ids.toSet()
            release.complete(Unit)
            awaitAdapter { observed.isCompleted }
            if (age == SnapshotAge.DURING_START) {
                awaitAdapter {
                    f.player.currentMediaItem
                        ?.mediaMetadata
                        ?.title
                        ?.toString() == markerTitle
                }
            } else {
                runCurrent()
            }
            assertTrue(f.failures.isEmpty(), "${f.failures}")
            assertEquals(ids, f.player.ids(), "Age=$age; calls=${f.player.calls}")

            repair.complete(Unit)
            awaitAdapter { refreshed.isCompleted }
            f.player.seekToNextMediaItem()
            awaitAdapter { arrived.isCompleted }
            assertEquals(ids[1], arrived.await())
            assertEquals(
                ids[1],
                f.sessions
                    .virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
                    .current
                    ?.episodeId,
            )
        } finally {
            release.complete(Unit)
            repair.complete(Unit)
            f.close()
        }
    }

    private enum class SnapshotAge { BEFORE_START, DURING_START }

    private companion object {
        const val METADATA_BURST_SIZE = 100
    }
}
