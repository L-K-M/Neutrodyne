// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import ch.lkmc.neutrodyne.feeds.identity.LocalDestination.isLocal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The add pipeline's local-destination classes (03 implementation note 2026-11-02). */
class LocalDestinationTest {
    @Test
    fun loopbackAndLanNamesAreLocal() {
        assertTrue(isLocal("http://localhost/feed"))
        assertTrue(isLocal("http://localhost:8080/feed"))
        assertTrue(isLocal("https://printer.localhost/"))
        assertTrue(isLocal("http://router.local/setup"))
        assertTrue(isLocal("http://nas.local.:8080/feed"))
        assertTrue(isLocal("http://user:pass@localhost/feed"))
    }

    @Test
    fun localIpv4RangesAreLocal() {
        for (
        url in listOf(
            "http://0.0.0.1/",
            "http://0.255.255.254/",
            "http://10.0.0.1/feed",
            "http://10.255.255.255/",
            "http://100.64.0.1/",
            "http://100.127.255.254/",
            "http://127.0.0.1:8080/feed",
            "http://127.255.0.1/",
            "http://169.254.0.1/",
            "http://169.254.255.254/",
            "http://172.16.0.1/",
            "http://172.31.255.254/",
            "http://192.168.1.1/action",
            "http://192.168.255.254/",
        )
        ) {
            assertTrue(isLocal(url), "expected local: $url")
        }
    }

    @Test
    fun localIpv6AndMappedFormsAreLocal() {
        for (
        url in listOf(
            "http://[::1]/",
            "http://[::]/",
            "http://[0:0:0:0:0:0:0:1]/feed",
            "http://[fc00::1]/",
            "http://[fd12:3456::1]/",
            "http://[fe80::1]/",
            "http://[febf::255]/",
            "http://[fe80::1%25eth0]/",
            "http://[::ffff:127.0.0.1]/feed",
            "http://[::ffff:7f00:1]/",
            "http://[::ffff:10.0.0.1]/",
            "http://[::ffff:192.168.1.1]:8080/",
        )
        ) {
            assertTrue(isLocal(url), "expected local: $url")
        }
    }

    @Test
    fun compatibleIpv6UsesTheEmbeddedIpv4Ranges() {
        for (host in listOf(
            "::127.0.0.1",
            "::10.1.2.3",
            "::172.16.1.2",
            "::192.168.1.2",
            "::100.64.0.1",
            "::169.254.1.2",
            "::0.0.0.2",
            "::7f00:1",
        )) {
            assertTrue(isLocal("http://[$host]/feed"), host)
        }
        for (host in listOf("::8.8.8.8", "::100.128.0.1", "::172.32.0.1", "::192.169.0.1", "0:0:0:0:1:0:7f00:1")) {
            assertFalse(isLocal("http://[$host]/feed"), host)
        }
    }

    @Test
    fun scopedIpv6LiteralsAreLocalWithoutInterfaceLookup() {
        for (zone in listOf("25eth0", "eth0", "2542", "42", "25no-such-interface")) {
            assertTrue(isLocal("http://[fe80::1%$zone]/feed"), zone)
        }
    }

    @Test
    fun multicastBroadcastAndReservedAddressesAreLocal() {
        for (host in listOf("224.0.0.0", "239.255.255.255", "240.0.0.0", "255.255.255.254", "255.255.255.255")) {
            for (literal in listOf(host, "[::$host]", "[::ffff:$host]")) {
                assertTrue(isLocal("http://$literal/feed"), literal)
            }
        }
        for (host in listOf("ff00::1", "ff02::1", "ffff::1")) {
            assertTrue(isLocal("http://[$host]/feed"), host)
        }
        assertFalse(isLocal("http://223.255.255.255/feed"))
    }

