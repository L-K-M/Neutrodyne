// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on API26/API37 with platform update delivery; no public-network fixture is required. */
@ReleaseSmoke
@RunWith(AndroidJUnit4::class)
class UpdateNavigationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun updateNotificationRouteAndHelpSurviveRecreation() {
        compose.enableAccessibilityChecks()
        assertRestoredRoute("neutrodyne://open/settings/updates", "updates-page")
        assertRestoredRoute("neutrodyne://open/help/install", "install-help-page")
    }

    private fun assertRestoredRoute(
        uri: String,
        tag: String,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClassName(context, INTERNAL_MAIN_ACTIVITY)
        // ActivityScenario matches the launch intent across lifecycle callbacks. Warm delivery
        // replaces that intent in MainActivity, so each route needs its own cold-launch scenario.
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithTag(tag).assertExists()
            scenario.recreate()
            compose.onNodeWithTag(tag).assertExists()
        }
    }
}
