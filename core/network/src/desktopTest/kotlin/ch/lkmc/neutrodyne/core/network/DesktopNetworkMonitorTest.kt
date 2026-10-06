// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.PowerEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * `DesktopNetworkMonitor` (01 Testing): interface-source fakes — global addresses connect,
 * loopback/link-local-only means disconnected, metering is always false, the VPN name heuristics
 * hold, a resume re-checks immediately, and the 15 s poll ticks on virtual time. The monitor never
 * opens a socket: only the source is consulted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopNetworkMonitorTest {
    private val connected = listOf(ndInterface("eth0", addresses = arrayOf("93.184.216.34")))
    private val privateOnly = listOf(ndInterface("eth0", addresses = arrayOf("10.0.0.2")))
    private val loopbackOnly =
        listOf(
            ndInterface("lo0", loopback = true, addresses = arrayOf("127.0.0.1")),
        )
    private val linkLocalOnly =
        listOf(
            ndInterface("eth0", addresses = arrayOf("169.254.12.34", "fe80::1")),
        )

    private fun monitor(
        source: FakeInterfaceSource,
        scope: CoroutineScope,
        power: FakePowerMonitor = FakePowerMonitor(),
    ) = DesktopNetworkMonitor(scope, power, source)

    @Test
    fun `an up interface with a routable address is connected and validated`() {
        val m = monitor(FakeInterfaceSource(connected), CoroutineScope(EmptyCoroutineContext))
        with(m.status.value) {
            assertThat(isConnected).isTrue()
            assertThat(isValidated).isTrue()
            assertThat(isMetered).isFalse()
            assertThat(isVpn).isFalse()
        }
    }

    @Test
    fun `only loopback or link-local addresses mean disconnected`() {
        for (interfaces in listOf(loopbackOnly, linkLocalOnly, emptyList())) {
            val m = monitor(FakeInterfaceSource(interfaces), CoroutineScope(EmptyCoroutineContext))
            assertThat(m.status.value.isConnected).isFalse()
            assertThat(m.status.value.isValidated).isFalse()
        }
    }

    @Test
    fun `a private-range address still counts as connected — no outbound probe exists`() {
        // The monitor only walks interfaces; 10.0.0.2 is a "non-link-local unicast" and qualifies.
        val m = monitor(FakeInterfaceSource(privateOnly), CoroutineScope(EmptyCoroutineContext))
        assertThat(m.status.value.isConnected).isTrue()
    }

    @Test
    fun `a point-to-point-only interface does not count`() {
        val interfaces =
            listOf(
                ndInterface("ppp0", pointToPoint = true, addresses = arrayOf("93.184.216.34")),
            )
        val m = monitor(FakeInterfaceSource(interfaces), CoroutineScope(EmptyCoroutineContext))
        assertThat(m.status.value.isConnected).isFalse()
    }

    @Test
    fun `vpn name heuristics`() {
        val interfaces =
            listOf(
                ndInterface("tun0", addresses = arrayOf("10.8.0.2")),
                ndInterface("eth0", addresses = arrayOf("93.184.216.34")),
            )
        val m = monitor(FakeInterfaceSource(interfaces), CoroutineScope(EmptyCoroutineContext))
        assertThat(m.status.value.isVpn).isTrue()

        for (name in listOf("utun1", "wg0", "ppp9", "tap2")) {
            val v =
                monitor(
                    FakeInterfaceSource(listOf(ndInterface(name, addresses = arrayOf("10.0.0.1")))),
                    CoroutineScope(EmptyCoroutineContext),
                )
            assertThat(v.status.value.isVpn).isTrue()
        }
        val plain = monitor(FakeInterfaceSource(connected), CoroutineScope(EmptyCoroutineContext))
        assertThat(plain.status.value.isVpn).isFalse()
    }

    @Test
    fun `initial value is seeded without a collector`() {
        // `status.value` must already be right — nothing ever has to subscribe.
        val m = monitor(FakeInterfaceSource(linkLocalOnly), CoroutineScope(EmptyCoroutineContext))
        assertThat(m.status.value.isConnected).isFalse()
    }

    @Test
    fun `a resume event triggers an immediate re-check`() =
        runTest {
            val source = FakeInterfaceSource(linkLocalOnly)
            val power = FakePowerMonitor()
            val m = monitor(source, backgroundScope, power)
            assertThat(m.status.value.isConnected).isFalse()

            // The collect must be subscribed before send — a replay=0 SharedFlow drops early events.
            runCurrent()
            source.current = connected
            power.send(PowerEvent.Resumed)
            runCurrent()

            assertThat(m.status.value.isConnected).isTrue()
        }

    @Test
    fun `the poll loop re-checks on its interval`() =
        runTest {
            val source = FakeInterfaceSource(linkLocalOnly)
            val m = monitor(source, backgroundScope, FakePowerMonitor())
            val seeded = source.queries

            source.current = connected
            advanceTimeBy(16_000)
            runCurrent()

            assertThat(m.status.value.isConnected).isTrue()
            assertThat(source.queries).isGreaterThan(seeded)
        }

    @Test
    fun `a suspending event does not trigger a re-check`() =
        runTest {
            val source = FakeInterfaceSource(connected)
            val power = FakePowerMonitor()
            val m = monitor(source, backgroundScope, power)
            val seeded = source.queries

            runCurrent()
            power.send(PowerEvent.Suspending)
            runCurrent()

            assertThat(source.queries).isEqualTo(seeded)
            assertThat(m.status.value.isConnected).isTrue()
        }
}
