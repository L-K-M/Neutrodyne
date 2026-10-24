// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Self-instrumentation runner for the bmgr driver APK. When the full arming triple
 * ([BmgrArming]) is absent the driver test class is excluded *before discovery*, so ordinary
 * `ciGroupAndroidDeviceTest` / `nightlyGroupAndroidDeviceTest` runs can never seed, click or
 * bind the target package — they only see [DriverSelfTest].
 */
class BmgrDriverRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        if (!BmgrArming.isArmed(arguments)) {
            val existing = arguments.getString(ARG_NOT_CLASS)
            arguments.putString(
                ARG_NOT_CLASS,
                if (existing.isNullOrEmpty()) {
                    BmgrArming.TEST_CLASS
                } else {
                    "$existing,${BmgrArming.TEST_CLASS}"
                },
            )
        }
        super.onCreate(arguments)
    }

    private companion object {
        const val ARG_NOT_CLASS = "notClass"
    }
}
