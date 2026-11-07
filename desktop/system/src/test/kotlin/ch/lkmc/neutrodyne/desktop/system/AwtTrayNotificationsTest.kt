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

    @Test
    fun aFailedMessageRemovesTheIconItAdded() =
        runTest {
            val tray = RecordingTray()
            val channel = AwtTrayNotifications(icon = { iconImage() }, tray = tray)

            val delivered = channel.post(notification())

            assertThat(delivered).isFalse()
            assertThat(tray.added).isEqualTo(1)
            assertThat(tray.removed).isEqualTo(1)
            channel.close()
        }

    private fun iconImage() = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)

    /** The tray always shows its icon but its balloon throws. */
    private class RecordingTray : TrayBridge {
        var added = 0
            private set

        var removed = 0
            private set

        override fun supported(): Boolean = true

        override fun add(image: java.awt.Image): TrayIconRef {
            added++
            return object : TrayIconRef {}
        }

        override fun message(
            icon: TrayIconRef,
            title: String,
            body: String,
        ): Unit = throw IllegalStateException("balloon failed")

        override fun remove(icon: TrayIconRef) {
            removed++
        }
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
