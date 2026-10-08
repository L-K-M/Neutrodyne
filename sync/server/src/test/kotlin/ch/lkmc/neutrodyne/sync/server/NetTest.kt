// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.net.Inet4Address
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Literal-only address parsing (no resolver lookups) and loopback detection. */
class NetTest {
    @Test
    fun `strict IPv4 literals parse and malformed ones fail fast`() {
        assertNotNull(IpLiterals.parse("1.2.3.4"))
        assertNotNull(IpLiterals.parse("0.0.0.0"))
        assertNotNull(IpLiterals.parse("255.255.255.255"))

        // A malformed IPv4-shaped string must not reach the resolver (InetAddress falls back
        // to a name lookup when the literal parse fails, stalling request threads).
        assertNull(IpLiterals.parse("999.1.2.3"))
        assertNull(IpLiterals.parse("1234.1.1.1"))
        assertNull(IpLiterals.parse("1.2.3"))
        assertNull(IpLiterals.parse("1.2.3.4:80"))
        assertNull(IpLiterals.parse("unknown"))
    }

    @Test
    fun `IPv6 literals parse in plain bracketed and mapped forms`() {
        assertNotNull(IpLiterals.parse("::1"))
        assertNotNull(IpLiterals.parse("[::1]"))

        // The JDK un-maps ::ffff:a.b.c.d to an Inet4Address, so mapped loopback peers still
        // count as loopback on dual-stack sockets.
        val mapped = IpLiterals.parse("::ffff:127.0.0.1")
        assertIs<Inet4Address>(mapped)
        assertTrue(mapped.isLoopbackAddress)

        // A structurally invalid IPv6-shaped string throws inside the JDK without a lookup.
        assertNull(IpLiterals.parse("1:2:3:4:5:6:7:8:9"))
    }

    @Test
    fun `loopback detection covers the name and both families`() {
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(isLoopbackHost("LOCALHOST"))
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("::1"))
        assertTrue(isLoopbackHost("::ffff:127.0.0.1"))
        assertFalse(isLoopbackHost("203.0.113.7"))
        assertFalse(isLoopbackHost("unknown"))
    }
}
