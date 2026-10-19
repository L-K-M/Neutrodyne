// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.legacy.MediaSessionCompat
import androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo
import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
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
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SessionPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
    private var accepted = true
    private var hardware = HardwareButtons.EPISODE
    private var previous = 0
    private var currentSession: MediaSession? = null
    private val calls = mutableListOf<String>()
    private val player =
        SessionPlayer(exo, scope, {
            calls += "gate"
            accepted
        }, { previous++ }, {
            calls += "flush"
            exo.stop()
            calls += "stop"
        }, hardwareButtons = { hardware }, transportProtocol = { currentSession.transportProtocol() })

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

    @Test fun sessionForwarderMovesBehindAdaptersAndReleasesPlayer() {
        val listeners = mutableListOf<Player.Listener>()
        val ordered =
            object : androidx.media3.common.ForwardingPlayer(exo) {
                override fun addListener(listener: Player.Listener) {
                    listeners += listener
                    super.addListener(listener)
                }

                override fun removeListener(listener: Player.Listener) {
                    listeners -= listener
                    super.removeListener(listener)
                }
            }
        val wrapper = SessionPlayer(ordered, scope, { true }, {}, {})
        assertEquals(1, listeners.size)
        val tracker = object : Player.Listener {}
        val projector = object : Player.Listener {}
        val applier = object : Player.Listener {}
        ordered.addListener(tracker)
        ordered.addListener(projector)
        ordered.addListener(applier)
        wrapper.orderAfterPlaybackAdapters()
        assertEquals(4, listeners.size)
        assertEquals(listOf(tracker, projector, applier), listeners.take(3))
        wrapper.release()
        assertTrue(exo.isReleased)
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

    @Test fun controllersCannotReenableVideoOrTextSelection() {
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS))
        player.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        assertTrue(
            exo.trackSelectionParameters.disabledTrackTypes.containsAll(listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT)),
        )
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

    @Test fun liveSkipMappingWorksWithOneItemAndExplicitEpisodeCommandsStaySeparate() {
        exo.setMediaItem(MediaItem.fromUri("https://synthetic.test/1.mp3"), 60_000)
        shadowOf(Looper.getMainLooper()).idle()
        hardware = HardwareButtons.SKIP
        exo.setSeekBackIncrementMs(20_000)
        exo.setSeekForwardIncrementMs(45_000)
        player.seekToNext()
        assertEquals(105_000L, exo.currentPosition)
        player.seekToPrevious()
        assertEquals(85_000L, exo.currentPosition)
        player.seekToNextMediaItem()
        assertEquals(85_000L, exo.currentPosition)
        player.seekToPreviousMediaItem()
        assertEquals(1, previous)
        hardware = HardwareButtons.EPISODE
        player.seekToNext()
        assertEquals(85_000L, exo.currentPosition)
        player.seekToPrevious()
        assertEquals(2, previous)
    }

    @Test
    @Config(sdk = [26])
    fun platformKeyIntentsAndLegacySkipControlsUseTheLiveMapping() {
        load()
        val app = RuntimeEnvironment.getApplication()
        val session = MediaSession.Builder(app, player).build().also { currentSession = it }
        val notification =
            MediaController
                .Builder(app, session.token)
                .setConnectionHints(
                    Bundle().apply {
                        putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true)
                    },
                ).buildAsync()
        try {
            pumpUntil { notification.isDone }
            assertTrue(notification.get().isConnected)
            assertNotNull(session.mediaNotificationControllerInfo)
            // Robolectric's platform session binder is a stub. Enter the real Media3 legacy
            // callbacks with the caller identity normally supplied by that binder.
            val impl = ReflectionHelpers.getField<Any>(session, "impl")
            val legacy = ReflectionHelpers.getField<MediaSessionCompat.Callback>(impl, "sessionLegacyStub")
            val compat = ReflectionHelpers.getField<MediaSessionCompat>(legacy, "sessionCompat")
            val compatImpl = ReflectionHelpers.getField<Any>(compat, "impl")
            ReflectionHelpers.setField(
                compatImpl,
                "remoteUserInfo",
                RemoteUserInfo(app.packageName, Process.myPid(), Process.myUid()),
            )

            fun key(code: Int) =
                legacy.onMediaButtonEvent(
                    Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                        putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
                    },
                )
            hardware = HardwareButtons.SKIP
            exo.setSeekBackIncrementMs(PlaybackSettingKeys.SKIP_BACK_MS.default)
            exo.setSeekForwardIncrementMs(PlaybackSettingKeys.SKIP_FORWARD_MS.default)
            exo.seekTo(60_000)
            assertTrue(key(KeyEvent.KEYCODE_MEDIA_NEXT))
            pumpUntil { exo.currentPosition != 60_000L || exo.currentMediaItemIndex != 0 }
            assertEquals(90_000L, exo.currentPosition, "index=${exo.currentMediaItemIndex}")
            assertEquals(0, exo.currentMediaItemIndex)
            legacy.onSkipToPrevious()
            pumpUntil { exo.currentPosition == 80_000L }
            hardware = HardwareButtons.EPISODE
            legacy.onSkipToNext()
            pumpUntil { exo.currentMediaItemIndex == 1 }
            key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            pumpUntil { previous == 1 }
        } finally {
            MediaController.releaseFuture(notification)
            session.release()
        }
    }

    @Test fun negativeUserSeekClampsToZero() {
        load()
        player.seekTo(-5)
        assertEquals(0L, exo.currentPosition)
    }
}
