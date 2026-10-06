// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Build
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    @After
    fun resetNightMode() {
        compose.runOnUiThread { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
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

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_FEEDS).assertIsDisplayed()
    }

    @Test
    fun aboutShowsVersionAndAbi() {
        compose.enableAccessibilityChecks()
        openSettings()
        compose.onNodeWithText("About").performClick()
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
        compose.onNodeWithText("Licences").performClick()
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
        compose.onAllNodesWithContentDescription(text(Res.string.settings)).onFirst().performClick()
        compose.waitForIdle()
    }

    /** The APK's ABI, resolved like `AndroidBuildInfo`. */
    private fun apkAbi(): String? =
        if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull()
        }

    private fun assertDestinations() {
        for ((tag, label) in DESTINATIONS) {
            // The label must belong to the destination itself; the selected tab's title repeats it in the top bar
            compose.onNodeWithTag(tag).assertIsDisplayed().assert(hasText(text(label)))
        }
    }

    private fun text(resource: StringResource): String = runBlocking { getString(resource) }

    private companion object {
        const val TAG_FEEDS = "nav_feeds"

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
