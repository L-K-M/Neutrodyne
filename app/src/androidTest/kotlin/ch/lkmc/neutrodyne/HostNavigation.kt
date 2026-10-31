// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A real Home press, verified by its resulting state rather than an accessibility-event receipt. */
internal suspend fun pressHomeAndAwaitBackground(
    device: UiDevice,
    host: MainActivity,
    timeoutMillis: Long,
) {
    check(
        withContext(Dispatchers.Main) { host.lifecycle.currentState == Lifecycle.State.RESUMED } &&
            device.currentPackageName == host.packageName,
    ) { "The host must be foreground before Home" }

    // UiAutomator may miss TYPE_WINDOW_CONTENT_CHANGED after Home has already stopped the host.
    val eventObserved = device.pressHome()
    Log.i(HOST_NAVIGATION_TAG, "home-event windowContentChanged=$eventObserved")
    withTimeout(timeoutMillis) {
        while (true) {
            val foreground = device.currentPackageName
            val stopped = withContext(Dispatchers.Main) { host.lifecycle.currentState == Lifecycle.State.CREATED }
            if (stopped && !foreground.isNullOrEmpty() && foreground != host.packageName) return@withTimeout
            delay(HOST_POLL_MS)
        }
    }
}

private const val HOST_NAVIGATION_TAG = "HostNavigationEvidence"
private const val HOST_POLL_MS = 100L
