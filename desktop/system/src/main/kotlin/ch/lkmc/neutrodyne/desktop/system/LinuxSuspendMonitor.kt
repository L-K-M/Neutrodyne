// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal

/** MD0 logind prototype. A private bus tests transport; real suspend ordering stays open. */
class LinuxSuspendMonitor private constructor(
    private val connection: DBusConnection,
) : PowerMonitor, AutoCloseable {
    private val channel = Channel<PowerEvent>(64)
    override val events = channel.receiveAsFlow()
    private val lastFailure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = lastFailure
    private val handler = DBusSigHandler<LogindManager.PrepareForSleep> { signal ->
        val event = if (signal.sleeping) PowerEvent.Suspending else PowerEvent.Resumed
        if (channel.trySend(event).isFailure) lastFailure.value = "logind event queue is full or closed"
    }

    init {
        connection.addSigHandler(LogindManager.PrepareForSleep::class.java, handler)
    }

    override fun close() {
        connection.removeSigHandler(LogindManager.PrepareForSleep::class.java, handler)
        connection.close()
        channel.close()
    }

    companion object {
        fun open(busAddress: String? = null): LinuxSuspendMonitor {
            val connection = if (busAddress == null) {
                DBusConnectionBuilder.forSystemBus().build()
            } else {
                DBusConnectionBuilder.forAddress(busAddress).build()
            }
            try {
                return LinuxSuspendMonitor(connection)
            } catch (failure: Exception) {
                connection.close()
                throw failure
            }
        }
    }
}

@DBusInterfaceName("org.freedesktop.login1.Manager")
internal interface LogindManager : DBusInterface {
    class PrepareForSleep(path: String, val sleeping: Boolean) : DBusSignal(path, sleeping)
}
