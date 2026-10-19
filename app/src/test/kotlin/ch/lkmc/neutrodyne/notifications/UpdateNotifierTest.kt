// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import ch.lkmc.neutrodyne.INTERNAL_MAIN_ACTIVITY
import ch.lkmc.neutrodyne.core.data.update.UpdatePostResult
import ch.lkmc.neutrodyne.core.navigation.IntentRouter
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.RouteInput
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.testing.updateContractInfo
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en")
class UpdateNotifierTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val adapter = UpdateNotifier(app)

    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @Test fun stableNotificationUsesProtectedImmutableTapAndReleaseAction() =
        runBlocking {
            val info = updateContractInfo().copy(notes = "First line\nSecond line")
            assertEquals(UpdatePostResult.ACCEPTED, adapter.post(info))
            adapter.post(info)
            val posted = manager.activeNotifications.single()
            assertEquals(4200, posted.id)
            val notification = posted.notification
            assertEquals("updates", notification.channelId)
            assertEquals("First line", notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals("Neutrodyne 1.0.0 is available", notification.extras.getString(Notification.EXTRA_TITLE))
            for ((pending, release) in listOf(
                notification.contentIntent to false,
                notification.actions.single().actionIntent to true,
            )) {
                assertTrue(pending.isImmutable)
                val intent = shadowOf(pending).savedIntent
                assertEquals(INTERNAL_MAIN_ACTIVITY, intent.component!!.className)
                assertEquals(app.packageName, intent.component!!.packageName)
                assertEquals(
                    Route.Push(SettingsKey(SettingsPage.UPDATES, openRelease = release)),
                    IntentRouter.route(RouteInput(RouteInput.Action.VIEW, uri = intent.dataString, internal = true)),
                )
            }
            adapter.cancel()
            assertTrue(manager.activeNotifications.isEmpty())
        }

    @Test fun deniedPermissionMasterSwitchAndChannelDegradeWithoutPosting() =
        runBlocking {
            val info = updateContractInfo()
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(UpdatePostResult.UNAVAILABLE, adapter.post(info))
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(manager).setNotificationsEnabled(false)
            assertEquals(UpdatePostResult.UNAVAILABLE, adapter.post(info))
            shadowOf(manager).setNotificationsEnabled(true)
            manager.createNotificationChannel(
                NotificationChannel("updates", "Blocked", NotificationManager.IMPORTANCE_NONE),
            )
            assertEquals(UpdatePostResult.UNAVAILABLE, adapter.post(info))
            assertTrue(manager.activeNotifications.isEmpty())
        }

    @Test fun channelRefreshPreservesUserImportanceAndTooNewAndroidDoesNotPost() =
        runBlocking {
            manager.createNotificationChannel(
                NotificationChannel("updates", "Old name", NotificationManager.IMPORTANCE_LOW),
            )
            UpdateChannels.ensure(app)
            assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("updates").importance)
            assertEquals("App updates", manager.getNotificationChannel("updates").name)
            assertEquals(UpdatePostResult.UNAVAILABLE, adapter.post(updateContractInfo().copy(minSdk = 35)))
            assertTrue(manager.activeNotifications.isEmpty())
        }

    @Test
    @Config(sdk = [26])
    fun api26PostsWithoutRuntimeNotificationPermission() =
        runBlocking {
            assertEquals(UpdatePostResult.ACCEPTED, adapter.post(updateContractInfo()))
            assertEquals(1, manager.activeNotifications.size)
        }
}
