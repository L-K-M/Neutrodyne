// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.DesktopOs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * Keeps the computer from idle-sleeping while an episode plays (11 Power's policy row 1).
 * Idempotent: a second [acquire] while held does nothing, a [release] with nothing held is a
 * no-op. A mechanism that cannot be taken is logged, never thrown — playback continues
 * without the inhibition (11 OS-integration failure modes).
 */
interface IdleSleepInhibitor {
    fun acquire(reason: String)

    fun release()
}

/** Picks the OS's [IdleSleepInhibitor]; `busAddress` is the private-bus test's bus. */
object IdleSleepInhibitors {
    fun create(
        os: DesktopOs?,
        busAddress: String? = null,
    ): IdleSleepInhibitor =
        when (os) {
            DesktopOs.LINUX -> LinuxIdleSleepInhibitor(busAddress)
            else -> PendingIdleSleepInhibitor
        }
}

/**
 * macOS/Windows until `ndmedia` grows `nd_power_keep_awake`: documented as pending (11
 * Power's dated note), never a silent no-op — the first [acquire] logs what is missing.
 */
object PendingIdleSleepInhibitor : IdleSleepInhibitor {
    private const val TAG = "IdleSleep"

    private val logged =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    override fun acquire(reason: String) {
        if (logged.compareAndSet(false, true)) {
            Log.i(TAG) { "idle-sleep inhibition is pending native work on this OS" }
        }
    }

    override fun release() = Unit
}

/**
 * Linux through the desktop portal's `org.freedesktop.portal.Inhibit` on the session bus:
 * `Inhibit(window, flags = 8, {reason})` returns a `Request` handle whose `Close()` releases
 * the inhibition. logind's own `Inhibit("idle", …)` cannot be used instead: it hands back a
 * unix file descriptor, and dbus-java 5.2.2's transports do not pass descriptors (the same
 * limitation 11 records for delay inhibitors) — see the dated note in 11. No portal on the
 * bus (minimal window managers) → no inhibition, logged.
 */
class LinuxIdleSleepInhibitor internal constructor(
    private val connect: () -> PortalConnection,
) : IdleSleepInhibitor,
    AutoCloseable {
    constructor(busAddress: String? = null) : this({ PortalDbusConnection(busAddress) })

    private val lock = Any()
    private var connection: PortalConnection? = null
    private var handle: AutoCloseable? = null
    private val lastFailure = MutableStateFlow<String?>(null)

    /** Null while the inhibition is held or was never needed; diagnostics keep the reason. */
    val failure: StateFlow<String?> = lastFailure

    override fun acquire(reason: String) {
        synchronized(lock) {
            if (handle != null) return
            try {
                val portal = connection ?: connect().also { connection = it }
                handle = portal.inhibit(reason)
                lastFailure.value = null
            } catch (e: Exception) {
                lastFailure.value = "idle-sleep inhibition unavailable: ${e.message}"
                Log.w(TAG, e) { "portal Inhibit failed; playing without idle-sleep inhibition" }
            }
        }
    }

    override fun release() {
        synchronized(lock) {
            val held = handle ?: return
            handle = null
            runCatching { held.close() }
                .onFailure {
                    lastFailure.value = "releasing idle-sleep inhibition failed: ${it.message}"
                    Log.w(TAG, it) { "portal Request.Close failed" }
                }
        }
    }

    override fun close() {
        synchronized(lock) {
            release()
            runCatching { connection?.close() }
                .onFailure { Log.w(TAG, it) { "portal connection close failed" } }
            connection = null
        }
    }

    /** The portal calls the inhibitor needs; a seam for the deterministic tests. */
    internal interface PortalConnection : AutoCloseable {
        /** Starts the inhibition and returns its release handle. */
        fun inhibit(reason: String): AutoCloseable
    }

    private class PortalDbusConnection(
        private val busAddress: String?,
    ) : PortalConnection {
        private val connection: DBusConnection by lazy {
            if (busAddress == null) {
                DBusConnectionBuilder.forSessionBus().build()
            } else {
                DBusConnectionBuilder.forAddress(busAddress).build()
            }
        }

        override fun inhibit(reason: String): AutoCloseable {
            val portal =
                connection.getRemoteObject(PortalNames.BUS, PortalNames.PATH, PortalInhibit::class.java)
            val handle =
                portal.Inhibit(
                    "",
                    UInt32(FLAG_IDLE.toLong()),
                    mapOf(REASON_KEY to Variant(reason)),
                )
            return AutoCloseable {
                connection
                    .getRemoteObject(PortalNames.BUS, handle.path, PortalRequest::class.java)
                    .Close()
            }
        }

        override fun close() = connection.close()
    }

    private companion object {
        const val TAG = "IdleSleep"

        /** org.freedesktop.portal.Inhibit flags: 1 Logout, 2 User Switch, 4 Suspend, 8 Idle. */
        const val FLAG_IDLE = 8
        const val REASON_KEY = "reason"
    }
}

internal object PortalNames {
    const val BUS = "org.freedesktop.portal.Desktop"
    const val PATH = "/org/freedesktop/portal/desktop"
}

@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName("org.freedesktop.portal.Inhibit")
internal interface PortalInhibit : DBusInterface {
    /** v3 signature: `Inhibit(window s, flags u, options a{sv}) → handle o`. */
    fun Inhibit(
        window: String,
        flags: UInt32,
        options: Map<String, Variant<*>>,
    ): DBusPath
}

@Suppress("ktlint:standard:function-naming", "FunctionName")
@DBusInterfaceName("org.freedesktop.portal.Request")
internal interface PortalRequest : DBusInterface {
    fun Close()

    /**
     * `Response(u response, a{sv} results)` — every portal request's answer, emitted on the
     * request handle's object path. Nested here (dbus-java derives a signal's interface and
     * member names from the enclosing type and this class's name); used by `DbusDesktopPortal`.
     */
    class Response(
        path: String,
        response: UInt32,
        results: Map<String, Variant<*>>,
    ) : DBusSignal(path, response, results)
}
