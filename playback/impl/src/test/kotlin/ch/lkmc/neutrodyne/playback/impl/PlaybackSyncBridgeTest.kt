// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncActive
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.testing.FakePositionStore
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.core.RemotePlayedGuard
import ch.lkmc.neutrodyne.playback.core.StartRequest
import dev.zacsweers.metro.Provider
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
 * `Paused`/`Stopped`/`Transition` boundaries only after their save or transaction committed, and
 * freezes a remotely marked-played item (rule 3) — never stopping, seeking or skipping it.
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

    @Test
    fun activeTracksTheLoadedItemAndItsPlayState() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                assertEquals(PlaybackSyncActive(false), f.sync.active.value, "nothing published yet")
                f.attach()
                f.start(ids, 50_000)
                awaitAdapter { f.sync.active.value.episodeId == ids[0] }
                assertFalse(f.sync.active.value.isPlaying, "a fresh window loads paused")

                f.player.advancing = true
                f.tracker.onIsPlayingChanged(true)
                f.publishActive()
                assertEquals(PlaybackSyncActive(true, ids[0]), f.sync.active.value)

                f.player.advancing = false
                f.tracker.onIsPlayingChanged(false)
                f.publishActive()
                assertEquals(PlaybackSyncActive(false, ids[0]), f.sync.active.value)
            } finally {
                f.close()
            }
        }

    @Test
    fun pausedIsEmittedOnlyAfterThePauseSaveCommitted() =
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

    @Test
    fun transitionedIsEmittedOnlyAfterTheTransitionTransactionCommitted() =
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
                f.player.advancing = true
                f.tracker.onIsPlayingChanged(true)
                f.player.seekToNextMediaItem()
                awaitAdapter { f.player.ids() == ids.drop(1) }

                val event = awaitEvent(received) { it is PlaybackSyncEvent.Transition && it.episodeId == ids[1] }
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

    @Test
    fun teardownClearsActiveBeforeStopped() =
        runTest {
            val f = AdapterFixture(this)
            val id = f.seed(1).single()
            val received = f.boundaries()
            f.attach()
            f.start(listOf(id), 50_000)
            awaitAdapter { f.sync.active.value.episodeId == id }

            f.stopActivePublisher()
            f.projector.detach()
            // 06 Sync interplay: a release reading the port at the boundary must already see
            // `active` cleared, so capture it at the moment the bridge emits Stopped.
            var activeWhenStopped: PlaybackSyncActive? = null
            val teardown =
                f.tracker.detach(f.tracker.flush(PositionFlushReason.DESTROY)) {
                    f.sync.stopped()
                    activeWhenStopped = f.sync.active.value
                }
            f.sync.detachAfter(teardown)
            f.applier.detach().join()
            teardown?.join()
            runCurrent()
            awaitAdapter { f.sync.active.value.episodeId == null }
            assertEquals(PlaybackSyncActive(false), activeWhenStopped)
            assertEquals(PlaybackSyncEvent.Stopped, awaitEvent(received) { it is PlaybackSyncEvent.Stopped })
            f.exo.release()
            f.serviceJob.cancel()
            f.cache.cache().release()
            f.db.close()
        }

    // --- rule 3: the remote freeze --------------------------------------------------------------

    @Test
    fun aRemoteMarkPlayedFreezesSavesAndTheAdvanceRule() =
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
                f.sync.onRemoteMarkedPlayed(ids[0], "Pixel")
                awaitAdapter { f.remoteGuard.isFrozen(ids[0]) }
                val playedAt = f.clock.now()
                f.db.withWriteTransaction {
                    f.db.episodeStateDao().ensure(EpisodeStateEntity(ids[0], updatedAt = playedAt))
                    f.db.episodeStateDao().markPlayed(listOf(ids[0]), playedAt)
                }

                // Nothing stops, seeks or skips: the marked item keeps playing.
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

    @Test
    fun theFreezeLiftsWhenTheEpisodeBecomesUnplayedHereAgain() =
        runTest {
            val marks = MarksProbe()
            val f = AdapterFixture(this, positionStore = marks::wrap)
            try {
                val id = f.seed(1).single()
                f.attach()
                f.start(listOf(id), 50_000)
                awaitAdapter { f.player.episodeIdOrNull() == id }

                f.sync.onRemoteMarkedPlayed(id, "Pixel")
                val playedAt = f.clock.now()
                f.db.withWriteTransaction {
                    // As the applier projects it: the played mark lands with its position reset.
                    f.db.episodeStateDao().ensure(EpisodeStateEntity(id, updatedAt = playedAt))
                    f.db.episodeStateDao().markPlayed(listOf(id), playedAt)
                }
                awaitAdapter { f.remoteGuard.isFrozen(id) }

                // The lift needs the watch to observe the played mark once: an unplayed commit
                // landing before the Room read is indistinguishable from the mark never having
                // committed, so the write must wait for the observation.
                awaitAdapter { marks.sawEffectivelyPlayed(id) }

                // The user's "Mark unplayed": the watch lifts the freeze, the next save writes.
                f.db.withWriteTransaction { f.db.episodeStateDao().markUnplayed(listOf(id), f.clock.now()) }
                awaitAdapter { !f.remoteGuard.isFrozen(id) }

                f.player.seekTo(60_000)
                f.tracker.flush(PositionFlushReason.PAUSE)
                f.awaitSaved(id, 60_000)
            } finally {
                f.close()
            }
        }

    @Test
    fun aRolledBackMarkReleasesItsFreezeContribution() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                f.attach()
                f.start(listOf(id), 50_000)
                awaitAdapter { f.player.episodeIdOrNull() == id }

                f.sync.onRemoteMarkedPlayed(id, "Pixel")
                assertTrue(f.remoteGuard.isFrozen(id))

                f.sync.onRemoteMarkReverted(id)
                assertFalse(f.remoteGuard.isFrozen(id), "the rolled-back mark's contribution lifts")

                f.player.seekTo(60_000)
                f.tracker.flush(PositionFlushReason.PAUSE)
                f.awaitSaved(id, 60_000)
            } finally {
                f.close()
            }
        }

    @Test
    fun rollingBackARepeatedMarkKeepsWatchingTheCommittedMark() =
        runTest {
            val positions = FakePositionStore()
            positions.positions.value = mapOf(1L to StoredPosition(1, 30_000, 100, 100, null))
            val guard = RemotePlayedGuard()
            val bridge = PlaybackSyncBridge(guard, Provider { positions }, backgroundScope)
            bridge.updateActive(1, isPlaying = true)
            bridge.onRemoteMarkedPlayed(1, "Pixel")
            runCurrent()
            bridge.onRemoteMarkedPlayed(1, "Laptop")
            runCurrent()
            bridge.onRemoteMarkReverted(1)
            runCurrent()
            assertTrue(guard.isFrozen(1), "the first mark committed")

            positions.positions.value = mapOf(1L to StoredPosition(1, 30_000, 101, null, null))
            runCurrent()
            assertFalse(guard.isFrozen(1), "unplayed must lift the surviving mark's freeze")
        }

    @Test
    fun aPlayedMarkNeverObservedByTheWatchKeepsTheFreeze() =
        runTest {
            val positions = FakePositionStore()
            positions.positions.value = mapOf(1L to StoredPosition(1, 30_000, 100, 100, null))
            val guard = RemotePlayedGuard()
            val bridge = PlaybackSyncBridge(guard, Provider { positions }, backgroundScope)
            bridge.updateActive(1, isPlaying = true)
            bridge.onRemoteMarkedPlayed(1, "Pixel")
            // The unplayed write lands before the watch's first read: the observed marks go
            // straight to unplayed, so nothing can arm the lift. The freeze is kept rather
            // than released on a mark state it never saw played.
            positions.positions.value = mapOf(1L to StoredPosition(1, 30_000, 101, null, null))
            runCurrent()
            assertTrue(guard.isFrozen(1), "an unobserved played mark must not arm the lift")
        }

    @Test
    fun leavingTheCurrentItemCancelsItsMarkObserver() =
        runTest {
            val positions = FakePositionStore()
            positions.positions.value = mapOf(1L to StoredPosition(1, 30_000, 100, 100, null))
            val guard = RemotePlayedGuard()
            val bridge = PlaybackSyncBridge(guard, Provider { positions }, backgroundScope)
            bridge.updateActive(1, isPlaying = true)
            bridge.onRemoteMarkedPlayed(1, "Pixel")
            runCurrent()
            assertEquals(1, positions.positions.subscriptionCount.value)

            bridge.stopped()
            runCurrent()
            assertFalse(guard.isFrozen(1))
            assertEquals(0, positions.positions.subscriptionCount.value, "old items must leave no Room observer")
        }

    // --- STOPPED from the explicit dismiss and stop paths ---------------------------------------

    @Test
    fun stopEmitsStoppedAfterTheFlush() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            val received = Channel<PlaybackSyncEvent>(Channel.UNLIMITED)
            h.scope.launch { h.sync.events.collect { received.trySend(it) } }
            try {
                pumpUntil { player.mediaItemCount == 1 }
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

    @Test
    fun dismissEmitsStoppedAfterTheClearTransaction() {
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
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }
}
