// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [bareHostOf]: the gate classifies the bare host of every accepted server-address shape —
 * names, `.local`, IPv4, bracketed IPv6, bare IPv6 literals — with and without ports, and
 * malformed authorities fail explicitly.
 */
class BareHostOfTest {
    @Test
    fun namesAndLiteralsKeepTheirHost() {
        assertEquals("sync.example", bareHostOf("https://sync.example"))
        assertEquals("nas.local", bareHostOf("https://nas.local"))
        assertEquals("192.168.1.5", bareHostOf("https://192.168.1.5"))
        assertEquals("::1", bareHostOf("https://::1"))
    }

    @Test
    fun explicitAndDefaultPortsAreDropped() {
        assertEquals("sync.example", bareHostOf("https://sync.example:443"))
        assertEquals("192.168.1.5", bareHostOf("http://192.168.1.5:8080"))
        assertEquals("nas.local", bareHostOf("https://nas.local:8443"))
        assertEquals("192.168.1.5", bareHostOf("http://192.168.1.5:8080/nd"))
        assertEquals("fe80::1", bareHostOf("https://[fe80::1]:8443"))
    }

    @Test
    fun bracketedIpv6KeepsTheLiteral() {
        assertEquals("fe80::1", bareHostOf("https://[fe80::1]"))
        assertEquals("2001:db8::5", bareHostOf("https://[2001:db8::5]:8443/prefix"))
    }

    @Test
    fun userinfoDoesNotBecomeTheHost() {
        assertEquals("sync.example", bareHostOf("https://user@sync.example:8443"))
    }

    @Test
    fun schemeCaseDoesNotDefeatTheHost() {
        assertEquals("sync.example", bareHostOf("HTTP://sync.example"))
        assertEquals("sync.example", bareHostOf("HtTpS://sync.example"))
        assertEquals("192.168.1.5", bareHostOf("HTTP://192.168.1.5:8080/nd"))
        assertEquals("fe80::1", bareHostOf("HTTPS://[fe80::1]:8443"))
    }

    @Test
    fun malformedAuthoritiesAreRejected() {
        assertNull(bareHostOf("https://"))
        assertNull(bareHostOf("https://:8080"))
        assertNull(bareHostOf("https://@"))
        assertNull(bareHostOf("https://host:"))
        assertNull(bareHostOf("https://host:abc"))
        assertNull(bareHostOf("https://[fe80::1"))
        assertNull(bareHostOf("https://[fe80::1]junk"))
        assertNull(bareHostOf("https://[fe80::1]:"))
        assertNull(bareHostOf("https://[fe80::1]:abc"))
    }
}
