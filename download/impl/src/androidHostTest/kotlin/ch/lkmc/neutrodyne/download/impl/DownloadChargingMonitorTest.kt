// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33, 34, 36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadChargingMonitorTest {
    private val application = RuntimeEnvironment.getApplication()

    @Test fun readsStickyStateBeforeCollectorAndTracksUnplugging() =
        runTest {
            send(BatteryManager.BATTERY_STATUS_CHARGING)
            val monitor = DownloadChargingMonitor(application)
            assertTrue(monitor.charging.value)
            val job = backgroundScope.launch { monitor.observe() }
            runCurrent()
            send(BatteryManager.BATTERY_STATUS_DISCHARGING)
            assertFalse(monitor.charging.value)
            send(BatteryManager.BATTERY_STATUS_FULL)
            assertTrue(monitor.charging.value)
            job.cancel()
            job.join()
            send(BatteryManager.BATTERY_STATUS_DISCHARGING)
            assertTrue(monitor.charging.value)
        }

    private fun send(status: Int) {
        application.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_STATUS, status),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }
}
