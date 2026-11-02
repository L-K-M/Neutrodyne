// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
        if (FirstLaunchRestoreSeed.isRequested(InstrumentationRegistry.getArguments())) {
            FirstLaunchRestoreSeed.run(app as NeutrodyneApplication)
        }
        super.callApplicationOnCreate(app)
    }

    override fun onStart() {
        hideSystemErrorDialogs()
        super.onStart()
    }

    /**
     * A loaded emulator's System UI can miss its boot deadline, and its ANR dialog then holds window
     * focus from every test activity, so Espresso times out waiting for focus (#262). With dialogs
     * hidden the system kills an unresponsive process instead, so this app's own ANR still fails.
     */
    private fun hideSystemErrorDialogs() {
        val automation =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
            } else {
                uiAutomation
            }
        for (command in SYSTEM_DIALOG_COMMANDS) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    private companion object {
        val SYSTEM_DIALOG_COMMANDS =
            listOf(
                "settings put global hide_error_dialogs 1",
                "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS",
            )
    }
}
