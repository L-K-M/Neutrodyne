// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val REPLAY_MS = 60_000L

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SleepTimerTest {
    @Test fun fadeCountsElapsedAtTripleSpeedFreezesOnBufferingAndRestoresOnPause() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player = RecordingPlayer(exo).also { it.advancing = true }
            val events = mutableListOf<PlaybackEvent>()
            var state: SleepTimerState = SleepTimerState.Off
            val timer =
                SleepTimer(
                    player,
                    EndOfItemPauseArbiter {},
                    TestClock.from(testScheduler),
                    backgroundScope,
                    { state = it },
                    events::add,
                )
            try {
                player.playbackParameters = androidx.media3.common.PlaybackParameters(3f)
                timer.attach()
                timer.set(SleepTimerMode.Minutes(1))
                advanceTimeBy(55_000)
                runCurrent()
                assertEquals(5_000, assertIs<SleepTimerState.Running>(state).remainingMs)
                assertEquals(0.5f, player.volume)
                player.advancing = false
                timer.onIsPlayingChanged(false)
                advanceTimeBy(30_000)
                runCurrent()
                assertEquals(0.5f, player.volume)
                assertEquals(5_000, assertIs<SleepTimerState.Running>(state).remainingMs)
                timer.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                assertEquals(1f, player.volume)
                player.advancing = true
                timer.onIsPlayingChanged(true)
                assertEquals(0.5f, player.volume)
                timer.extend(5)
                assertEquals(1f, player.volume)
                assertEquals(305_000, assertIs<SleepTimerState.Running>(state).remainingMs)
                timer.set(SleepTimerMode.Minutes(1))
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(SleepTimerState.Off, state)
                assertEquals(1f, player.volume)
                assertEquals(listOf<PlaybackEvent>(PlaybackEvent.SleepTimerFired), events)
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(1, events.size)
            } finally {
                timer.detach()
                exo.release()
            }
        }

    @Test fun endOfEpisodeBeforeTheSavedItemProjectsArmsWhenItLoads() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player = RecordingPlayer(exo)
            var pausedAtBoundary = false
            val arbiter = EndOfItemPauseArbiter { pausedAtBoundary = it }
            var state: SleepTimerState = SleepTimerState.Off
            val timer =
                SleepTimer(player, arbiter, TestClock.from(testScheduler), backgroundScope, { state = it }, {})
            try {
                timer.attach()
                timer.set(SleepTimerMode.EndOfEpisode)
                assertEquals(SleepTimerState.Off, state)
                assertFalse(pausedAtBoundary)

                player.setMediaItem(
                    MediaItem
                        .Builder()
                        .setMediaId("episode:1")
                        .setUri("https://example.org/1")
                        .build(),
                )
                timer.onMediaItemTransition(
                    player.currentMediaItem,
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                )
                assertEquals(SleepTimerState.EndOfEpisode(1), state)
                assertTrue(pausedAtBoundary)
            } finally {
                timer.detach()
                exo.release()
            }
        }

    @Test fun replacementOffAndDestructionRestoreAndKeepMeteredReason() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player = RecordingPlayer(exo).also { it.advancing = true }
            player.setMediaItem(
                MediaItem
                    .Builder()
                    .setMediaId("episode:1")
                    .setUri("https://example.org/1")
                    .build(),
            )
            var pausedAtBoundary = false
            val arbiter = EndOfItemPauseArbiter { pausedAtBoundary = it }
            var state: SleepTimerState = SleepTimerState.Off
            val events = mutableListOf<PlaybackEvent>()
            val timer =
                SleepTimer(player, arbiter, TestClock.from(testScheduler), backgroundScope, { state = it }, events::add)
            try {
                timer.attach()
                timer.set(SleepTimerMode.Minutes(1))
                advanceTimeBy(55_000)
                runCurrent()
                timer.set(SleepTimerMode.EndOfEpisode)
                assertEquals(1f, player.volume)
                assertTrue(pausedAtBoundary)
                arbiter.blockBeforeNext = true
                timer.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                assertEquals(SleepTimerState.Off, state)
                assertFalse(arbiter.sleepTimerArmed)
                assertTrue(pausedAtBoundary)
                assertEquals(listOf<PlaybackEvent>(PlaybackEvent.SleepTimerFired), events)
                timer.set(SleepTimerMode.Minutes(1))
                timer.onIsPlayingChanged(true)
                advanceTimeBy(55_000)
                runCurrent()
                timer.detach()
                assertEquals(SleepTimerState.Off, state)
                assertEquals(1f, player.volume)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, events.size)
            } finally {
                timer.detach()
                exo.release()
            }
        }

    @Test fun seekingARetainedCompletedItemStartsAReplayWithDurableProgress() =
        runTest {
            val f = AdapterFixture(this)
            f.sources.keepPositionOnMetadataUpdate = true
            val timer = SleepTimer(f.player, EndOfItemPauseArbiter {}, f.clock, f.playerScope, {}, {})
            try {
                val ids = f.seed(2)
                val id = ids.first()
                f.attach()
                f.start(ids, AdapterFixture.DURATION_MS - 1)
                awaitAdapter { f.player.ids() == ids }
                timer.attach()
                timer.set(SleepTimerMode.EndOfEpisode)
                f.fakeClock.advanceTime(1)
                f.projector.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                timer.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                awaitAdapter { f.events.size == 1 }

                // A seek alone keeps the completed state until audio actually plays.
                f.fakeClock.advanceTime(1)
                f.player.seekTo(REPLAY_MS)
                awaitAdapter { f.player.currentPosition == REPLAY_MS }
                assertNotNull(
                    f.db
                        .episodeStateDao()
                        .byEpisode(id)
                        ?.playedAt,
                )

                f.player.advancing = true
                f.tracker.onIsPlayingChanged(true)
                awaitAdapter("Replay did not clear played") {
                    runBlocking {
                        f.db
                            .episodeStateDao()
                            .byEpisode(id)
                            ?.playedAt == null
                    }
                }

                f.player.advancing = false
                f.fakeClock.advanceTime(1)
                f.tracker.onIsPlayingChanged(false)
                awaitAdapter("Replay progress was not saved", {
                    val saved = runBlocking { f.db.positionDao().byEpisode(id) }
                    val state = runBlocking { f.db.episodeStateDao().byEpisode(id) }
                    "saved=$saved state=$state player=${f.player.currentPosition} " +
                        "playWhenReady=${f.player.playWhenReady} failures=${f.failures} calls=${f.player.calls}"
                }) {
                    runBlocking {
                        f.db
                            .positionDao()
                            .byEpisode(id)
                            ?.positionMs == REPLAY_MS
                    }
                }
                assertEquals(id, f.player.episodeIdOrNull(), "Replay must stay on the retained item")
                assertEquals(
                    id,
                    f.sessions
                        .virtualQueue(0)
                        .current
                        ?.episodeId,
                )
                assertTrue(f.failures.isEmpty(), f.failures.toString())
            } finally {
                timer.detach()
                f.close()
            }
        }

    @Test fun eoeCompletionRunsPlayedChainOnceWithoutAdvancingEvenOnFinalItem() =
        runTest {
            for (count in listOf(1, 2)) {
                val f = AdapterFixture(this)
                val events = mutableListOf<PlaybackEvent>()
                var armed = false
                val arbiter = EndOfItemPauseArbiter { armed = it }
                val timer = SleepTimer(f.player, arbiter, f.clock, f.playerScope, {}, events::add)
                try {
                    val ids = f.seed(count)
                    f.positions.save(ids.first(), 120_000, null, PositionSource.STREAM, f.clock.now(), 0) { true }
                    f.attach()
                    f.start(ids, AdapterFixture.DURATION_MS - 1)
                    awaitAdapter { f.player.ids() == ids }
                    timer.attach()
                    timer.set(SleepTimerMode.EndOfEpisode)
                    arbiter.blockBeforeNext = true
                    f.fakeClock.advanceTime(1)
                    f.projector.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                    timer.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                    f.projector.onPlaybackStateChanged(Player.STATE_ENDED)
                    timer.onPlaybackStateChanged(Player.STATE_ENDED)
                    awaitAdapter { f.events.size == 1 }
                    val session = f.sessions.virtualQueue(20)
                    val state = f.db.episodeStateDao().byEpisode(ids.first())
                    val position = f.db.positionDao().byEpisode(ids.first())
                    assertEquals(ids.first(), session.current?.episodeId)
                    assertEquals(ids.drop(1), session.upNext.map { it.episodeId })
                    assertEquals(ids.first(), f.player.episodeIdOrNull())
                    assertNotNull(state?.playedAt)
                    assertEquals(0, position?.positionMs)
                    assertEquals(listOf<PlaybackEvent>(PlaybackEvent.MarkedPlayed(ids.first())), f.events)
                    assertEquals(listOf<PlaybackEvent>(PlaybackEvent.SleepTimerFired), events)
                    assertTrue(armed)
                    assertFalse(arbiter.sleepTimerArmed)
                    assertTrue(f.failures.isEmpty(), f.failures.toString())
                } finally {
                    timer.detach()
                    f.close()
                }
            }
        }
}
