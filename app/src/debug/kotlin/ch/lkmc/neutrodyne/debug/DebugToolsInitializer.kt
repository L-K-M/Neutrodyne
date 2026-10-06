// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.debug

import android.os.StrictMode
import ch.lkmc.neutrodyne.NeutrodyneApplication
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import leakcanary.LeakCanary

/**
 * Debug builds only (01 Debug build type, initializer order 0): StrictMode and LeakCanary. Never compiled into a
 * release APK (`check-apk.sh --published`).
 *
 * Deviation (2026-10-06): network on the main thread and leaked resources crash (death penalty); disk access on
 * the main thread is only logged, because platform and library code (splash, ProfileInstaller, LeakCanary itself)
 * reads disk there and would make every debug run and instrumented test crash.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class DebugToolsInitializer : AppInitializer {
    override val order: Int = ORDER

    override suspend fun run() {
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )
        withContext(Dispatchers.Main) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectNetwork()
                    .penaltyDeathOnNetwork()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .build(),
            )
        }

        // Heap dumps stall device tests; NeutrodyneTestRunner sets the property (09 Gradle Managed Devices)
        val instrumented = System.getProperty(NeutrodyneApplication.INSTRUMENTED_TEST_PROPERTY) != null
        LeakCanary.config = LeakCanary.config.copy(dumpHeap = !instrumented)
    }

    private companion object {
        const val ORDER = 0
    }
}
