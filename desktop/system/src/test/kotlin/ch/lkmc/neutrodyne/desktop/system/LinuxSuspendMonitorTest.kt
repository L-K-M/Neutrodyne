// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.PowerEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxSuspendMonitorTest {
    @Test
    fun privateBusDeliversSuspendAndResumeInOrder() = runBlocking {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        assumeTrue("a private Linux session bus is required", address?.startsWith("unix:") == true)
        LinuxSuspendMonitor.open(address).use { monitor ->
            DBusConnectionBuilder.forAddress(address).build().use { emitter ->
                val events = async { withTimeout(5_000) { monitor.events.take(2).toList() } }
                emitter.sendMessage(LogindManager.PrepareForSleep("/org/freedesktop/login1", true))
                emitter.sendMessage(LogindManager.PrepareForSleep("/org/freedesktop/login1", false))
                assertEquals(listOf(PowerEvent.Suspending, PowerEvent.Resumed), events.await())
                assertNull(monitor.failure.value)
            }
        }
    }
}
