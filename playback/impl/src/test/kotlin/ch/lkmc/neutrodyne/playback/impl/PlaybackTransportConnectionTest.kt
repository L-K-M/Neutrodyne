// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaybackTransportConnectionTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test fun foregroundTransportsBindAgainAfterTheProcessReturns() {
        val app: Application = RuntimeEnvironment.getApplication()
        LifecycleHarness(app).use { h ->
            val player = TestExoPlayerBuilder(app).setMediaSourceFactory(AdapterSources()).build()
            player.setMediaItem(MediaItem.fromUri("https://fixture.test/transport.mp3"))
            player.setSeekForwardIncrementMs(PlaybackSettingKeys.SKIP_FORWARD_MS.default)
            player.prepare()
            TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)
            val session = MediaSession.Builder(app, player).build()
            val owner = Owner().also { it.lifecycle.currentState = Lifecycle.State.STARTED }
            var builds = 0
            val connection = PlayerConnection(owner.lifecycle) {
                builds++
                MediaController.Builder(app, session.token).buildAsync()
            }
            val controller = PlaybackControllerImpl(
                connection, h.hub, h.gate, h.opener, Provider { h.queues }, Provider { h.sessions },
                h.clock, h.scope, Dispatchers.IO,
            )
            try {
                controller.seekTo(TARGET_MS)
                pumpUntil { player.currentPosition == TARGET_MS }
                assertEquals(1, builds)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                assertNull(connection.controller)
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                controller.skipForward()
                val expected = TARGET_MS + PlaybackSettingKeys.SKIP_FORWARD_MS.default
                pumpUntil { player.currentPosition == expected }
                assertEquals(2, builds)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                controller.skipBack()
                assertEquals(2, builds)
                assertEquals(expected, player.currentPosition)
            } finally {
                connection.onStop(owner)
                session.release()
                player.release()
            }
        }
    }

    private companion object {
        const val TARGET_MS = 23_456L
    }
}
