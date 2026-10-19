// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.content.Context
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
 */
class NeutrodyneTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        System.setProperty(NeutrodyneApplication.INSTRUMENTED_TEST_PROPERTY, "true")
        return super.newApplication(cl, className, context)
    }

    override fun callApplicationOnCreate(app: Application) {
        if (FirstLaunchRestoreSeed.isRequested(InstrumentationRegistry.getArguments())) {
            FirstLaunchRestoreSeed.run(app as NeutrodyneApplication)
        }
        super.callApplicationOnCreate(app)
    }
}