    @Test
    fun numericShorthandHostsAreLocal() {
        // Resolvers read these as IP addresses; the spelling cannot be verified, so they refuse.
        for (
        url in listOf(
            "http://2130706433/feed",
            "http://0x7f000001/feed",
            "http://0x7f.0.0.1/feed",
            "http://127.1/feed",
            "http://10.1.2/feed",
            "http://0177.0.0.1/feed",
            "http://1.2.3.4.5/feed",
        )
        ) {
            assertTrue(isLocal(url), "expected local: $url")
        }
    }

    @Test
    fun oversizedNumericPartsUseTheConservativeRawFallback() {
        for (host in listOf("99999999999.0.0.1", "1.99999999999.0.1", "1.0.99999999999.1", "1.0.0.99999999999")) {
            val url = "http://$host/feed"
            assertNull(UrlNormalizer.transportHost(url), host)
            assertTrue(isLocal(url), host)
        }
    }

    @Test
    fun normalizedNumericHostsContainOnlyBoundedOctets() {
        for (host in listOf("127.1", "2130706433", "4294967295", "255.255.255.255", "%31%32%37.1", "１２７.１")) {
            val normalized = UrlNormalizer.transportHost("http://$host/feed")!!
            val parts = normalized.split('.')
            assertEquals(4, parts.size, host)
            assertTrue(parts.all { it.toIntOrNull() in 0..255 }, host)
        }
    }

    @Test
    fun encodedAndIdnaHostsAreLocal() {
        // The transport decodes and IDNA-maps the host before it dials (03 note 2026-11-02):
        // these spellings reach loopback/RFC 1918 peers exactly like their plain forms, so the
        // string check must classify them the same way.
        for (
        url in listOf(
            "http://%31%32%37.0.0.1:8080/feed",
            "http://%31%32%37.0.0.1/",
            "http://local%68ost/feed",
            "http://LOCA%4CHOST:8080/",
            "http://192.168.%30.%31/",
            "http://１２７.０.０.１/feed",
            "http://１２７．０．０．１/feed",
            "http://ｌｏｃａｌｈｏｓｔ/feed",
            "http://１９２.１６８.０.１/action",
            "http://user:pass@%31%32%37.0.0.1/feed",
        )
        ) {
            assertTrue(isLocal(url), "expected local: $url")
        }
    }

    @Test
    fun nonCanonicalHttpAuthoritiesAreLocal() {
        for (prefix in listOf("http:/", "http:\\", "http:\\\\", "http:", "https:/")) {
            assertTrue(isLocal("${prefix}127.0.0.1/feed"), "expected local: $prefix")
        }
    }

    @Test
    fun encodedPublicHostsAreNotLocal() {
        for (
        url in listOf(
            "https://%65xample.com/feed",
            "http://127.0.0.1.e%76il.com/",
            "https://podcasts.%61pple.com/show",
        )
        ) {
            assertFalse(isLocal(url), "expected non-local: $url")
        }
    }

    @Test
    fun publicDestinationsAreNotLocal() {
        for (
        url in listOf(
            "https://example.com/feed",
            "https://podcasts.apple.com/show",
            "https://local.example.com/",
            "https://127.0.0.1.evil.com/feed",
            "http://192.168.1.1.evil.com/",
            "http://8.8.8.8/dns",
            "http://11.0.0.1/",
            "http://100.63.255.255/",
            "http://100.128.0.1/",
            "http://128.0.0.1/",
            "http://169.253.255.255/",
            "http://172.15.0.1/",
            "http://172.32.0.1/",
            "http://192.167.1.1/",
            "http://192.169.1.1/",
            "http://[2001:db8::1]/feed",
            "http://[feff::1]/",
            "http://[fec0::1]/",
            "http://[64:ff9b::808:808]/",
        )
        ) {
            assertFalse(isLocal(url), "expected non-local: $url")
        }
    }

    @Test
    fun nonAbsoluteInputIsNotLocal() {
        assertFalse(isLocal("192.168.1.1/feed"))
        assertFalse(isLocal("localhost/feed"))
        assertFalse(isLocal(""))
        assertFalse(isLocal("not a url"))
    }
}
