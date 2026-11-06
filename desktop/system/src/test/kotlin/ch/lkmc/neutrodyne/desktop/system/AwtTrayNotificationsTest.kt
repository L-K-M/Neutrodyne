// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.NotificationKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The AWT fallback's deterministic half: without an image no message can be shown and the
 * post reports undelivered — on every OS, so CI's headless JVMs and a dev run prove the
 * fallback handoff without a system tray. `close()` stays safe without an icon. The tray
 * path itself needs a display; 11's manual checklist covers it.
 */
class AwtTrayNotificationsTest {
    @Test
    fun aMissingIconReportsUndelivered() =
        runTest {
            val channel = AwtTrayNotifications(icon = { null })

            val delivered = channel.post(notification())

            assertThat(delivered).isFalse()
            channel.close()
        }

    private fun notification() =
        DesktopNotification(
            id = "download-failed",
            kind = NotificationKind.DOWNLOAD_FAILED,
            title = "title",
            body = "body",
            route = null,
        )
}
