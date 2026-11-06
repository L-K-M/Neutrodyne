// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicLong

internal object NotificationsNames {
    const val BUS = "org.freedesktop.Notifications"
    const val PATH = "/org/freedesktop/Notifications"
    const val APP_NAME = "Neutrodyne"
    const val APP_ICON = "neutrodyne"

    /** The spec's reserved key that makes a body click invoke the action without a button. */
    const val DEFAULT_ACTION = "default"

    /** 11's table: the hint key naming our desktop entry. */
    const val DESKTOP_ENTRY_HINT = "desktop-entry"

    /** 11's table: hint `desktop-entry` = the frozen entry id (without `.desktop`). */
    const val DESKTOP_ENTRY = "ch.lkmc.neutrodyne"

    /** The label of the default action; servers do not display it. */
    const val DEFAULT_ACTION_LABEL = "Activate"

    /** The spec's "let the server decide" expire timeout. */
    const val SERVER_DEFAULT_TIMEOUT = -1

    const val CAPABILITY_ACTIONS = "actions"
}

/**
 * 11 Notifications' Linux back end: `org.freedesktop.Notifications.Notify` on the session
 * bus (app name "Neutrodyne", icon `neutrodyne`, `replaces_id` per notification id, hint
 * `desktop-entry`, the reserved `default` action, server-default expiry). A session bus
 * without a notification server (minimal window managers) reports every post undelivered,
 * so the composite falls back to in-app banners — never an error.
 *
 * The connection is made lazily by the first post; `GetCapabilities` then decides whether
 * the server advertises `actions` (GNOME does; a plain `notify-send` daemon may not) and
 * the default action key is sent only when it does — harmless either way, but the spec's
 * polite form. `ActionInvoked` on a notification of ours becomes a [clicks] emission; a
 * `NotificationClosed` for it drops the id mapping so a later repost starts fresh.
 *
 * dbus-java dispatches method replies on the caller's thread and signals on the bus
 * worker; the id maps are therefore guarded and clicks only cross a buffered channel.
 */
