// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.sync

import ch.lkmc.neutrodyne.core.testing.CrossDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The desktop half of E12 (`CrossDeviceSyncTest`, 09 End-to-end journeys): a host-driven phase
 * driver over the real `DesktopAppGraph`, Room and `SyncController`/`KtorSyncClient`.
 *
 * The host passes the caller-owned isolated root through `-PcrossDeviceRoot=<dir>` (or
 * `NEUTRODYNE_CROSS_DEVICE_ROOT`) and the phase through `-PcrossDevicePhase=seed|link|edit|sync|dump`.
 * The class carries the `CrossDevice` category, so `:desktopApp`'s test tasks exclude it before
 * discovery unless the `crossDevice` Gradle property is present; an opted-in run without a root
 * fails clearly instead of assuming a default or skipping.
 */
@Category(CrossDevice::class)
class CrossDeviceSyncDesktopTest {
    @Test
    fun runRequestedPhase() {
        val root = crossDeviceRoot()
        val phase = System.getProperty("neutrodyne.crossDevice.phase")
        runBlocking(Dispatchers.IO) {
            CrossDeviceDesktopRunner(root, phase).run()
        }
    }

    private fun crossDeviceRoot(): Path {
        val fromProperty = System.getProperty("neutrodyne.crossDevice.root")
        val fromEnvironment = System.getenv("NEUTRODYNE_CROSS_DEVICE_ROOT")
        val configured = fromProperty ?: fromEnvironment
        check(!configured.isNullOrBlank()) {
            "cross-device desktop driver: no isolated root configured; " +
                "pass -PcrossDeviceRoot=<dir> (or set NEUTRODYNE_CROSS_DEVICE_ROOT)"
        }
        return Paths.get(configured)
    }
}
