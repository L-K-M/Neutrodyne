// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import ch.lkmc.neutrodyne.core.testing.TestClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 33, 34, 36])
class DownloadNotificationsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val notifications = DownloadNotifications(app, clock)
    private val manager = app.getSystemService(NotificationManager::class.java)

    @Test fun channelsInitialNotificationAndExplicitImmutableAction() {
        val initial = notifications.progress()
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(DownloadNotifications.CHANNEL).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(DownloadNotifications.ERROR_CHANNEL).importance,
        )
        assertEquals(Notification.CATEGORY_PROGRESS, initial.category)
        assertTrue(initial.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val named = notifications.progress(1, 12, 100, "Episode title")
        assertTrue(named.extras.getString(Notification.EXTRA_TITLE)!!.contains("Episode title"))
        assertTrue(named.extras.getString(Notification.EXTRA_TEXT)!!.isNotEmpty())
        val pause = initial.actions.single().actionIntent
        assertTrue(shadowOf(pause).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = shadowOf(pause).savedIntent
        assertEquals(DownloadActionReceiver::class.java.name, intent.component!!.className)
        assertEquals(DownloadActionReceiver.PAUSE_ALL, intent.action)
        assertEquals("neutrodyne://open/downloads", shadowOf(initial.contentIntent).savedIntent.data.toString())
        assertNotNull(shadowOf(initial.contentIntent).savedIntent.component)
    }

    @Test fun aggregateProgressAndByteReportsLimitedToOneHz() {
        val samples = mutableListOf<Pair<Notification, Long>>()
        val reporter =
            notifications.reporter { notification, _, _, transferred ->
                samples +=
                    notification to transferred
            }
        reporter.active(setOf(1, 2))
        reporter.transferred(20)
        reporter.bytes(100, 200)
        repeat(100) {
            reporter.transferred(1)
            reporter.bytes(101L + it, 300)
        }
        assertEquals(1, samples.size)
        clock.elapsedMs = 1_000
        reporter.bytes(200, 300)
        assertEquals(listOf(20L, 120L), samples.map { it.second })
        assertEquals(
            66,
            samples
                .last()
                .first.extras
                .getInt(Notification.EXTRA_PROGRESS),
        )
    }

    @Test fun deniedNotificationPermissionDoesNotBlockRequiredInitialNotification() {
        if (Build.VERSION.SDK_INT >= 33) shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertNotNull(notifications.progress())
        notifications.failures(setOf(1), "Episode", "Connection lost")
        if (Build.VERSION.SDK_INT >= 33) assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun errorsAreAggregatedAndDismissedUntilNextFailure() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.failures(setOf(1), "Episode", "Connection lost")
        assertEquals(1, manager.activeNotifications.size)
        notifications.dismissErrors()
        notifications.failures(setOf(1), "Episode", "Connection lost")
        assertTrue(manager.activeNotifications.isEmpty())
        notifications.failures(setOf(1, 2), "Episode", "Connection lost")
        assertEquals(1, manager.activeNotifications.size)
        notifications.failures(emptySet(), "", "")
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun storageAndForegroundNoticesClearAndRearm() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.storage(2)
        notifications.storage(3)
        notifications.needsForeground(true)
        notifications.needsForeground(true)
        assertEquals(2, manager.activeNotifications.size)
        notifications.storage(0)
        notifications.needsForeground(false)
        assertTrue(manager.activeNotifications.isEmpty())
        notifications.storage(1)
        assertEquals(1, manager.activeNotifications.size)
    }
}
