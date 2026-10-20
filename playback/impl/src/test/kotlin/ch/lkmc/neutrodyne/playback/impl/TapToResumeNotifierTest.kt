// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TapToResumeNotifierTest {
    @Test fun deniedPermissionLeavesRecoveryInApp() {
        val app: Application = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(TapToResumeNotifier(app).post())
        val manager = app.getSystemService(NotificationManager::class.java)
        assertNull(shadowOf(manager).getNotification(PlaybackChannels.NOTIF_ID_TAP_TO_RESUME))
    }

    @Test fun grantedRecoveryOffersAnExplicitMediaButtonActionAndCancels() {
        val app: Application = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = TapToResumeNotifier(app)
        assertTrue(notifier.post())
        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = assertNotNull(shadowOf(manager).getNotification(PlaybackChannels.NOTIF_ID_TAP_TO_RESUME))
        val action = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(Intent.ACTION_MEDIA_BUTTON, action.action)
        assertEquals(NeutrodynePlaybackService::class.java.name, action.component?.className)
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals("neutrodyne://open/player", open.data.toString())
        assertEquals("ch.lkmc.neutrodyne.MainActivity", open.component?.className)
        notifier.cancel()
        assertNull(shadowOf(manager).getNotification(PlaybackChannels.NOTIF_ID_TAP_TO_RESUME))
    }
}
