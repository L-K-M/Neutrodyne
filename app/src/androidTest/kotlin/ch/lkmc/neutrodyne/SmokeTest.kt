// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
        assertDestinations()
    }

    @Test
    fun destinationsSurviveRotationAndDarkMode() {
        compose.activityRule.scenario.recreate()
        assertDestinations()

        compose.runOnUiThread { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        compose.waitForIdle()
        assertDestinations()
    }

    @Test
    fun settingsOpensAndBackReturnsToTheTab() {
        compose.onAllNodesWithContentDescription(text(Res.string.settings)).onFirst().performClick()
        compose.waitForIdle()

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_FEEDS).assertIsDisplayed()
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
