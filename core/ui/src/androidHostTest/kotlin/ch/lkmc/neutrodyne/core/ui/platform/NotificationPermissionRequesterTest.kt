// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import android.Manifest
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationPermissionRequesterTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun reset() {
        rule.activity
            .getSharedPreferences("notification_permission", 0)
            .edit()
            .clear()
            .commit()
        shadowOf(rule.activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun deniedRequestDoesNotLaunchAgainAfterRecreation() {
        var launches = 0
        val requester =
            AndroidNotificationPermissionRequester(rule.activity) { result ->
                launches++
                result(false)
            }
        var allowed: Boolean? = null
        requester.request { allowed = it }
        assertEquals(false, allowed)
        assertEquals(1, launches)
        val recreated = AndroidNotificationPermissionRequester(rule.activity) { error("Repeated dialog") }
        recreated.request { allowed = it }
        assertEquals(false, allowed)
        assertFalse(recreated.granted)
    }

    @Test
    fun grantedPermissionSkipsLauncherAndRefreshesAfterSettings() {
        val requester = AndroidNotificationPermissionRequester(rule.activity) { error("Already granted") }
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        requester.request { assertTrue(it) }
        assertTrue(requester.granted)
        shadowOf(rule.activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        requester.refresh()
        assertFalse(requester.granted)
    }

    @Test
    fun resultIsDeliveredAndSettingsTargetsThisApp() {
        val requester = AndroidNotificationPermissionRequester(rule.activity) { result -> result(true) }
        requester.request { assertTrue(it) }
        assertTrue(requester.granted)
        requester.openSettings()
        val intent = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(rule.activity.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun android33SuppliesRequesterWithoutPromptingAtComposition() {
        var actions: PlatformActions? = null
        rule.setContent { actions = rememberAndroidPlatformActions() }
        rule.waitForIdle()
        assertNotNull(actions?.notifications)
        assertFalse(actions!!.notifications!!.granted)
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [32])
    fun olderAndroidHasNoRuntimePermissionControl() {
        var actions: PlatformActions? = null
        rule.setContent { actions = rememberAndroidPlatformActions() }
        rule.waitForIdle()
        assertNotNull(actions)
        assertNull(actions!!.notifications)
    }

    @Test
    fun downloadNoticeActionsOpenTheMatchingSystemSettings() {
        var actions: PlatformActions? = null
        rule.setContent { actions = rememberAndroidPlatformActions() }
        rule.waitForIdle()
        val cases =
            mapOf(
                DownloadSystemSettingsTarget.DATA_SAVER to Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                DownloadSystemSettingsTarget.BACKGROUND to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                DownloadSystemSettingsTarget.STORAGE to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            )
        for ((target, action) in cases) {
            rule.runOnIdle { assertTrue(actions!!.downloadSettings!!.open(target)) }
            val intent = shadowOf(rule.activity).nextStartedActivity
            assertEquals(action, intent.action)
            val expectedData =
                when (target) {
                    DownloadSystemSettingsTarget.STORAGE -> null
                    else -> "package:${rule.activity.packageName}"
                }
            assertEquals(expectedData, intent.dataString)
        }
    }
}
