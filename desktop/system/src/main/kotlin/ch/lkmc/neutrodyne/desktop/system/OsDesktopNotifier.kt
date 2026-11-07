// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.model.DesktopOs
import kotlinx.coroutines.flow.Flow
import java.awt.Image

/**
 * 11 Notifications' [DesktopNotifier] implementation: one OS channel plus the documented
 * in-app banner fallback. Every post goes to the OS channel first; a channel that cannot
 * deliver (no notification server, no tray, a denied back end) hands the notification to
 * [fallback] — 11's "in-app banners and the Downloads screen show the same events" failure
 * row — so the owners' notifiers never need to know which path ran. [cancel] retracts on
 * both sides; an already-displayed banner, like a shown toast, cannot be recalled.
 *
 * OS clicks arrive as [clicks] carrying the whole notification (the route may be `null` —
 * 11: "a click shows the window"); the shell applies the route through `IntentRouter` on
 * the AWT thread, the same path the banner fallback's "Open" action takes. Clicks after
 * the app has quit are the OS's business (they only start the app; no route runs).
 */
class OsDesktopNotifier internal constructor(
    private val channel: OsNotificationChannel,
    private val fallback: DesktopNotifier,
) : DesktopNotifier,
    AutoCloseable {
    /** Diagnostics: which back end this notifier uses (11 Diagnostics' notification row). */
    val description: String
        get() = channel.description

    /** OS-delivered notification clicks, in arrival order; buffered until collection. */
    val clicks: Flow<DesktopNotification>
        get() = channel.clicks

    override suspend fun post(n: DesktopNotification) {
        if (!channel.post(n)) fallback.post(n)
    }

    override fun cancel(id: String) {
        channel.cancel(id)
        fallback.cancel(id)
    }

    override fun close() = channel.close()

    companion object {
        /**
         * Binds the OS channel for [os] and the documented banner [fallback] — the one
         * construction path of `DesktopAppGraph` ([busAddress] and [trayIcon] exist for
         * tests and the Windows channel).
         */
        fun create(
            os: DesktopOs?,
            fallback: DesktopNotifier,
            busAddress: String? = null,
            trayIcon: () -> Image? = { null },
        ): OsDesktopNotifier = OsDesktopNotifier(OsDesktopNotifiers.createChannel(os, busAddress, trayIcon), fallback)
    }
}

/**
 * One OS's delivery path behind [OsDesktopNotifier]. [post] reports delivery instead of
 * throwing: an unavailable back end is 11's documented normal state, not an error the
 * callers must handle. Implementations may be called from any thread.
 */
internal interface OsNotificationChannel : AutoCloseable {
    /** The mechanism this channel uses, for diagnostics. */
    val description: String

    /** Notification clicks delivered by the OS, if the mechanism reports them. */
    val clicks: Flow<DesktopNotification>

    /** Delivers [n], or reports `false` so the composite falls back to in-app banners. */
    suspend fun post(n: DesktopNotification): Boolean

    /** Retracts [id] where the OS allows it; never throws. */
    fun cancel(id: String)
}

/** Picks the OS's channel (11 Notifications' back-end table); [busAddress] is the test bus. */
internal object OsDesktopNotifiers {
    fun createChannel(
        os: DesktopOs?,
        busAddress: String? = null,
        trayIcon: () -> Image? = { null },
    ): OsNotificationChannel =
        when (os) {
            DesktopOs.LINUX -> LinuxDbusNotifications(busAddress)
            DesktopOs.WINDOWS -> AwtTrayNotifications(trayIcon)
            DesktopOs.MACOS, null -> BannerOnlyNotifications
        }
}

/**
 * macOS until the Objective-C shim grows `UNUserNotificationCenter` delivery: every post
 * reports undelivered so the composite shows the in-app banner — 11's macOS fallback row
 * ("Denied or not authorised: in-app banners only"), here pending native work rather than
 * denied (11's dated note records the deviation).
 */
internal object BannerOnlyNotifications : OsNotificationChannel {
    override val description: String = "in-app banners"

    override val clicks: Flow<DesktopNotification> = kotlinx.coroutines.flow.emptyFlow()

    override suspend fun post(n: DesktopNotification): Boolean = false

    override fun cancel(id: String) = Unit

    override fun close() = Unit
}
