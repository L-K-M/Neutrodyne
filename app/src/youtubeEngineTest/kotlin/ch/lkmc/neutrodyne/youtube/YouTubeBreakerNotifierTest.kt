// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import ch.lkmc.neutrodyne.INTERNAL_MAIN_ACTIVITY
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeHealth
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class YouTubeBreakerNotifierTest {
    @Test fun openingPostsOneRetryNoticeAndClosingRemovesIt() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val manager = app.getSystemService(NotificationManager::class.java)
            val health = FakeYouTubeHealth()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                YouTubeBreakerNotifier(app, health, scope).run()
                health.state.value = YouTubeHealthState(breaker = BreakerState.OPEN, breakerOpenUntil = Long.MAX_VALUE)
                withTimeout(10_000) { while (manager.activeNotifications.isEmpty()) delay(10) }
                assertEquals(1, manager.activeNotifications.size)
                val notification = manager.activeNotifications.single().notification
                assertEquals(1, notification.actions.size)
                assertTrue(
                    notification.extras
                        .getCharSequence("android.text")
                        .toString()
                        .contains("retries automatically"),
                )
                health.state.value = YouTubeHealthState()
                withTimeout(10_000) { while (manager.activeNotifications.isNotEmpty()) delay(10) }
            } finally {
                scope.cancel()
                manager.cancelAll()
            }
        }

    @Test fun openingNoticeTapsThroughTheInternalAliasToTheYouTubeSettingsRoute() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val manager = app.getSystemService(NotificationManager::class.java)
            val health = FakeYouTubeHealth()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                YouTubeBreakerNotifier(app, health, scope).run()
                health.state.value = YouTubeHealthState(breaker = BreakerState.OPEN, breakerOpenUntil = Long.MAX_VALUE)
                withTimeout(10_000) { while (manager.activeNotifications.isEmpty()) delay(10) }
                val intent =
                    shadowOf(
                        manager.activeNotifications
                            .single()
                            .notification.contentIntent,
                    ).savedIntent
                // The router only honours an internal route when the component names the
                // non-exported alias; aimed at MainActivity the tap is treated as external.
                assertEquals(INTERNAL_MAIN_ACTIVITY, intent.component!!.className)
                assertEquals(app.packageName, intent.component!!.packageName)
                assertEquals("neutrodyne://open/settings/youtube", intent.dataString)
                // The OS resolves the alias when the user taps, not when the PendingIntent is
                // built. Resolving it here makes a renamed or dropped manifest alias fail this
                // test instead of leaving a notification whose tap silently does nothing.
                val alias = app.packageManager.getActivityInfo(intent.component!!, 0)
                assertTrue(alias.exported.not())
            } finally {
                scope.cancel()
                manager.cancelAll()
            }
        }
}
