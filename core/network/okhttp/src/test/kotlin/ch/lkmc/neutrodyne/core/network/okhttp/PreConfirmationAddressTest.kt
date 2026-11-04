// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.network.okhttp

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreConfirmationAddressTest {
    private val localPeer: (InetSocketAddress) -> Boolean = { isLocalPeerAddress(it.address) }

    @Test fun compatibleIpv6UsesEmbeddedIpv4Ranges() {
        for (host in LOCAL_COMPATIBLE) {
            val address = InetAddress.getByName(host)
            assertEquals(16, address.address.size)
            assertTrue(isLocalPeerAddress(address), host)
        }
        for (host in listOf("::8.8.8.8", "::100.128.0.1", "::172.32.0.1", "::192.169.0.1", "0:0:0:0:1:0:7f00:1")) {
            assertFalse(isLocalPeerAddress(InetAddress.getByName(host)), host)
        }
    }

    @Test fun compatibleIpv6LiteralsAreRefusedBeforeConnect() {
        val destinations = PreConfirmationDestinations(Dns { error("literal must not use DNS") }, localPeer)
        for (host in LOCAL_COMPATIBLE) {
            assertFailsWith<LocalNetworkUnsupportedException>(host) {
                destinations.check("http://[$host]/feed".toHttpUrl())
            }
        }
    }

    @Test fun compatibleDnsAnswersAreRefusedAtPreflightAndRouteSelection() {
        for (host in LOCAL_COMPATIBLE) assertDnsRefused(InetAddress.getByName(host))
    }

    @Test fun scopedLinkLocalDnsAnswersAreRefusedWithoutRuntimeExceptions() {
        val bytes = InetAddress.getByName("fe80::1").address
        for (scope in listOf(1, 42)) {
            val address = Inet6Address.getByAddress(null, bytes, scope)
            assertEquals(scope, address.scopeId)
            assertNull(address.scopedInterface)
            assertTrue(isLocalPeerAddress(address))
            assertDnsRefused(address)
        }
    }

    @Test fun scopedIpv6LiteralsCannotReachALocalDestination() {
        // HttpUrl rejects zone markers. A percent escape can also decode into another hex
        // digit of the literal (e.g. %42 -> B); that still needs the link-local refusal.
        val destinations = PreConfirmationDestinations(Dns { error("literal must not use DNS") }, localPeer)
        for (zone in listOf("25eth0", "eth0", "2542", "42", "25no-such-interface")) {
            val parsed = "http://[fe80::1%$zone]/feed".toHttpUrlOrNull() ?: continue
            assertFailsWith<LocalNetworkUnsupportedException>(zone) { destinations.check(parsed) }
        }
    }

    @Test fun multicastBroadcastAndReservedPeersAreLocal() {
        for (address in nonUnicastAddresses()) {
            assertTrue(isLocalPeerAddress(address), address.toString())
            assertFalse(isLanAddress(address), "Unmarked platform ranges must stay unchanged: $address")
        }
        assertFalse(isLocalPeerAddress(InetAddress.getByName("223.255.255.255")))
        assertFalse(isLocalPeerAddress(InetAddress.getByName("feff::1")))
    }

    @Test fun multicastBroadcastAndReservedDnsAnswersAreRefused() {
        for (address in nonUnicastAddresses()) assertDnsRefused(address)
    }

    @Test fun multicastBroadcastAndReservedLiteralsAreRefused() {
        val destinations = PreConfirmationDestinations(Dns { error("literal must not use DNS") }, localPeer)
        for (host in NON_UNICAST_V4 + listOf("[ff00::1]", "[ff02::1]", "[ffff::1]")) {
            assertFailsWith<LocalNetworkUnsupportedException>(host) {
                destinations.check("http://$host/feed".toHttpUrl())
            }
        }
    }

    private fun nonUnicastAddresses(): List<InetAddress> =
        NON_UNICAST_V4.flatMap { host ->
            val v4 = InetAddress.getByName(host)
            val compatible = ByteArray(16).also { v4.address.copyInto(it, 12) }
            val mapped =
                compatible.copyOf().also {
                    it[10] = 0xFF.toByte()
                    it[11] = 0xFF.toByte()
                }
            listOf(v4, Inet6Address.getByAddress(null, compatible, -1), Inet6Address.getByAddress(null, mapped, -1))
        } + listOf("ff00::1", "ff02::1", "ffff::1").map(InetAddress::getByName)

    private fun assertDnsRefused(address: InetAddress) {
        val dns = Dns { listOf(InetAddress.getByName("8.8.8.8"), address) }
        assertFailsWith<LocalNetworkUnsupportedException>("preflight $address") {
            PreConfirmationDestinations(dns, localPeer).check("http://mixed.test/feed".toHttpUrl())
        }
        assertFailsWith<LocalNetworkUnsupportedException>("route $address") {
            PreConfirmationCall.protect { PreConfirmationDns(dns, localPeer).lookup("mixed.test") }
        }
        assertFalse(PreConfirmationCall.active)
    }

    private companion object {
        val NON_UNICAST_V4 = listOf("224.0.0.0", "239.255.255.255", "240.0.0.0", "255.255.255.254", "255.255.255.255")
        val LOCAL_COMPATIBLE =
            listOf(
                "::127.0.0.1",
                "::10.1.2.3",
                "::172.16.1.2",
                "::192.168.1.2",
                "::100.64.0.1",
                "::169.254.1.2",
                "::0.0.0.2",
                "::7f00:1",
            )
    }
}
