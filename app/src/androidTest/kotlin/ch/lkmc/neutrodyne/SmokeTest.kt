// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToLog
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.nav_up_next
import ch.lkmc.neutrodyne.core.ui.resources.settings
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * E0 (09 End-to-end journeys, PLAN M0 AC4): five labelled destinations, rotation and a dark-mode switch survive,
 * Settings opens and back returns. Runs on the debug build in `ci` and on the published release build in the
 * release-type smoke runs ([ReleaseSmoke]). The predictive-back animation is checked by hand (ATD has no gesture).
 */
@RunWith(AndroidJUnit4::class)
@ReleaseSmoke
class SmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private var originalVmPolicy: StrictMode.VmPolicy? = null

    /**
     * `ActivityScenario.recreate()` keeps the destroyed activity reachable until the
     * relaunch completes; StrictMode's activity-leak heuristic counts it and the debug
     * policy kills the process (a relaunch false positive, API 26 logcat). The relaunch
     * can still be in flight when the Compose rule tears the activity down, so the
     * relaxed policy must outlive `@After`: this rule has a higher order than the
     * Compose rule and restores the app's policy after its teardown.
     */
    @get:Rule(order = 1)
    val relaxActivityLeakDetection =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    // The app's debug initializers run asynchronously; replacing the policy
                    // before they set it would be overwritten again.
                    waitForAppVmPolicy()
                    originalVmPolicy = StrictMode.getVmPolicy()
                    Log.i("SmokeTestPolicy", "app policy=$originalVmPolicy")
                    StrictMode.setVmPolicy(
                        StrictMode.VmPolicy
                            .Builder()
                            .detectLeakedClosableObjects()
                            .detectLeakedSqlLiteObjects()
                            .detectLeakedRegistrationObjects()
                            .detectFileUriExposure()
                            .penaltyLog()
                            .penaltyDeath()
                            .build(),
                    )
                    try {
                        base.evaluate()
                    } finally {
                        Log.i("SmokeTestPolicy", "restore policy=$originalVmPolicy")
                        originalVmPolicy?.let(StrictMode::setVmPolicy)
                        originalVmPolicy = null
                    }
                }
            }
        }

    private fun waitForAppVmPolicy() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val policy = StrictMode.getVmPolicy().toString()
            val mask = policy.substringAfter("mask=").removeSuffix("]").trim()
            if (mask != "0") return
            Thread.sleep(50)
        }
    }

    @Before
    fun resetNightMode() {
        // Reset before the journey, not after it: resetting after the test would
        // trigger a configuration-change relaunch while the Compose rule is tearing
        // the activity down, outside the relaxed policy's lifetime.
        compose.runOnUiThread { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        compose.waitForIdle()
    }

    @Test
    fun fiveLabelledDestinations() {
        compose.enableAccessibilityChecks()
        assertDestinations()
    }

    @Test
    fun destinationsSurviveRotationAndDarkMode() {
        compose.enableAccessibilityChecks()
        compose.activityRule.scenario.recreate()
        assertDestinations()

        compose.runOnUiThread { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        compose.waitForIdle()
        assertDestinations()
    }

    @Test
    fun settingsOpensAndBackReturnsToTheTab() {
        compose.enableAccessibilityChecks()
        openSettings()
        clearSystemErrorDialogs()

        try {
            pressBack()
        } catch (failure: RuntimeException) {
            // Capture focus and ANR causes before teardown, without replacing the Back failure.
            runCatching(::recordWindowFocus).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_FEEDS).assertIsDisplayed()
    }

    @Test
    fun aboutShowsVersionAndAbi() {
        compose.enableAccessibilityChecks()
        openSettings()
        // The About row sits at the bottom of the settings list; on the small API 37
        // window it is below the fold, so it is scrolled into view first, as the
        // Licences row below is.
        compose.onNodeWithText("About").performScrollTo().performClick()
        compose.waitForIdle()

        compose
            .onNodeWithText("Version", substring = true)
            .assertIsDisplayed()
            .assert(hasText(BuildConfig.VERSION_NAME, substring = true))
        apkAbi()?.let {
            compose.onNodeWithText(it, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun licencesListsLibraries() {
        compose.enableAccessibilityChecks()
        openSettings()
        // M7's Discover and Privacy rows put the Licences row near the bottom of the small
        // API 26 window, where a click on an out-of-view row does nothing (device runs); the
        // row is scrolled into view first, as the other screen tests do.
        val licences = compose.onNodeWithText("Licences").performScrollTo()
        try {
            licences.performClick()
        } catch (failure: RuntimeException) {
            // Preserve the clickable bounds if ATF catches a size defect on a device-only layout.
            runCatching {
                compose.onRoot(useUnmergedTree = true).printToLog("SettingsTouchTargets")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        compose.waitForIdle()

        // A non-empty list shows its section headers (a default build always has both).
        // The labels are the en defaults; `:feature:settings`'s generated Res stays internal
        // (only `:core:ui` exports one), so the smoke test pins the English literals. The list
        // loads asynchronously, and the bundled section follows every library, so it is scrolled to.
        compose.waitUntil(LICENCES_LOAD_TIMEOUT_MS) {
            compose.onAllNodes(hasText(LIBRARIES_HEADER, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(LIBRARIES_HEADER, substring = true).assertIsDisplayed()
        compose
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(BUNDLED_HEADER, substring = true))
        compose.onNodeWithText(BUNDLED_HEADER, substring = true).assertIsDisplayed()
    }

    private fun openSettings() {
        // The database opens on IO: Compose can be idle while the root still shows its
        // startup gate. Resolve the label on each poll so a locale recreation cannot stale it.
        compose.waitUntil(STARTUP_TIMEOUT_MS) {
            compose
                .onAllNodesWithContentDescription(text(Res.string.settings))
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        compose.onAllNodesWithContentDescription(text(Res.string.settings)).onFirst().performClick()
        compose.waitForIdle()
    }

    /**
     * Espresso waits for window focus, and an emulator's System UI ANR dialog from boot holds it
     * (nightly release smoke, #262). With dialogs hidden the system kills an unresponsive process
     * instead, so this app's own ANR still fails the test.
     */
    private fun clearSystemErrorDialogs() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("settings put global hide_error_dialogs 1")
        device.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    }

    private fun recordWindowFocus() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Per-test logcat omits boot and inter-test ANR records.
        val commands =
            listOf(
                "dumpsys -t $FOCUS_DUMP_TIMEOUT_SECONDS window",
                "dumpsys -t $FOCUS_DUMP_TIMEOUT_SECONDS activity activities",
                "dumpsys -t $FOCUS_DUMP_TIMEOUT_SECONDS activity lastanr",
                "dumpsys -t $FOCUS_DUMP_TIMEOUT_SECONDS dropbox --print system_app_anr",
                "timeout $FOCUS_DUMP_TIMEOUT_SECONDS logcat -b events -d -v threadtime -s am_anr",
            )

        for (command in commands) {
            Log.e(FOCUS_EVIDENCE_TAG, command)
            device
                .executeShellCommand(command)
                .lineSequence()
                .forEach { Log.e(FOCUS_EVIDENCE_TAG, it) }
        }
    }

    /** The APK's ABI, resolved like `AndroidBuildInfo`. */
    private fun apkAbi(): String? =
        if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull()
        }

    private fun assertDestinations() {
        // Compose can be idle while the database opens on IO, including after recreation.
        compose.waitUntil(STARTUP_TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(TAG_FEEDS)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        for ((tag, label) in DESTINATIONS) {
            // The label must belong to the destination itself; the selected tab's title repeats it in the top bar
            compose.onNodeWithTag(tag).assertIsDisplayed().assert(hasText(text(label)))
        }
    }

    private fun text(resource: StringResource): String = runBlocking { getString(resource) }

    private companion object {
        const val TAG_FEEDS = "nav_feeds"
        const val STARTUP_TIMEOUT_MS = 10_000L
        const val FOCUS_EVIDENCE_TAG = "BackFocusEvidence"
        const val FOCUS_DUMP_TIMEOUT_SECONDS = 5

        // Section headers render "<title> · <count>"; the separator keeps library names such as
        // "AboutLibraries" from matching.
        const val LIBRARIES_HEADER = "Libraries · "
        const val BUNDLED_HEADER = "Bundled components · "
        const val LICENCES_LOAD_TIMEOUT_MS = 10_000L

        val DESTINATIONS =
            listOf(
                TAG_FEEDS to Res.string.nav_feeds,
                "nav_library" to Res.string.nav_library,
                "nav_up_next" to Res.string.nav_up_next,
                "nav_downloads" to Res.string.nav_downloads,
                "nav_discover" to Res.string.nav_discover,
            )
    }
}
