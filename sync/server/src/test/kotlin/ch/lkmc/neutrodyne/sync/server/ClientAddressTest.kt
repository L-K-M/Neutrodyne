// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Trusted-proxy resolution of the client address and scheme (10 Ktor setup, Request pipeline). */
class ClientAddressTest {
    @Test
    fun `a peer outside the trusted list is the client and its headers are ignored`() {
        val resolved =
            ClientAddress(IpCidr.parseAll("127.0.0.1/32")).resolve(
                peerHost = "203.0.113.7",
                forwardedFor = listOf("198.51.100.9"),
                forwardedProto = listOf("https"),
            )
        assertEquals("203.0.113.7", resolved.address)
        assertFalse(resolved.secureTransport)
    }

    @Test
    fun `a loopback peer is a trusted proxy (N13 loopback-or-trusted)`() {
        val resolved =
            ClientAddress(emptyList()).resolve(
                peerHost = "127.0.0.1",
                forwardedFor = listOf("203.0.113.7"),
                forwardedProto = listOf("https"),
            )
        assertEquals("203.0.113.7", resolved.address)
        assertTrue(resolved.secureTransport)
    }

    @Test
    fun `a trusted proxy yields the right-most untrusted address`() {
        val resolved =
            ClientAddress(IpCidr.parseAll("127.0.0.1/32")).resolve(
                peerHost = "127.0.0.1",
                forwardedFor = listOf("203.0.113.7, 127.0.0.1, 127.0.0.1"),
                forwardedProto = listOf("http"),
            )
        assertEquals("203.0.113.7", resolved.address)
        assertFalse(resolved.secureTransport)
    }

    @Test
    fun `a chain of only trusted proxies falls back to the peer`() {
        val resolved =
            ClientAddress(IpCidr.parseAll("127.0.0.1/32")).resolve(
                peerHost = "127.0.0.1",
                forwardedFor = listOf("127.0.0.1"),
                forwardedProto = emptyList(),
            )
        assertEquals("127.0.0.1", resolved.address)
        assertFalse(resolved.secureTransport)
    }

    @Test
    fun `secure transport needs X-Forwarded-Proto https`() {
        val resolver = ClientAddress(IpCidr.parseAll("::1/128"))

        val secure = resolver.resolve("::1", emptyList(), listOf("HTTPS"))
        assertTrue(secure.secureTransport)

        val plain = resolver.resolve("::1", emptyList(), listOf("http"))
        assertFalse(plain.secureTransport)
    }

    @Test
    fun `custom trusted CIDRs believe a proxy inside them`() {
        val resolved =
            ClientAddress(IpCidr.parseAll("172.31.87.0/24")).resolve(
                peerHost = "172.31.87.5",
                forwardedFor = listOf("203.0.113.7"),
                forwardedProto = listOf("https"),
            )
        assertEquals("203.0.113.7", resolved.address)
        assertTrue(resolved.secureTransport)
    }

    @Test
    fun `an unparseable peer is treated as untrusted`() {
        val resolved =
            ClientAddress(IpCidr.parseAll("127.0.0.1/32")).resolve(
                peerHost = "unknown",
                forwardedFor = listOf("203.0.113.7"),
                forwardedProto = listOf("https"),
            )
        assertEquals("unknown", resolved.address)
        assertFalse(resolved.secureTransport)
    }

    @Test
    fun `CIDR matching honours prefixes and address families`() {
        val net24 = IpCidr.parse("172.31.87.0/24")!!

        assertTrue(net24.matches(java.net.InetAddress.getByName("172.31.87.254")))
        assertFalse(net24.matches(java.net.InetAddress.getByName("172.31.88.1")))
        // An IPv6 address never matches an IPv4 CIDR.
        assertFalse(net24.matches(java.net.InetAddress.getByName("2001:db8::1")))

        val single = IpCidr.parse("10.0.0.8")!!
        assertTrue(single.matches(java.net.InetAddress.getByName("10.0.0.8")))
        assertFalse(single.matches(java.net.InetAddress.getByName("10.0.0.9")))

        assertEquals("10.0.0.8/32", single.toString())
    }
}