internal class LinuxDbusNotifications internal constructor(
    private val connect: () -> DBusConnection,
) : OsNotificationChannel {
    constructor(busAddress: String? = null) : this({ openBus(busAddress) })

    private val lock = Any()
    private var connection: DBusConnection? = null
    private var api: NotificationsApi? = null
    private var actionsSupported = false

    /** Notification id → the server's id for it (the `replaces_id` of the next post). */
    private val serverIds = HashMap<String, Long>()

    /** Server id → the notification behind it, so a click carries title, body and route. */
    private val byServerId = HashMap<Long, DesktopNotification>()

    private val clickChannel = Channel<DesktopNotification>(CLICK_BUFFER)
    override val clicks: Flow<DesktopNotification> = clickChannel.receiveAsFlow()
    override val description: String = NotificationsNames.BUS

    override suspend fun post(n: DesktopNotification): Boolean =
        withContext(Dispatchers.IO) {
            val established = synchronized(lock) { connectOrNull() } ?: return@withContext false
            try {
                val actions =
                    if (established.actionsSupported) {
                        listOf(NotificationsNames.DEFAULT_ACTION, NotificationsNames.DEFAULT_ACTION_LABEL)
                    } else {
                        emptyList()
                    }
                val replaces =
                    synchronized(lock) {
                        serverIds[n.id]?.let(::UInt32) ?: UInt32(0L)
                    }
                val serverId =
                    established.api
                        .Notify(
                            NotificationsNames.APP_NAME,
                            replaces,
                            NotificationsNames.APP_ICON,
                            n.title,
                            n.body,
                            actions,
                            mapOf(NotificationsNames.DESKTOP_ENTRY_HINT to Variant(NotificationsNames.DESKTOP_ENTRY)),
                            NotificationsNames.SERVER_DEFAULT_TIMEOUT,
                        ).toLong()
                synchronized(lock) {
                    serverIds[n.id] = serverId
                    byServerId[serverId] = n
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, e) { "Notify failed; the composite falls back to in-app banners" }
                false
            }
        }

    override fun cancel(id: String) {
        val serverId =
            synchronized(lock) {
                val closed = serverIds.remove(id) ?: return
                byServerId.remove(closed)
                closed
            }
        runCatching {
            synchronized(lock) { api }?.CloseNotification(UInt32(serverId))
        }.onFailure { Log.w(TAG, it) { "CloseNotification failed for $id" } }
    }

    override fun close() {
        val conn =
            synchronized(lock) {
                val current = connection ?: return
                connection = null
                api = null
                serverIds.clear()
                byServerId.clear()
                current
            }
        runCatching { conn.close() }
            .onFailure { Log.w(TAG, it) { "notification connection close failed" } }
        clickChannel.close()
    }

    /** First use only: connect, learn the capabilities, listen for the server's signals. */
    private fun connectOrNull(): Established? {
        connection?.let { conn ->
            val remote = api ?: return null
            return Established(conn, remote, actionsSupported)
        }
        var opened: DBusConnection? = null
        return try {
            val conn = connect()
            opened = conn
            val remote =
                conn.getRemoteObject(
                    NotificationsNames.BUS,
                    NotificationsNames.PATH,
                    NotificationsApi::class.java,
                )
            conn.addSigHandler(NotificationsApi.ActionInvoked::class.java, DBusSigHandler(::onActionInvoked))
            conn.addSigHandler(NotificationsApi.NotificationClosed::class.java, DBusSigHandler(::onNotificationClosed))
            val caps = runCatching { remote.GetCapabilities() }.getOrDefault(emptyList())
            actionsSupported = NotificationsNames.CAPABILITY_ACTIONS in caps
            api = remote
            connection = conn
            Established(conn, remote, actionsSupported)
        } catch (e: Exception) {
            runCatching { opened?.close() }
            Log.w(TAG, e) { "session bus or notification server unavailable" }
            null
        }
    }

    private fun onActionInvoked(signal: NotificationsApi.ActionInvoked) {
        val id = (signal.parameters?.getOrNull(ID_PARAM) as? UInt32)?.toLong() ?: return
        val action = signal.parameters?.getOrNull(ACTION_PARAM) as? String ?: return
        if (action != NotificationsNames.DEFAULT_ACTION) return
        val notification =
            synchronized(lock) { byServerId[id] } ?: return
        clickChannel.trySend(notification)
    }

    private fun onNotificationClosed(signal: NotificationsApi.NotificationClosed) {
        val id = (signal.parameters?.getOrNull(ID_PARAM) as? UInt32)?.toLong() ?: return
        synchronized(lock) {
            byServerId.remove(id)
            serverIds.entries.removeIf { it.value == id }
        }
    }

    /** The connected state handed out under [lock] while the post runs outside it. */
    private class Established(
        @Suppress("unused") val connection: DBusConnection,
        val api: NotificationsApi,
        val actionsSupported: Boolean,
    )

    internal companion object {
        const val TAG = "OsNotifier"
        private const val CLICK_BUFFER = 16
        private const val ID_PARAM = 0
        private const val ACTION_PARAM = 1

        fun openBus(busAddress: String?): DBusConnection =
            if (busAddress == null) {
                DBusConnectionBuilder.forSessionBus().build()
            } else {
                DBusConnectionBuilder.forAddress(busAddress).build()
            }
    }
}

/** The notification spec's `Notify` surface as dbus-java sees it (member names verbatim). */
@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName(NotificationsNames.BUS)
internal interface NotificationsApi : DBusInterface {
    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, Variant<*>>,
        expireTimeout: Int,
    ): UInt32

    fun CloseNotification(id: UInt32)

    fun GetCapabilities(): List<String>

    /**
     * `ActionInvoked(id u, action_key s)` — the default action is a body click. Nested
     * here because dbus-java derives a signal's interface and member names from the
     * enclosing `DBusInterface` type and this class's name; only the body's arguments
     * go to the constructor.
     */
    class ActionInvoked(
        path: String,
        id: UInt32,
        actionKey: String,
    ) : DBusSignal(path, id, actionKey)

    /** `NotificationClosed(id u, reason u)` — a closed notification's mapping dies with it. */
    class NotificationClosed(
        path: String,
        id: UInt32,
        reason: UInt32,
    ) : DBusSignal(path, id, reason)
}
