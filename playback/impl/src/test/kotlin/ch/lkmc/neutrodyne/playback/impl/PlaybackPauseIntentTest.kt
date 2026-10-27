// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaybackPauseIntentTest {
    @Test
    fun canonicalPauseCancelsTheSuspendedSessionPlayIntent() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            val exo = ExoPlayer.Builder(h.app).build()
            val entered = CompletableDeferred<Unit>()
            val decision = CompletableDeferred<Boolean>()
            val wrapper =
                SessionPlayer(exo, h.scope, {
                    entered.complete(Unit)
                    decision.await()
                }, { true }, {}, {})
            val bridge = AndroidServiceBridge(exo, h.run { h.access.open() }, h.access, h.scope, { true }, {}, wrapper::pause)
            h.hub.attach(bridge)
            try {
                exo.setMediaItem(MediaItem.fromUri("https://synthetic.test/item.mp3"))
                shadowOf(Looper.getMainLooper()).idle()
                wrapper.play()
                pumpUntil { entered.isCompleted }

                h.controller.pause()
                decision.complete(true)
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(exo.playWhenReady, "A completed old play must not undo the canonical pause")
            } finally {
                decision.complete(false)
                h.hub.detach(bridge)
                bridge.detach()
                wrapper.release()
            }
        }
    }
}
