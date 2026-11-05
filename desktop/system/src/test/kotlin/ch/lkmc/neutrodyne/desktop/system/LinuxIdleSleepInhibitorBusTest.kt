// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Linux idle-sleep inhibition over a real session bus: a fake
 * `org.freedesktop.portal.Desktop` exported on `dbus-run-session`'s private bus proves the
 * D-Bus mapping — `Inhibit` carries flag 8 (Idle) and the reason vardict, the returned
 * `Request` handle is closed on release, and a bus without the portal logs instead of
 * throwing (11 OS-integration failure modes). The transport's lack of unix-fd passing is why
 * the portal is used at all; logind `Inhibit` cannot be held (11's dated note).
 */
class LinuxIdleSleepInhibitorBusTest {
    @Test(timeout = 30_000)
    fun portalInhibitHoldsAndRequestCloseReleases() {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        assumeTrue("a private Linux session bus is required", address?.startsWith("unix:") == true)

        DBusConnectionBuilder.forAddress(address).build().use { bus ->
            val portal = FakePortal(bus)
            bus.exportObject(PortalNames.PATH, portal)
            bus.requestBusName(PortalNames.BUS)

            val inhibitor = LinuxIdleSleepInhibitor(address)
            inhibitor.use {
                it.acquire("Neutrodyne is playing")

                await("Inhibit call") { portal.calls.isNotEmpty() }
                val call = portal.calls.single()
                assertThat(call.flags).isEqualTo(UInt32(8L))
                @Suppress("UNCHECKED_CAST")
                val options = call.options as Map<String, Variant<*>>
                assertThat(options.getValue("reason").value).isEqualTo("Neutrodyne is playing")

                it.release()
                await("Request.Close") { portal.closedHandles.get() == 1 }
            }
        }
    }

    @Test(timeout = 30_000)
    fun aBusWithoutThePortalIsLoggedNotThrown() =
        runBlocking {
            val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
            assumeTrue("a private Linux session bus is required", address?.startsWith("unix:") == true)

            // No fake portal exported: the remote object does not exist on this bus.
            LinuxIdleSleepInhibitor(address).use { inhibitor ->
                inhibitor.acquire("Neutrodyne is playing") // must not throw

                withTimeout(5_000) {
                    while (inhibitor.failure.value == null) kotlinx.coroutines.delay(10)
                }
                assertThat(inhibitor.failure.value).contains("idle-sleep inhibition unavailable")
            }
        }

    private fun await(
        what: String,
        until: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 5_000L * 1_000_000
        while (!until() && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(until()).isTrue()
    }

    private class InhibitCall(
        val flags: UInt32,
        val options: Map<String, Variant<*>>,
    )

    /** The fake portal exports a fresh Request object at each returned handle path. */
    private class FakePortal(
        private val bus: DBusConnection,
    ) : PortalInhibit {
        val calls = ConcurrentLinkedQueue<InhibitCall>()
        val closedHandles = java.util.concurrent.atomic.AtomicInteger(0)

        private var nextHandle = 0

        override fun getObjectPath() = PortalNames.PATH

        override fun Inhibit(
            window: String,
            flags: UInt32,
            options: Map<String, Variant<*>>,
        ): DBusPath {
            calls += InhibitCall(flags, options)
            val path = "${PortalNames.PATH}/request/${nextHandle++}"
            bus.exportObject(path, FakeRequest(path, closedHandles))
            return DBusPath(path)
        }
    }

    private class FakeRequest(
        private val path: String,
        private val closedHandles: java.util.concurrent.atomic.AtomicInteger,
    ) : PortalRequest {
        override fun getObjectPath() = path

        override fun Close() {
            closedHandles.incrementAndGet()
        }
    }
}
