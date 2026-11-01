// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperOptionsTest {
    @Test fun opensDeveloperOptionsAndFallsBackToAboutPhone() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val actions = mutableListOf<String?>()
        val context =
            object : ContextWrapper(activity) {
                override fun startActivity(intent: Intent) {
                    actions += intent.action
                    if (intent.action == Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) {
                        throw ActivityNotFoundException()
                    }
                }
            }
        assertTrue(openDeveloperOptions(context))
        val expected =
            listOf(
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                Settings.ACTION_DEVICE_INFO_SETTINGS,
            )
        assertEquals(expected, actions.filterNotNull())
    }

    @Test fun missingSystemScreensLeaveOfflineInstructionsAvailable() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context =
            object : ContextWrapper(activity) {
                override fun startActivity(intent: Intent): Unit = throw ActivityNotFoundException()
            }
        assertFalse(openDeveloperOptions(context))
    }
}
