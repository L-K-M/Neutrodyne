// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read the sticky battery state before the first claim; keep one process-lifetime receiver. */
@SingleIn(AppScope::class)
@Inject
internal class DownloadChargingMonitor(
    private val application: Application,
) {
    private val state =
        MutableStateFlow(isCharging(application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))))

    val charging = state.asStateFlow()

    suspend fun observe() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    state.value = isCharging(intent)
                }
            }
        application.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try {
            awaitCancellation()
        } finally {
            application.unregisterReceiver(receiver)
        }
    }

    private fun isCharging(intent: Intent?): Boolean =
        intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
            setOf(
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
            )
}
