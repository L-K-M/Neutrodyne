// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Looper
import androidx.media3.common.Player
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(application = PlaybackTestApplication::class, sdk = [34])
class PlaybackMeteredTransportTest {
    @Test
    fun nextDoesNotBypassNeverAfterTheNetworkBecomesMetered() {
        val app = RuntimeEnvironment.getApplication() as PlaybackTestApplication
        LifecycleHarness(app).use { h ->
            h.seedCurrent()
            h.lookup.items.value += 2L to media(id = 2)
            h.publish(h.store.queue.copy(upNext = listOf(QueueItem(2, QueueOrigin.UP_NEXT))))
            h.run { h.settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.NEVER) }
            app.graph = object : PlaybackServiceInjector {
                override fun inject(service: NeutrodynePlaybackService) {
                    service.access = h.access
                }
            }
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            try {
                pumpUntil { h.hub.bridge != null }
                val player = assertNotNull(service.get().onGetSession(controllerInfo())).player
                pumpUntil { player.mediaItemCount == 2 }
                player.play()
                shadowOf(Looper.getMainLooper()).idle()
                h.network.setStatus(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
                shadowOf(Looper.getMainLooper()).idle()

                player.seekToNextMediaItem()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, player.currentMediaItemIndex, "Never must apply to cross-item transports too")
                assertEquals("episode:1", player.currentMediaItem?.mediaId)
            } finally {
                service.destroy()
            }
        }
    }
}
