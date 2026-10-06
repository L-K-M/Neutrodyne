// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Sets `neutrodyne.instrumentedTest` before the application is created, so ACRA stays uninstalled and LeakCanary's
 * heap dumps stay off in instrumented runs (09 Gradle Managed Devices).
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
}
