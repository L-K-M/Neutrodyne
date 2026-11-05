// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.domain.ActivePlayback
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 06 Sync interplay: `PlaybackSyncBridge` publishes `active` from the loaded item, emits the
 * `Paused`/`Stopped`/`Transitioned` boundaries only after their save or transaction committed,
 * and freezes a remotely marked-played item (rule 3) whose pickup (rule 4) seeks a paused player
 * to a diverging stored position before it resumes.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaybackSyncBridgeTest {
    private fun AdapterFixture.boundaries(): Channel<PlaybackSyncEvent> {
        val received = Channel<PlaybackSyncEvent>(Channel.UNLIMITED)
        scope.backgroundScope.launch { sync.events.collect { received.trySend(it) } }
        return received
    }

    private suspend fun awaitEvent(
        received: Channel<PlaybackSyncEvent>,
        match: (PlaybackSyncEvent) -> Boolean,
    ): PlaybackSyncEvent {
        while (true) {
            val event = received.receive()
            if (match(event)) return event
        }
    }

    private fun awaitEventBlocking(
        received: Channel<PlaybackSyncEvent>,
        match: (PlaybackSyncEvent) -> Boolean,
    ): PlaybackSyncEvent {
        while (true) {
            val event = runBlocking { received.receive() }
            if (match(event)) return event
        }
    }

    // --- active and the boundary events (the real adapter over a real player) -------------------

    @Test fun activeTracksTheLoadedItemAndItsPlayState() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                assertNull(f.sync.active.value, "no service runtime published anything yet")
                f.attach()
                f.start(ids, 50_000)
                awaitAdapter {
                    f.sync.active.value
                        ?.episodeId == ids[0]
                }
                val since =
                    f.sync.active.value!!
                        .sinceMs
                assertEquals(f.tracker.currentSinceMs, since)
                assertFalse(
                    f.sync.active.value!!
                        .isPlaying,
                    "a fresh window loads paused",
                )

                f.player.advancing = true
                f.tracker.onIsPlayingChanged(true)
                f.publishActive()
                assertEquals(ActivePlayback(ids[0], true, since), f.sync.active.value)

                f.player.advancing = false
                f.tracker.onIsPlayingChanged(false)
                f.publishActive()
                assertEquals(ActivePlayback(ids[0], false, since), f.sync.active.value)
            } finally {
                f.close()
            }
        }

    @Test fun pausedIsEmittedOnlyAfterThePauseSaveCommitted() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                val received = f.boundaries()
                f.attach()
                f.start(listOf(id), 50_000)
                awaitAdapter { f.player.episodeIdOrNull() == id }

                f.player.advancing = true
                f.tracker.onIsPlayingChanged(true)
                f.player.seekTo(60_000)
                f.player.advancing = false
                f.tracker.onIsPlayingChanged(false)

                assertEquals(PlaybackSyncEvent.Paused, awaitEvent(received) { it is PlaybackSyncEvent.Paused })
                // The event followed the committed save: the row already carries the pause position.
                f.awaitSaved(id, 60_000)
            } finally {
                f.close()
            }
        }

    @Test fun transitionedIsEmittedOnlyAfterTheTransitionTransactionCommitted() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                val received = f.boundaries()
                f.attach()
                f.start(ids, 50_000)
                awaitAdapter { f.player.ids() == ids }

                f.player.seekTo(70_000)
                runCurrent()
                f.awaitSaved(ids[0], 70_000)
                f.player.seekToNextMediaItem()
                awaitAdapter { f.player.ids() == ids.drop(1) }

                // The start itself reported its item change; the seek's arrival is the next one.
                val event =
                    awaitEvent(received) {
                        it is PlaybackSyncEvent.Transition && it.episodeId == ids[1]
                    }
                assertEquals(PlaybackSyncEvent.Transition(ids[1]), event)
                // The event followed the committed transition: the arrival is already current and
                // the outgoing position was persisted by the transaction.
                assertEquals(
                    ids[1],
                    f.sessions
                        .virtualQueue(20)
                        .current!!
                        .episodeId,
                )
                assertEquals(
                    70_000L,
                    f.db
                        .positionDao()
                        .byEpisode(ids[0])!!
                        .positionMs,
                )
            } finally {
                f.close()
            }
        }

    @Test fun teardownEmitsStoppedAndThenClearsActive() =
        runTest {
            val f = AdapterFixture(this)
            val id = f.seed(1).single()
            val received = f.boundaries()
            f.attach()
            f.start(listOf(id), 50_000)
            awaitAdapter {
                f.sync.active.value
                    ?.episodeId == id
            }

            f.stopActivePublisher()
            f.projector.detach()
            val teardown = f.tracker.detach(f.tracker.flush(PositionFlushReason.DESTROY)) { f.sync.stopped() }
            f.sync.detachAfter(teardown)
            f.applier.detach().join()
            teardown?.join()
            runCurrent()
            awaitAdapter { f.sync.active.value == null }
            assertEquals(PlaybackSyncEvent.Stopped, awaitEvent(received) { it is PlaybackSyncEvent.Stopped })
            f.exo.release()
            f.serviceJob.cancel()
            f.cache.cache().release()
            f.db.close()
        }

    // --- rule 3: the remote freeze --------------------------------------------------------------

    @Test fun aRemoteMarkPlayedFreezesSavesAndTheAdvanceRule() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.attach()
                f.start(ids, 50_000)
                awaitAdapter { f.player.ids() == ids }

                // One unfrozen pause saves the start position; the item then plays again.
                f.player.seekTo(50_000)
                f.tracker.flush(PositionFlushReason.PAUSE)
                f.awaitSaved(ids[0], 50_000)
                f.player.advancing = true
                f.tracker.onIsPlayingChanged(true)

                // As 10's applier does: freeze before the played state is projected.
                runBlocking { f.sync.onRemoteMarkedPlayed(ids[0], "Pixel") }
                awaitAdapter { f.remoteGuard.isFrozen(ids[0]) }
                val playedAt = f.clock.now()
                f.db.withWriteTransaction {
                    f.db.episodeStateDao().ensure(EpisodeStateEntity(ids[0], updatedAt = playedAt))
                    f.db
                        .episodeStateDao()
                        .upsert(EpisodeStateEntity(ids[0], playedAt = playedAt, updatedAt = playedAt))
                }

                // Nothing stops, seeks or skips: the marked item keeps playing (MS3 AC3 shape).
                awaitAdapter { f.player.episodeIdOrNull() == ids[0] }
                f.player.seekTo(60_000)
                f.player.advancing = false
                f.tracker.onIsPlayingChanged(false)
                runCurrent()
                assertEquals(
                    50_000L,
                    f.db
                        .positionDao()
                        .byEpisode(ids[0])!!
                        .positionMs,
                    "a frozen item writes no position",
                )

                // ...and the departure at the transition writes neither a position nor a mark.
                f.player.seekToNextMediaItem()
                awaitAdapter { f.player.ids() == ids.drop(1) }
                assertEquals(
                    50_000L,
                    f.db
                        .positionDao()
                        .byEpisode(ids[0])!!
                        .positionMs,
                )
                assertEquals(
                    playedAt,
                    f.db
                        .episodeStateDao()
                        .byEpisode(ids[0])
                        ?.playedAt,
                )
                assertFalse(f.remoteGuard.isFrozen(ids[0]), "the freeze lifted with the item change")
            } finally {
                f.close()
            }
        }

    @Test fun theFreezeLiftsWhenTheEpisodeBecomesUnplayedHereAgain() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                f.attach()
                f.start(listOf(id), 50_000)
                awaitAdapter { f.player.episodeIdOrNull() == id }

                runBlocking { f.sync.onRemoteMarkedPlayed(id, "Pixel") }
                val playedAt = f.clock.now()
                f.db.withWriteTransaction {
                    // As the applier projects it: the played mark lands with its position reset.
                    f.db.episodeStateDao().ensure(EpisodeStateEntity(id, updatedAt = playedAt))
                    f.db.episodeStateDao().upsert(EpisodeStateEntity(id, playedAt = playedAt, updatedAt = playedAt))
                    f.db
                        .positionDao()
                        .upsert(
                            ch.lkmc.neutrodyne.core.database.EpisodePositionEntity(
                                episodeId = id,
                                positionMs = 0,
                                positionSource = ch.lkmc.neutrodyne.core.model.PositionSource.STREAM,
                                updatedAt = playedAt,
                            ),
                        )
                }
                awaitAdapter { f.sync.seenPlayed.contains(id) }

                // The user's "Mark unplayed": the watch lifts the freeze, the next save writes.
                f.sql("UPDATE episode_state SET playedAt = NULL WHERE episodeId = $id")
                awaitAdapter { !f.remoteGuard.isFrozen(id) }

                f.player.seekTo(60_000)
                f.tracker.flush(PositionFlushReason.PAUSE)
                f.awaitSaved(id, 60_000)
            } finally {
                f.close()
            }
        }

    // --- rule 4: the paused-item pickup (the service bridge's resume paths) ---------------------

    @Test fun aPausedItemPicksUpASyncedPositionAtResume() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            try {
                pumpUntil { player.mediaItemCount == 1 }
                assertEquals(12_000L, player.currentPosition)

                // Sync (or a restore) wrote a newer position while the item sat paused here.
                h.positions.positions.value =
                    mapOf(1L to StoredPosition(1, 900_000, h.clock.now() + 1, null))

                assertEquals(PlayResult.Started, h.run { bridge.start(StartRequest.Resume, StartOrigin.UI) })
                assertEquals(900_000L, player.currentPosition, "the pickup seek precedes the resume")
                assertTrue(h.store.calls.isEmpty(), "the pickup seek writes nothing")
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    @Test fun beforePlayPicksUpASyncedPositionForMediaKeyResumes() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            try {
                pumpUntil { player.mediaItemCount == 1 }
                h.positions.positions.value =
                    mapOf(1L to StoredPosition(1, 700_000, h.clock.now() + 1, null))

                assertTrue(h.run { bridge.beforePlay(StartOrigin.EXTERNAL) })
                assertEquals(700_000L, player.currentPosition)
                assertTrue(h.store.calls.isEmpty())
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    @Test fun aPickupOfAStoredResetSeeksZeroAndWritesNothing() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            try {
                pumpUntil { player.mediaItemCount == 1 }
                // A reset that arrived through sync: stored 0 is taken up, never written back.
                h.positions.positions.value =
                    mapOf(1L to StoredPosition(1, 0, h.clock.now() + 1, null))

                assertEquals(PlayResult.Started, h.run { bridge.start(StartRequest.Resume, StartOrigin.UI) })
                assertEquals(0L, player.currentPosition)
                assertTrue(h.store.calls.isEmpty(), "no reset, no save: the pickup writes nothing")
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    @Test fun aDivergenceOfAtMostOneSecondIsNotPickedUp() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            try {
                pumpUntil { player.mediaItemCount == 1 }
                h.positions.positions.value =
                    mapOf(1L to StoredPosition(1, 12_500, h.clock.now() + 1, null))

                assertEquals(PlayResult.Started, h.run { bridge.start(StartRequest.Resume, StartOrigin.UI) })
                assertEquals(12_000L, player.currentPosition, "the in-memory position stays")
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    // --- STOPPED from the explicit dismiss and stop paths ---------------------------------------

    @Test fun stopEmitsStoppedAfterTheFlush() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            h.lookup.items.value += 2L to media(id = 2)
            h.publish(h.store.queue.copy(upNext = listOf(QueueItem(2, QueueOrigin.UP_NEXT))))
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            val received = Channel<PlaybackSyncEvent>(Channel.UNLIMITED)
            h.scope.launch { h.sync.events.collect { received.trySend(it) } }
            try {
                pumpUntil { player.mediaItemCount == 2 }
                h.run { bridge.stop() }
                assertEquals(
                    PlaybackSyncEvent.Stopped,
                    awaitEventBlocking(received) { it is PlaybackSyncEvent.Stopped },
                )
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    @Test fun dismissEmitsStoppedAfterTheClearTransaction() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            h.lookup.items.value += 2L to media(id = 2)
            h.publish(h.store.queue.copy(upNext = listOf(QueueItem(2, QueueOrigin.UP_NEXT))))
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            val received = Channel<PlaybackSyncEvent>(Channel.UNLIMITED)
            h.scope.launch { h.sync.events.collect { received.trySend(it) } }
            try {
                pumpUntil { player.mediaItemCount == 2 }
                h.run { bridge.dismiss() }
                assertEquals(
                    PlaybackSyncEvent.Stopped,
                    awaitEventBlocking(received) { it is PlaybackSyncEvent.Stopped },
                )
                assertNull(h.store.queue.current, "the dismiss cleared the durable session")
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }
}
