// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on API26/API37 after W4/W5 integration; no public-network fixture is required. */
@ReleaseSmoke
@RunWith(AndroidJUnit4::class)
class UpdateNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun updateNotificationRouteAndHelpSurviveRecreation() {
        compose.enableAccessibilityChecks()
        open("neutrodyne://open/settings/updates")
        compose.onNodeWithTag("updates-page").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("updates-page").assertExists()
        open("neutrodyne://open/help/install")
        compose.onNodeWithTag("install-help-page").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("install-help-page").assertExists()
    }

    private fun open(uri: String) {
        compose.runOnUiThread {
            val activity = compose.activity
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClassName(activity, INTERNAL_MAIN_ACTIVITY)
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, intent)
        }
        compose.waitForIdle()
    }
}
