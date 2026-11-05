// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.PowerEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LinuxSuspendMonitorTest {
    @Test
    fun privateBusDeliversSuspendAndResumeInOrder() =
        runBlocking {
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

    @Test
    fun closeIsIdempotentAndAlwaysClosesTheEventChannel() =
        runBlocking<Unit> {
            val bus = FakeSuspendBus().apply { failRemoval = true }
            val monitor = LinuxSuspendMonitor.open(null) { bus }

            monitor.close()
            monitor.close() // idempotent: no second teardown and no rethrow

            assertEquals(1, bus.closeCalls)
            // `first()` over a closed flow ends at once ("no element"); an open, empty
            // channel would hang to the timeout instead — the old code stranded collectors
            // forever when the bus teardown threw before the channel closed.
            assertFailsWith<NoSuchElementException> {
                withTimeout(5_000) { monitor.events.first() }
            }
        }

    @Test
    fun openCleanupNeverMasksTheOriginalFailure() {
        val bus =
            FakeSuspendBus().apply {
                failAdd = IllegalStateException("logind refused the subscription")
                failClose = RuntimeException("connection close failed too")
            }

        val failure =
            assertFailsWith<IllegalStateException> {
                LinuxSuspendMonitor.open(null) { bus }
            }

        assertEquals("logind refused the subscription", failure.message)
        assertEquals(1, bus.closeCalls)
        // The failing cleanup is attached, not thrown away and not substituted.
        assertEquals(1, failure.suppressed.size)
        assertEquals("connection close failed too", failure.suppressed.single().message)
    }

    private class FakeSuspendBus : LinuxSuspendMonitor.SuspendBus {
        var closeCalls = 0
            private set

        var failAdd: Exception? = null
        var failRemoval = false
        var failClose: Exception? = null

        override fun addPrepareForSleepHandler(handler: DBusSigHandler<LogindManager.PrepareForSleep>) {
            failAdd?.let { throw it }
        }

        override fun removePrepareForSleepHandler(handler: DBusSigHandler<LogindManager.PrepareForSleep>) {
            check(!failRemoval) { "handler removal failed" }
        }

        override fun close() {
            closeCalls++
            failClose?.let { throw it }
        }
    }
}
