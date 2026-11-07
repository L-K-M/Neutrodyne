// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 11 Notifications' Windows back end *of this slice*: the documented fallback of the toast
 * row — "AWT `TrayIcon.displayMessage` on a tray icon shown for 10 s" — used as the only
 * mechanism because the WinRT toast needs the native shim (`nd_notify_post`, not written
 * here; 11's dated note records the deviation). A message cannot be retracted and AWT
 * reports no click for balloons, so [cancel] is a no-op and [clicks] stays empty; without
 * a system tray the post reports undelivered and the composite falls back to banners.
 *
 * One tray icon carries every message: added by the first post and removed 10 s after the
 * last, so a burst does not flash the taskbar. AWT's tray API is internally synchronized
 * and has no EDT requirement; every failure is caught and answered with `false` — the
 * banner fallback, not a crash of the posting lane.
 */
internal class AwtTrayNotifications(
    private val icon: () -> Image?,
    private val tray: TrayBridge = AwtTrayBridge(),
) : OsNotificationChannel {
    private val lock = Any()
    private var trayIcon: TrayIconRef? = null
    private var removal: ScheduledFuture<*>? = null
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, TRAY_THREAD).apply { isDaemon = true }
        }

    override val description: String = "awt-tray"

    override val clicks: Flow<DesktopNotification> = emptyFlow()

    override suspend fun post(n: DesktopNotification): Boolean =
        synchronized(lock) {
            try {
                val current = ensureIcon() ?: return false
                try {
                    tray.message(current, n.title, n.body)
                } catch (e: Exception) {
                    // A balloon that could not post leaves its icon behind otherwise.
                    removeIcon()
                    throw e
                }
                scheduleRemoval()
                true
            } catch (e: Exception) {
                Log.w(TAG, e) { "AWT tray message failed; the composite falls back to in-app banners" }
                false
            }
        }

    /** AWT balloons have no close; a shown message lives out the OS's own timeout. */
    override fun cancel(id: String) = Unit

    override fun close() {
        synchronized(lock) {
            removal?.cancel(false)
            removeIcon()
        }
        scheduler.shutdownNow()
    }

    /** The shared icon, added on first use; `null` when this desktop has no tray. */
    private fun ensureIcon(): TrayIconRef? {
        trayIcon?.let { return it }
        if (!tray.supported()) return null
        val image = icon() ?: return null
        val created = tray.add(image)
        trayIcon = created
        return created
    }

    /** 10 s after the last message the icon goes away (11's fallback wording). */
    private fun scheduleRemoval() {
        removal?.cancel(false)
        removal =
            scheduler.schedule({
                synchronized(lock) { removeIcon() }
            }, ICON_LIFETIME_S, TimeUnit.SECONDS)
    }

    private fun removeIcon() {
        val current = trayIcon ?: return
        trayIcon = null
        runCatching { tray.remove(current) }
            .onFailure { Log.w(TAG, it) { "removing the notification tray icon failed" } }
    }

    private companion object {
        const val TAG = "OsNotifier"
        const val TRAY_THREAD = "nd-notify-tray"
        const val ICON_LIFETIME_S = 10L
    }
}

/** A tray icon a [TrayBridge] added; opaque to [AwtTrayNotifications]. */
internal interface TrayIconRef

/**
 * The AWT tray calls behind [AwtTrayNotifications], so a headless test can watch
 * add/message/remove without a display — [TrayIcon] cannot even be constructed
 * there.
 */
internal interface TrayBridge {
    fun supported(): Boolean

    fun add(image: Image): TrayIconRef

    fun message(
        icon: TrayIconRef,
        title: String,
        body: String,
    )

    fun remove(icon: TrayIconRef)
}

private class AwtTrayBridge : TrayBridge {
    private class AwtIcon(
        val icon: TrayIcon,
    ) : TrayIconRef

    override fun supported(): Boolean = SystemTray.isSupported()

    override fun add(image: Image): TrayIconRef =
        AwtIcon(TrayIcon(image, NotificationsNames.APP_NAME).apply { isImageAutoSize = true })
            .also { SystemTray.getSystemTray().add(it.icon) }

    override fun message(
        icon: TrayIconRef,
        title: String,
        body: String,
    ) {
        (icon as AwtIcon).icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }

    override fun remove(icon: TrayIconRef) {
        SystemTray.getSystemTray().remove((icon as AwtIcon).icon)
    }
}
