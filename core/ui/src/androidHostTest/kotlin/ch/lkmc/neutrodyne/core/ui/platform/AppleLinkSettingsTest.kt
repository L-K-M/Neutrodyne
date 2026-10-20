// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppleLinkSettingsTest {
    @Test
    fun opensAppSpecificPreferences() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertEquals(OpenResult.OPENED, AndroidAppleLinkSettings(activity).openSettings())
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, intent.action)
        assertEquals("package:${activity.packageName}", intent.dataString)
        activity.finish()
    }

    @Test
    fun absentSettingsHandlerReturnsExplicitFailure() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context =
            object : ContextWrapper(activity) {
                override fun startActivity(intent: Intent): Unit = throw ActivityNotFoundException()
            }
        assertEquals(OpenResult.NO_HANDLER, AndroidAppleLinkSettings(context).openSettings())
        activity.finish()
    }
}
