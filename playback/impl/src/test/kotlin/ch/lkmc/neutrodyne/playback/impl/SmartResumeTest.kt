// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SmartResumeTest {
    private val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
    private val clock = TestClock()
    private var enabled = true
    private val seeks = mutableListOf<Long>()
    private val resume =
        SmartResume(player, clock, { enabled }) {
            seeks += it
            player.seekTo(it)
        }

    init {
        player.setMediaItem(
            MediaItem
                .Builder()
                .setMediaId("episode:1")
                .setUri("https://example.org/1")
                .build(),
            98_000,
        )
        shadowOf(Looper.getMainLooper()).idle()
        player.addListener(resume)
    }

    @After fun close() = player.release()

    private fun pause() {
        player.play()
        resume.userPaused()
        player.pause()
    }

    @Test fun exactlyFiveMinutesDoesNotRewindButOneMillisecondMoreDoesOnce() {
        pause()
        clock.elapsedMs += 300_000
        resume.resume()
        assertEquals(emptyList(), seeks)
        pause()
        clock.elapsedMs += 300_001
        resume.resume()
        resume.resume()
        assertEquals(listOf(93_000L), seeks)
    }

    @Test fun liveResumeIgnoresWallClockAndDoesNotRestartNearEnd() {
        pause()
        clock.nowMs -= 9_000_000
        clock.elapsedMs += 300_001
        resume.onMediaItemTransition(
            player.currentMediaItem,
            androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )
        resume.resume()
        assertEquals(listOf(93_000L), seeks)
    }

    @Test fun explicitTimestampOrChapterSeekCancelsPendingRewind() {
        pause()
        clock.elapsedMs += 300_001
        player.seekTo(754_000)
        shadowOf(Looper.getMainLooper()).idle()
        resume.resume()
        assertEquals(emptyList(), seeks)
        assertEquals(754_000, player.currentPosition)
    }

    @Test fun itemChangeClearsUserPause() {
        pause()
        clock.elapsedMs += 300_001
        player.setMediaItem(
            MediaItem
                .Builder()
                .setMediaId("episode:2")
                .setUri("https://example.org/2")
                .build(),
            60_000,
        )
        shadowOf(Looper.getMainLooper()).idle()
        resume.resume()
        assertEquals(emptyList(), seeks)
    }

    @Test fun disabledSettingTakesEffectLiveAndConsumesPause() {
        pause()
        clock.elapsedMs += 300_001
        enabled = false
        resume.resume()
        enabled = true
        resume.resume()
        assertEquals(emptyList(), seeks)
        pause()
        clock.elapsedMs += 300_001
        resume.resume()
        assertEquals(listOf(93_000L), seeks)
    }

    @Test fun coldProjectionAndArrivalsReadTheLiveSmartResumeSetting() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(3)
                for (id in ids) f.positions.save(id, 80_000, null, PositionSource.STREAM, f.clock.now(), 0) { true }
                f.fakeClock.advanceTime(300_001)
                f.smartResume = true
                f.sessions.commitStart(SessionStart(ids[0], null, ContextRetention.REPLACE, null, null), f.clock.now())
                f.queue.addLast(ids.drop(1))
                f.attach()
                awaitAdapter { f.player.episodeIdOrNull() == ids[0] && f.player.currentPosition == 75_000L }
                f.smartResume = false
                f.player.seekToNextMediaItem()
                awaitAdapter { f.player.episodeIdOrNull() == ids[1] && f.player.currentPosition == 80_000L }
                f.smartResume = true
                f.player.seekToNextMediaItem()
                awaitAdapter { f.player.episodeIdOrNull() == ids[2] && f.player.currentPosition == 75_000L }
                assertEquals(
                    80_000L,
                    f.db
                        .positionDao()
                        .byEpisode(ids[2])
                        ?.positionMs,
                )
                assertEquals(emptyList(), f.failures)
            } finally {
                f.close()
            }
        }

    @Test fun bufferingFocusAndRepeatedPauseDoNotCreateOrMoveUserPause() {
        player.play()
        player.pause()
        clock.elapsedMs += 300_001
        resume.resume()
        assertEquals(emptyList(), seeks)
        pause()
        clock.elapsedMs += 300_000
        resume.userPaused()
        clock.elapsedMs++
        resume.resume()
        assertEquals(listOf(93_000L), seeks)
    }
}
