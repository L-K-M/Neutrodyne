// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixed vectors of 03 URL normalisation, shared with the sync server's tests. `forIdentity` is
 * scheme-free, idempotent and identical on every runtime.
 */
class UrlNormalizerTest {
    @Test
    fun designExample() {
        assertEquals(
            "feeds.example.com/Show?a=1",
            UrlNormalizer.forIdentity("HTTPS://Feeds.Example.com:443/Show/?a=1#x"),
        )
    }

    @Test
    fun schemeFreeComparison() {
        assertEquals(
            UrlNormalizer.forIdentity("http://example.com/feed"),
            UrlNormalizer.forIdentity("https://EXAMPLE.com/feed"),
        )
    }

    @Test
    fun defaultPortsDroppedOthersKept() {
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("http://example.com:80/feed"))
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("https://example.com:443/feed"))
        assertEquals("example.com:8080/feed", UrlNormalizer.forIdentity("https://example.com:8080/feed"))
        assertEquals("example.com:8443/feed", UrlNormalizer.forIdentity("http://example.com:8443/feed"))
    }

    @Test
    fun emptyPathBecomesSlash() {
        assertEquals("example.com/", UrlNormalizer.forIdentity("https://example.com"))
        assertEquals("example.com/", UrlNormalizer.forIdentity("https://example.com?"))
    }

    @Test
    fun trailingSlashRemovedOnce() {
        assertEquals("example.com/Show", UrlNormalizer.forIdentity("https://example.com/Show/"))
        assertEquals("example.com/", UrlNormalizer.forIdentity("https://example.com//"))
        assertEquals("example.com/a/b", UrlNormalizer.forIdentity("https://example.com/a/b/"))
    }

    @Test
    fun dotSegmentsRemoved() {
        assertEquals("example.com/a/b", UrlNormalizer.forIdentity("https://example.com/a/./b"))
        assertEquals("example.com/b", UrlNormalizer.forIdentity("https://example.com/a/../b"))
        assertEquals("example.com/a", UrlNormalizer.forIdentity("https://example.com/a/b/.."))
        assertEquals("example.com/", UrlNormalizer.forIdentity("https://example.com/../.."))
    }

    @Test
    fun percentEncodingNormalised() {
        // Unreserved escapes decode; other escapes uppercase; raw characters stay.
        assertEquals("example.com/a~b", UrlNormalizer.forIdentity("https://example.com/a%7Eb"))
        assertEquals("example.com/a%2Fb", UrlNormalizer.forIdentity("https://example.com/a%2fb"))
        assertEquals("example.com/a%2Fb", UrlNormalizer.forIdentity("https://example.com/a%2Fb"))
        // A bare % without two hex digits stays literal (java.net.URI would throw).
        assertEquals("example.com/100%", UrlNormalizer.forIdentity("https://example.com/100%"))
    }

    @Test
    fun queryKeptVerbatimInOrder() {
        assertEquals("example.com/p?a=1&b=2", UrlNormalizer.forIdentity("https://example.com/p?a=1&b=2"))
        assertNotEquals(
            UrlNormalizer.forIdentity("https://example.com/p?b=2&a=1"),
            UrlNormalizer.forIdentity("https://example.com/p?a=1&b=2"),
        )
        assertEquals("example.com/p", UrlNormalizer.forIdentity("https://example.com/p?"))
    }

    @Test
    fun noQueryVariant() {
        assertEquals("example.com/p", UrlNormalizer.forIdentityNoQuery("https://example.com/p?a=1&b=2"))
    }

    @Test
    fun userinfoAndFragmentDropped() {
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("https://user:pass@example.com/feed#frag"))
    }

    @Test
    fun idnaHostsAndTrailingDot() {
        // IDNA toASCII (java.net.IDN on both JVM targets, IDNA2003 mapping).
        assertEquals("xn--mnchen-3ya.de/feed", UrlNormalizer.forIdentity("https://MÜNCHEN.de/feed"))
        assertEquals("fass.de/feed", UrlNormalizer.forIdentity("https://faß.de/feed"))
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("https://example.com./feed"))
    }

    @Test
    fun idempotent() {
        val url = "HTTPS://Feeds.Example.com:443/Show/../Show/?a=1#x"
        val once = UrlNormalizer.forIdentity(url)
        assertEquals(once, UrlNormalizer.forIdentity("https://" + once))
    }

    @Test
    fun nonHttpIsRejected() {
        assertNull(UrlNormalizer.forIdentity("ftp://example.com/feed"))
        assertNull(UrlNormalizer.forIdentity("file:///etc/passwd"))
        assertNull(UrlNormalizer.forIdentity("example.com/feed"))
        assertNull(UrlNormalizer.forIdentity(""))
        assertNull(UrlNormalizer.forIdentity("javascript:alert(1)"))
    }

    @Test
    fun ipv6HostKeepsBrackets() {
        assertEquals("[2001:db8::1]/feed", UrlNormalizer.forIdentity("http://[2001:DB8::1]:80/feed"))
        assertEquals("[2001:db8::1]:8080/feed", UrlNormalizer.forIdentity("http://[2001:db8::1]:8080/feed"))
    }

    @Test
    fun originForm() {
        assertEquals("https://example.com", UrlNormalizer.origin("https://example.com:443/feed?a=1"))
        assertEquals("http://example.com:8080", UrlNormalizer.origin("http://example.com:8080/feed"))
        assertNull(UrlNormalizer.origin("ftp://example.com"))
    }

    @Test
    fun splitUserInfoExtractsCredentials() {
        val (url, credentials) = UrlNormalizer.splitUserInfo("https://user%40x:p%3Ass@feeds.example.com/show")
        assertEquals("https://feeds.example.com/show", url)
        assertEquals("user@x", credentials?.username)
        assertEquals("p:ss", credentials?.password)
    }

    @Test
    fun splitUserInfoWithoutCredentials() {
        val (url, credentials) = UrlNormalizer.splitUserInfo("https://feeds.example.com/show")
        assertEquals("https://feeds.example.com/show", url)
        assertNull(credentials)
    }

    @Test
    fun unescapedSpacesSurvive() {
        // java.net.URI would throw here; the splitter is lenient.
        assertEquals("example.com/a b", UrlNormalizer.forIdentity("https://example.com/a b"))
        assertTrue(UrlNormalizer.forIdentity("https://example.com/a b")!!.startsWith("example.com/"))
    }
}
