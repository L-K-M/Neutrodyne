// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SessionPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
    private var accepted = true
    private var previous = 0
    private val calls = mutableListOf<String>()
    private val player =
        SessionPlayer(exo, scope, {
            calls += "gate"
            accepted
        }, { previous++ }, {
            calls += "flush"
            exo.stop()
            calls += "stop"
        })

    @After fun close() {
        player.release()
        scope.cancel()
    }

    private fun load() {
        exo.setMediaItems(
            listOf(
                MediaItem.fromUri("https://synthetic.test/1.mp3"),
                MediaItem.fromUri("https://synthetic.test/2.mp3"),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun advertisesBothEpisodeAndHardwareTransportsWithoutRepeat() {
        load()
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE))
    }

    @Test fun bothNextCommandsSelectEpisodesAndPreviousUsesHistory() {
        load()
        player.seekToNext()
        assertEquals(1, exo.currentMediaItemIndex)
        player.seekToPreviousMediaItem()
        player.seekToPrevious()
        assertEquals(2, previous)
    }

    @Test fun neverResumesWhenGateRefuses() {
        load()
        accepted = false
        player.play()
        assertFalse(exo.playWhenReady)
        accepted = true
        player.play()
        assertTrue(exo.playWhenReady)
        player.pause()
        assertFalse(exo.playWhenReady)
    }

    @Test fun stopFlushesBeforeStoppingAndReleaseReleasesExo() {
        load()
        player.stop()
        assertEquals(listOf("flush", "stop"), calls)
        assertEquals(Player.STATE_IDLE, exo.playbackState)
        player.release()
        assertTrue(exo.isReleased)
    }

    @Test fun negativeUserSeekClampsToZero() {
        load()
        player.seekTo(-5)
        assertEquals(0L, exo.currentPosition)
    }
}
