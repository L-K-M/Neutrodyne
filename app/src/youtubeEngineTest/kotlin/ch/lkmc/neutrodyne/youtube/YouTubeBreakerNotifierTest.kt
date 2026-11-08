// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube

import android.Manifest
import android.app.Application
import android.app.NotificationManager
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
}
