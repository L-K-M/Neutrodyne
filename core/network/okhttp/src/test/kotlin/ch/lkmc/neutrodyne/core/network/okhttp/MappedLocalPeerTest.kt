// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.model.PRE_CONFIRMATION_HEADER
import okhttp3.Dns
import okhttp3.Request
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MappedLocalPeerTest {
    @Test fun mappedLocalDnsAnswersOpenNoSocket() = assertDnsRefused(DnsPhase.PREFLIGHT)

    @Test fun mappedLocalRouteAnswersOpenNoSocket() = assertDnsRefused(DnsPhase.ROUTE)

    private fun assertDnsRefused(phase: DnsPhase) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { recorder ->
            for (address in LOCAL_V4.map(::mapped) + listOf(rawV6(0), rawV6(1))) {
                val lookups = AtomicInteger()
                val clients =
                    newNetworkClients(
                        baseDns =
                            Dns {
                                val first = lookups.getAndIncrement() == 0
                                listOf(
                                    if (phase == DnsPhase.ROUTE && first) {
                                        InetAddress.getByName("203.0.113.1")
                                    } else {
                                        address
                                    },
                                )
                            },
                    )
                for (client in listOf(clients.feed, clients.image)) {
                    lookups.set(0)
                    val request =
                        Request
                            .Builder()
                            .url("http://mapped.test:${recorder.localPort}/action")
                            .header(PRE_CONFIRMATION_HEADER, "1")
                            .build()
                    assertFailsWith<LocalNetworkUnsupportedException>("$phase $address") {
                        client
                            .newBuilder()
                            .callTimeout(500, TimeUnit.MILLISECONDS)
                            .build()
                            .newCall(request)
                            .execute()
                            .use { }
                    }
                    recorder.soTimeout = 100
                    assertFailsWith<SocketTimeoutException> { recorder.accept().use { } }
                }
            }
        }
    }

    @Test fun rawMappedAddressesUseTheEmbeddedIpv4Ranges() {
        for (address in LOCAL_V4) assertTrue(isLocalPeerAddress(mapped(address)), address)
        for (address in listOf("10.1.2.3", "172.16.1.2", "192.168.1.2", "169.254.1.2")) {
            assertTrue(isLanAddress(mapped(address)), address)
        }
        for (address in listOf("8.8.8.8", "100.128.0.1", "172.32.0.1", "192.169.0.1")) {
            assertFalse(isLocalPeerAddress(mapped(address)), address)
            assertFalse(isLanAddress(mapped(address)), address)
        }
        for (lastByte in listOf(0, 1)) {
            val address = rawV6(lastByte)
            assertTrue(isLocalPeerAddress(address), address.toString())
            assertFalse(isLanAddress(address), address.toString())
        }
    }

    private fun rawV6(lastByte: Int): Inet6Address =
        Inet6Address.getByAddress(null, ByteArray(16).also { it[15] = lastByte.toByte() }, -1)

    private fun mapped(v4: String): Inet6Address {
        val bytes = ByteArray(16)
        bytes[10] = 0xFF.toByte()
        bytes[11] = 0xFF.toByte()
        v4.split('.').forEachIndexed { index, part -> bytes[12 + index] = part.toInt().toByte() }
        // getByName and InetAddress.getByAddress collapse mapped IPv6 into Inet4Address.
        return Inet6Address.getByAddress(null, bytes, -1).also { assertEquals(16, it.address.size) }
    }

    private enum class DnsPhase { PREFLIGHT, ROUTE }

    private companion object {
        val LOCAL_V4 =
            listOf("127.0.0.1", "10.1.2.3", "172.16.1.2", "192.168.1.2", "100.64.0.1", "169.254.1.2", "0.0.0.0")
    }
}
