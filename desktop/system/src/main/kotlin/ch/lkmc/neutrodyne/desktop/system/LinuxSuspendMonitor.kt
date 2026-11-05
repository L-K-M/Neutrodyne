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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Linux suspend/resume source of `OsPowerMonitor` (11 Power): logind's
 * `PrepareForSleep` signal on the system bus. No delay inhibitor is held — dbus-java's
 * native-unixsocket transport cannot pass the file descriptor `Inhibit` returns, so a wake
 * notice that arrives without one is simply forwarded.
 *
 * [close] is idempotent and exception-safe: every bus step is isolated and the event channel
 * always closes, so a failing handler removal or connection teardown can neither throw to the
 * shutdown path nor strand collectors. [open]'s cleanup never masks the original failure —
 * a close that throws while unwinding is attached as a suppressed exception.
 */
class LinuxSuspendMonitor private constructor(
    private val bus: SuspendBus,
) : PowerMonitor,
    AutoCloseable {
    private val channel = Channel<PowerEvent>(EVENT_BUFFER)
    override val events = channel.receiveAsFlow()
    private val lastFailure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = lastFailure
    private val closed = AtomicBoolean(false)
    private val handler =
        DBusSigHandler<LogindManager.PrepareForSleep> { signal ->
            if (closed.get()) return@DBusSigHandler
            val event = if (signal.sleeping) PowerEvent.Suspending else PowerEvent.Resumed
            if (channel.trySend(event).isFailure) lastFailure.value = "logind event queue is full or closed"
        }

    init {
        bus.addPrepareForSleepHandler(handler)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Isolated steps: a throwing teardown must still close the channel below.
        runCatching { bus.removePrepareForSleepHandler(handler) }
            .onFailure { lastFailure.value = "logind handler removal failed: $it" }
        runCatching { bus.close() }
            .onFailure { lastFailure.value = "logind connection close failed: $it" }
        channel.close()
    }

    /** The system-bus surface [LinuxSuspendMonitor] needs; a seam for the failure tests. */
    internal interface SuspendBus : AutoCloseable {
        fun addPrepareForSleepHandler(handler: DBusSigHandler<LogindManager.PrepareForSleep>)

        fun removePrepareForSleepHandler(handler: DBusSigHandler<LogindManager.PrepareForSleep>)
    }

    private class DbusSuspendBus(
        private val connection: DBusConnection,
    ) : SuspendBus {
        override fun addPrepareForSleepHandler(handler: DBusSigHandler<LogindManager.PrepareForSleep>) {
            connection.addSigHandler(LogindManager.PrepareForSleep::class.java, handler)
        }

        override fun removePrepareForSleepHandler(handler: DBusSigHandler<LogindManager.PrepareForSleep>) {
            connection.removeSigHandler(LogindManager.PrepareForSleep::class.java, handler)
        }

        override fun close() = connection.close()
    }

    companion object {
        private const val EVENT_BUFFER = 64

        /** Connects to the system bus, or to the bus at [busAddress] (the private-bus tests). */
        fun open(busAddress: String? = null): LinuxSuspendMonitor = open(busAddress, ::connect)

        internal fun open(
            busAddress: String?,
            connect: (String?) -> SuspendBus,
        ): LinuxSuspendMonitor {
            val bus = connect(busAddress)
            try {
                return LinuxSuspendMonitor(bus)
            } catch (failure: Exception) {
                // The original failure is the answer; a failing close must not replace it.
                runCatching { bus.close() }.onFailure(failure::addSuppressed)
                throw failure
            }
        }

        private fun connect(busAddress: String?): SuspendBus =
            DbusSuspendBus(
                if (busAddress == null) {
                    DBusConnectionBuilder.forSystemBus().build()
                } else {
                    DBusConnectionBuilder.forAddress(busAddress).build()
                },
            )
    }
}

@DBusInterfaceName("org.freedesktop.login1.Manager")
internal interface LogindManager : DBusInterface {
    class PrepareForSleep(
        path: String,
        val sleeping: Boolean,
    ) : DBusSignal(path, sleeping)
}
