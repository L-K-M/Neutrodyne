// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaybackStateHubTest {
    @Test fun observingDurableSessionWaitsForOpenAndNeverConnects() {
        LifecycleHarness(RuntimeEnvironment.getApplication(), delayed = true).use { h ->
            h.seedCurrent()
            assertNull(h.hub.nowPlaying.value)
            assertEquals(0, h.dbProviderReads)
            val job = h.scope.launch { h.hub.nowPlaying.collect() }
            pumpUntil { h.openStarted.count == 0L }
            assertEquals(0, h.dbProviderReads)
            assertNull(h.connection.controller)
            h.allowOpen.countDown()
            pumpUntil { h.hub.nowPlaying.value != null }
            val item = assertNotNull(h.hub.nowPlaying.value)
            assertEquals(PlayerPhase.NOT_LOADED, item.phase)
            assertFalse(item.isPlaying)
            assertEquals(12_000L, item.position.positionMs)
            h.positions.positions.value += 1L to StoredPosition(1, 25_000, h.clock.now(), null)
            pumpUntil {
                h.hub.nowPlaying.value
                    ?.position
                    ?.positionMs == 25_000L
            }
            assertNull(h.connection.controller)
            job.cancel()
        }
    }

    @Test fun noSubscribersDoNotDereferenceDatabaseProviders() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            assertEquals(0, h.dbProviderReads)
            assertFalse(h.opener.openState.value is DatabaseOpenState.Opened)
            assertNull(h.hub.nowPlaying.value)
            assertNull(h.hub.effectivePlayback.value)
        }
    }
}
