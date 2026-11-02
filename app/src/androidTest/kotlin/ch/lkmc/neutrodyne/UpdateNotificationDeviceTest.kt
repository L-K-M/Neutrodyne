// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.data.update.UpdatePostResult
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.core.navigation.IntentRouter
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.RouteInput
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.notifications.UpdateNotifier
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on API26/API37 with notification permission both granted and denied. No SystemUI needed. */
@RunWith(AndroidJUnit4::class)
class UpdateNotificationDeviceTest {
    private val application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val adapter = UpdateNotifier(application)
    private val info =
        UpdateInfo(
            "1.0.0",
            1000095L,
            26,
            "2026-10-19T00:00:00Z",
            "Update test",
            "https://github.com/L-K-M/Neutrodyne/releases/tag/v1.0.0",
            null,
            null,
            null,
        )

    @After fun cleanup() = runBlocking { adapter.cancel() }

    @Test fun deniedPermissionDoesNotPostOrRequestPermission() =
        runBlocking {
            assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            val before = application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            assumeTrue(before == PackageManager.PERMISSION_DENIED)
            adapter.cancel()
            assertThat(adapter.post(info)).isEqualTo(UpdatePostResult.UNAVAILABLE)
            assertThat(manager.activeNotifications.none { it.id == UPDATE_ID }).isTrue()
            assertThat(application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)).isEqualTo(before)
        }

    @Test fun notificationTapAndActionDispatchTheProtectedUpdatesRoutes() =
        runBlocking {
            // The denied-state leg above runs without granting a permission from background work.
            val permission = Manifest.permission.POST_NOTIFICATIONS
            assumeTrue(
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    application.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
            )
            assumeTrue(manager.areNotificationsEnabled())
            assertThat(adapter.post(info)).isEqualTo(UpdatePostResult.ACCEPTED)
            val notification = manager.activeNotifications.single { it.id == UPDATE_ID }.notification
            val actions =
                listOf(
                    notification.contentIntent to false,
                    notification.actions.single().actionIntent to true,
                )
            for ((pending, release) in actions) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertThat(pending.isImmutable).isTrue()
                assertThat(pending.creatorPackage).isEqualTo(application.packageName)
                val received = CompletableDeferred<Intent>()
                pending.send(
                    application,
                    0,
                    null,
                    { _, intent, _, _, _ -> received.complete(intent) },
                    Handler(Looper.getMainLooper()),
                )
                val intent = withTimeout(10_000) { received.await() }
                assertThat(intent.component?.className).isEqualTo(INTERNAL_MAIN_ACTIVITY)
                assertThat(IntentRouter.route(RouteInput(RouteInput.Action.VIEW, intent.dataString, internal = true)))
                    .isEqualTo(Route.Push(SettingsKey(SettingsPage.UPDATES, openRelease = release)))
            }
        }

    private companion object {
        const val UPDATE_ID = 4200
    }
}
