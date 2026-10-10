// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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
    fun crossSchemeDefaultPortsDroppedForIdentity() {
        // Identity is scheme-free, so both well-known ports drop regardless of the scheme used.
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("http://example.com:443/feed"))
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("https://example.com:80/feed"))
        assertEquals("example.com:8443/feed", UrlNormalizer.forIdentity("https://example.com:8443/feed"))
    }

    @Test
    fun originKeepsSchemeSpecificDefaultPorts() {
        // Credentials stay scheme-specific: only the scheme's own default port drops.
        assertEquals("http://example.com:443", UrlNormalizer.origin("http://example.com:443/feed"))
        assertEquals("https://example.com:80", UrlNormalizer.origin("https://example.com:80/feed"))
        assertEquals("http://example.com", UrlNormalizer.origin("http://example.com:80/feed"))
        assertEquals("https://example.com", UrlNormalizer.origin("https://example.com:443/feed"))
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
    fun decodedHostIsCharsetValidated() {
        // toASCII without STD3 rules passes '/' through: without the allowlist this would share
        // one identity with https://ex/evil.com/feed.
        assertNull(UrlNormalizer.forIdentity("https://ex%2Fevil.com/feed"))
        assertNull(UrlNormalizer.forIdentity("https://exa%24mple.com/feed"))
        // A "." host strips to empty: bogus, so no identity.
        assertNull(UrlNormalizer.forIdentity("http://./feed"))
        // Valid hosts still normalise: punycode, underscores that survive toASCII, mixed case.
        assertEquals("xn--tst-qla.example/feed", UrlNormalizer.forIdentity("https://täst.example/feed"))
        assertEquals("ex_ample.com/feed", UrlNormalizer.forIdentity("https://ex_ample.com/feed"))
        assertEquals("ex/evil.com/feed", UrlNormalizer.forIdentity("https://ex/evil.com/feed"))
    }

    @Test
    fun specialSchemeSkipsLeadingSlashesLikeWhatwg() {
        // For http(s) WHATWG ignores every leading `/` or `\` before the authority, so all of
        // these fetch from example.com (OkHttp agrees); non-special schemes keep `//`-only.
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("http:/example.com/feed"))
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("http:example.com/feed"))
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("http:\\\\example.com/feed"))
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("https:///example.com/feed"))
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("http://example.com/feed"))
        // A single slash on a non-special scheme is path, not authority.
        val parts = splitLenient("ftp:/example.com/feed")
        assertNull(parts.host)
        assertEquals("/example.com/feed", parts.path)
    }

    /**
     * Dot-segment removal must be an indexed linear scan: re-slicing the remaining input once per
     * segment turns `"../" * N` quadratic. A 4× input may cost at most 8× the time (linear ≈ 4×,
     * quadratic ≈ 16×) and the large run stays under a generous absolute budget.
     */
    @Test
    fun dotSegmentRemovalIsLinear() {
        // Warm up at the measured size so the small run is not the first compile-candidate.
        repeat(3) { UrlNormalizer.forIdentity(dotDoc(DOT_SMALL * 4)) }
        // Best-of-3 per size: a single noisy run (GC, shared CI CPU) must not fail the ratio.
        // The small run asserts too: its result must be computed, not optimised away. Build the
        // inputs outside the timed block: allocating the 480 KB document is not normalization work.
        val smallDoc = dotDoc(DOT_SMALL)
        val largeDoc = dotDoc(DOT_SMALL * 4)
        val small =
            (1..3).minOf {
                measureTime {
                    assertEquals("example.com/x", UrlNormalizer.forIdentity(smallDoc))
                }
            }
        val large =
            (1..3).minOf {
                measureTime {
                    assertEquals("example.com/x", UrlNormalizer.forIdentity(largeDoc))
                }
            }
        assertTrue(
            large < BUDGET,
            "dot segments took $large for ${DOT_SMALL * 4} segments (budget $BUDGET)",
        )
        assertTrue(
            large < small * QUADRATIC_SLACK,
            "dot segments took $small for $DOT_SMALL, $large for 4x — quadratic would be ~16x",
        )
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
    fun ipv6BracketResidueIsRejected() {
        // Anything after `]` that is not `:port` is invalid — the JDK's `URI` throws on the same
        // input — so the residue must not be silently dropped into an identity.
        assertNull(UrlNormalizer.forIdentity("http://[2001:db8::1]junk/feed"))
        assertNull(UrlNormalizer.forIdentity("http://[2001:db8::1]x:8080/feed"))
        assertNull(UrlNormalizer.origin("http://[2001:db8::1]junk"))
    }

    @Test
    fun ipv6LiteralHostsAreValidatedAndCanonicalised() {
        // Equivalent spellings share one identity: lowercase, leading zeros suppressed, the longest
        // leftmost zero run compressed to `::` (RFC 5952), an embedded IPv4 tail emitted as two hex
        // groups like the URL spec's IPv6 serialiser.
        assertEquals("[::1]/f", UrlNormalizer.forIdentity("http://[::1]/f"))
        assertEquals("[::1]/f", UrlNormalizer.forIdentity("http://[0:0:0:0:0:0:0:1]/f"))
        assertEquals("[::1]/f", UrlNormalizer.forIdentity("http://[0:0::0:1]/f"))
        assertEquals("[2001:db8::1]/f", UrlNormalizer.forIdentity("http://[2001:0DB8:0000:0000:0:0:0:1]/f"))
        assertEquals("[::ffff:808:808]/f", UrlNormalizer.forIdentity("http://[::ffff:8.8.8.8]/f"))
        // Tied zero runs compress the leftmost (2001:db8:0:0:1:0:0:1 → 2001:db8::1:0:0:1).
        assertEquals("[2001:db8::1:0:0:1]/f", UrlNormalizer.forIdentity("http://[2001:0db8:0:0:1:0:0:1]/f"))
        // The zone id keeps its text and case (RFC 6874); the marker canonicalises to `%25`.
        assertEquals("[fe80::1%25eth0]/f", UrlNormalizer.forIdentity("http://[fe80::1%25eth0]/f"))
        assertEquals("[fe80::1%25Eth0]/f", UrlNormalizer.forIdentity("http://[fe80::1%25Eth0]/f"))
        assertEquals("[fe80::1%25en1.10]/f", UrlNormalizer.forIdentity("http://[fe80::1%25en1.10]/f"))
    }

    @Test
    fun malformedIpv6LiteralHostsAreRejected() {
        // Full literal validation, not a character allowlist: `:::` and friends must not parse.
        assertNull(UrlNormalizer.forIdentity("http://[]/f"))
        assertNull(UrlNormalizer.forIdentity("http://[garbage]/f"))
        assertNull(UrlNormalizer.forIdentity("http://[:::]/f"))
        assertNull(UrlNormalizer.forIdentity("http://[v1.x]/f")) // IPvFuture is not supported
        assertNull(UrlNormalizer.forIdentity("http://[1:2:3:4:5:6:7:8:9]/f")) // nine groups
        assertNull(UrlNormalizer.forIdentity("http://[1:2:3:4:5:6:7]/f")) // seven groups, no `::`
        assertNull(UrlNormalizer.forIdentity("http://[1::2::3]/f")) // two `::`
        assertNull(UrlNormalizer.forIdentity("http://[12345::]/f")) // group over 4 hex digits
        assertNull(UrlNormalizer.forIdentity("http://[::ffff:256.1.1.1]/f")) // octet > 255
        assertNull(UrlNormalizer.forIdentity("http://[::ffff:01.2.3.4]/f")) // leading zero octet
        assertNull(UrlNormalizer.forIdentity("http://[1.2.3.4::]/f")) // IPv4 must be the tail
        assertNull(UrlNormalizer.forIdentity("http://[fe80::1%]/f")) // empty zone
        assertNull(UrlNormalizer.forIdentity("http://[fe80::1%25]/f")) // `%25` marker, empty zone
        assertNull(UrlNormalizer.forIdentity("http://[fe80::1%25e th0]/f")) // space in zone
        assertNull(UrlNormalizer.origin("http://[garbage]"))
    }

    @Test
    fun asciiTabCrLfAreStrippedBeforeSplitting() {
        // WHATWG removes ASCII tab/CR/LF anywhere in the input before parsing, so URLs other
        // clients fetch (stray tabs occur in pasted HTML) still get an identity here.
        assertEquals("example.com/f", UrlNormalizer.forIdentity("http://ex\tample.com/f"))
        assertEquals("example.com/f", UrlNormalizer.forIdentity("http://example.com/f\t\r\n"))
        assertEquals("example.com/f?a=b", UrlNormalizer.forIdentity("http://example.com/f\n?a=b"))
        assertEquals("example.com/f", UrlNormalizer.forIdentity("ht\ntp://example.com/f"))
    }

    @Test
    fun underscoreHostLabelsAreKept() {
        // `java.net.IDN.toASCII` (default flags) passes `_` labels through; OkHttp fetches such CDN
        // hosts, so they must keep an identity rather than failing conversion.
        assertEquals("ex_ample.example.com/f", UrlNormalizer.forIdentity("http://ex_ample.example.com/f"))
    }

    @Test
    fun outOfRangePortsAreRejected() {
        // Unfetchable URLs get no identity key: ports are 0..65535 (the JDK's `URI` accepts 65536 —
        // the splitter must not). Digit strings beyond Long never reach the range check.
        assertNull(UrlNormalizer.forIdentity("https://example.com:65536/x"))
        assertNull(UrlNormalizer.forIdentity("https://example.com:99999999999999999999/x"))
        assertNull(UrlNormalizer.origin("https://example.com:70000"))
        assertEquals("example.com:65535/x", UrlNormalizer.forIdentity("https://example.com:65535/x"))
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
    fun splitUserInfoPreservesRawUnicodeNextToEscapes() {
        // Percent escapes must not replace raw Unicode in Basic-auth credentials.
        val (url, credentials) =
            UrlNormalizer.splitUserInfo("https://user\uD83D\uDD12%40x:p\uD83D\uDD12%3Ass@feeds.example.com/show")
        assertEquals("https://feeds.example.com/show", url)
        assertEquals("user\uD83D\uDD12@x", credentials?.username)
        assertEquals("p\uD83D\uDD12:ss", credentials?.password)
    }

    @Test
    fun splitUserInfoWithoutCredentials() {
        val (url, credentials) = UrlNormalizer.splitUserInfo("https://feeds.example.com/show")
        assertEquals("https://feeds.example.com/show", url)
        assertNull(credentials)
    }

    @Test
    fun splitUserInfoFragmentEndsTheAuthority() {
        // The `#` ends the authority: text after it is never credentials and never the host.
        val (url, credentials) = UrlNormalizer.splitUserInfo("https://feeds.example.com#contact@example.com")
        assertEquals("https://feeds.example.com#contact@example.com", url)
        assertNull(credentials)
    }

    @Test
    fun splitUserInfoFragmentCannotMoveTheHost() {
        // Everything after the first `/`, `?` or `#` stays verbatim; only in-authority userinfo is split.
        val (url, credentials) =
            UrlNormalizer.splitUserInfo("https://alice:secret@trusted.example#x@evil.example")
        assertEquals("https://trusted.example#x@evil.example", url)
        assertEquals("alice", credentials?.username)
        assertEquals("secret", credentials?.password)
    }

    @Test
    fun splitUserInfoQueryEndsTheAuthority() {
        val (url, credentials) = UrlNormalizer.splitUserInfo("https://user:pass@example.com/feed?x=@evil")
        assertEquals("https://example.com/feed?x=@evil", url)
        assertEquals("user", credentials?.username)
        assertEquals("pass", credentials?.password)
    }

    @Test
    fun backslashEndsHttpAuthority() {
        // WHATWG/OkHttp parse `\` in an http(s) URL as a path separator: the authority ends there,
        // so the host before it wins and the remainder — including any `@` — is path text.
        assertEquals(
            "good.com/@evil.com/feed",
            UrlNormalizer.forIdentity("https://good.com\\@evil.com/feed"),
        )
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("https://example.com\\feed"))
        // Dot-segment removal runs on the converted path, matching the client (OkHttp 5.5.0).
        assertEquals("example.com/a/c", UrlNormalizer.forIdentity("https://example.com/a\\b/../c"))
    }

    @Test
    fun splitUserInfoCredentialsWithoutHostAreNotReturned() {
        // Credentials followed by no host must not surface at the add/persist boundary: the URL is
        // returned unchanged and no `UrlUserInfo` — so no secret can be stored against an origin
        // that does not exist.
        assertEquals("https://user:pass@/feed" to null, UrlNormalizer.splitUserInfo("https://user:pass@/feed"))
        assertEquals("https://user:pass@" to null, UrlNormalizer.splitUserInfo("https://user:pass@"))
        assertEquals(
            "https://user:pass@:8080/feed" to null,
            UrlNormalizer.splitUserInfo("https://user:pass@:8080/feed"),
        )
        // A bracketed host with a port is a real host — credentials are still split.
        val (url, credentials) = UrlNormalizer.splitUserInfo("https://user:pass@[::1]:80/feed")
        assertEquals("https://[::1]:80/feed", url)
        assertEquals("user", credentials?.username)
        assertEquals("pass", credentials?.password)
    }

    @Test
    fun splitUserInfoBackslashKeepsGenuineCredentials() {
        // Credentials before the first `\` are real: OkHttp contacts evil.com with user:pass.
        val (url, credentials) =
            UrlNormalizer.splitUserInfo("https://user:pass@evil.com\\@good.com/feed")
        assertEquals("https://evil.com\\@good.com/feed", url)
        assertEquals("user", credentials?.username)
        assertEquals("pass", credentials?.password)
    }

    @Test
    fun splitUserInfoBackslashAfterHostIsPathText() {
        // `@` after the first `\` sits in the path, so this URL carries no credentials.
        val (url, credentials) = UrlNormalizer.splitUserInfo("https://good.com\\@evil.com/feed")
        assertEquals("https://good.com\\@evil.com/feed", url)
        assertNull(credentials)
    }

    @Test
    fun urlUserInfoToStringRedactsBothParts() {
        // The generated data-class toString would print secrets into logs; 01's Redactor masks
        // both halves, so toString must too.
        val info = UrlUserInfo("alice", "secret")
        assertFalse("alice" in info.toString())
        assertFalse("secret" in info.toString())
        // A Basic-auth token can be carried as the username with an empty password.
        val tokenUser = UrlUserInfo("xKd93lskSKEa1zl4dQeF1", "")
        assertFalse("xKd93lskSKEa1zl4dQeF1" in tokenUser.toString())
        // The mirror case — empty username, secret password — masks too.
        val emptyUser = UrlUserInfo("", "secret")
        assertFalse("secret" in emptyUser.toString())
    }

    @Test
    fun unescapedSpacesSurvive() {
        // java.net.URI would throw here; the splitter is lenient. The fetcher percent-encodes a
        // raw space on the wire, so the identity encodes it too — matching `a%20b`, not a second
        // identity for the same resource.
        assertEquals("example.com/a%20b", UrlNormalizer.forIdentity("https://example.com/a b"))
        assertEquals(
            UrlNormalizer.forIdentity("https://example.com/a%20b"),
            UrlNormalizer.forIdentity("https://example.com/a b"),
        )
    }

    @Test
    fun emptyPortIsDefaultAndGarbagePortIsRejected() {
        // RFC 3986 permits an empty port (= default); the JDK and OkHttp fetch `host:` fine.
        assertEquals("example.com/feed", UrlNormalizer.forIdentity("http://example.com:/feed"))
        // …but a second colon or a non-numeric port is unfetchable: no identity.
        assertNull(UrlNormalizer.forIdentity("http://a.com:1:2/x"))
        assertNull(UrlNormalizer.forIdentity("http://:8080/x"))
        assertNull(UrlNormalizer.forIdentity("http://::1/x"))
    }

    private fun dotDoc(count: Int): String = "https://example.com/" + "../".repeat(count) + "x"

    private companion object {
        const val DOT_SMALL = 40_000
        const val QUADRATIC_SLACK = 8
        val BUDGET = 3.seconds
    }
}
