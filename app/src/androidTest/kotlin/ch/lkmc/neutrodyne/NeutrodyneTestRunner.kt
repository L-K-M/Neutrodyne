// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner

/**
 * Sets `neutrodyne.instrumentedTest` before the application is created, so ACRA stays uninstalled and LeakCanary's
 * heap dumps stay off in instrumented runs (09 Gradle Managed Devices).
 *
 * `firstLaunchSeed=restore` additionally drives [FirstLaunchRestoreSeed] between app creation and
 * `onCreate`: the instrumentation argument is read by the runner (populated before
 * `callApplicationOnCreate` on every supported API level), the seeded snapshot is produced by the
 * real backup writer, and `super.callApplicationOnCreate` then performs the genuine cold-start the
 * test asserts on — no production hook, receiver or flag is involved.
 *
 * `bmgrPhase` (armed through [bmgrArmedMethod]) is the M3 AC6 transport gate instead: the verify
 * leg must observe the app-private tree exactly as install-time restore delivered it — the two
 * payload files present with their expected hashes, the database family and device DataStore
 * absent so the band-100 opener is still pending, and every exclusion canary missing — all before
 * `super` runs the real production cold-start. The empty leg proves a pristine tree. Neither leg
 * constructs a graph, seeds, restores, or deletes anything here.
 */
class NeutrodyneTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        // Select before discovery/execution, including orchestrator discovery. AGP 9.4.1's
        // Android Test Engine 1.0.1 writes assumption status -4 as an XML failure.
        val excluded =
            selectSupportedDeviceTests(
                arguments,
                Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS.first(),
                BuildConfig.SHIPPED_LOCALES.split(',').map { it.trim() },
                FirstLaunchRestoreSeed.isRequested(arguments),
            )
        excluded.forEach { (selector, reason) -> Log.i("DeviceTestSelection", "Not selected: $selector ($reason)") }
        super.onCreate(arguments)
    }

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        System.setProperty(NeutrodyneApplication.INSTRUMENTED_TEST_PROPERTY, "true")
        return super.newApplication(cl, className, context)
    }

    override fun callApplicationOnCreate(app: Application) {
        val arguments = InstrumentationRegistry.getArguments()
        when (bmgrArmedMethod(arguments, Build.VERSION.SDK_INT)) {
            BmgrTransportPhases.METHODS[BmgrTransportPhases.VERIFY] -> {
                BmgrTransportExpectation.verifyRestoredBeforeLaunch(
                    BmgrTransportExpectation.Roots(app),
                    BmgrTransportExpectation.requireExpected(arguments),
                )
            }

            BmgrTransportPhases.METHODS[BmgrTransportPhases.EMPTY] -> {
                BmgrTransportExpectation.verifyNothingRestored(BmgrTransportExpectation.Roots(app))
            }

            else -> {
                if (FirstLaunchRestoreSeed.isRequested(arguments)) {
                    FirstLaunchRestoreSeed.run(app as NeutrodyneApplication)
                }
            }
        }
        super.callApplicationOnCreate(app)
    }
}
