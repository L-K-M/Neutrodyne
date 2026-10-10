// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The private-feed heuristic of 03 Private feed URLs. */
class PrivateFeedUrlsTest {
    @Test
    fun userinfoMarksPrivate() {
        assertTrue(PrivateFeedUrls.looksPrivate("https://user:pass@feeds.example.com/feed.xml"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://token@feeds.example.com/feed.xml"))
    }

    @Test
    fun tokenQueryParameterNames() {
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?token=short"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?AUTH=short"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?api_key=short"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?x=1&signature=abc"))
        // `_`-joined forms of the credential cores count too.
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?auth_token=short"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?feed_token=short"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?token_id=1"))
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?category=news"))
        // Near-miss names must not trip a substring match on token/auth/key: the `_` boundary
        // is required, so "author", "tokenize", "keyword", "monkey" stay public.
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?author=Jane"))
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?tokenize=1"))
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?keyword=music"))
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?monkey=1"))
        // Generic `_`-joined names outside the credential cores stay public too.
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?user_id=42"))
    }

    @Test
    fun longTokensInPathsAndValues() {
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/xKd93lskSKEa1zl4dQeF1/rss"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?user=a1b2c3d4e5f6g7h8i9j0k1l2"))
        // No digit: not a token.
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/abcdefghijklmnopqrstuvwxyz"))
        // Too short: not a token. The boundary is 20 chars: 19 below, 21 in the first vector above.
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/abc123def456"))
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/xKd93lskSKEa1zl4dQe"))
        // Exactly 20 chars with letters and digits is a token.
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/xKd93lskSKEa1zl4dQeF"))
        // A missing host (scheme-less or malformed input) does not skip the token checks.
        assertTrue(PrivateFeedUrls.looksPrivate("feeds.example.com/xKd93lskSKEa1zl4dQeF1"))
    }

    @Test
    fun pathTokensIncludeUuidsAndBase64Url() {
        // Self-hosted token feeds put a UUID or base64url token in the path; both carry '-'
        // or '_' separators, so the permissive class must apply to path segments too.
        assertTrue(
            PrivateFeedUrls.looksPrivate("https://selfhosted.example.com/feeds/550e8400-e29b-41d4-a716-446655440000"),
        )
        assertTrue(PrivateFeedUrls.looksPrivate("https://selfhosted.example.com/feeds/xKd93lskSKEa1zl4-dQeF1/"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://selfhosted.example.com/feeds/Ab3_xKd93lskSKEa1zl4dQeF1"))
        // A long hyphenated slug containing digits is an accepted false positive: the result
        // only feeds a warning (05 export, 10 link disclosure).
        assertTrue(PrivateFeedUrls.looksPrivate("https://example.com/feeds/my-podcast-episode-123-rss/"))
        // Short or digit-free slugs do not match the token shape and stay public.
        assertFalse(PrivateFeedUrls.looksPrivate("https://example.com/podcast/the-show-2025/feed"))
        assertFalse(PrivateFeedUrls.looksPrivate("https://example.com/feeds/my-podcast-episode-archive/"))
    }

    @Test
    fun privateHosts() {
        assertTrue(PrivateFeedUrls.looksPrivate("https://patreon.com/rss/xyz"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://rss.patreon.com/xyz"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://example.supercast.tech/xyz"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://example.memberful.com/feed"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://cdn.memberfulcontent.com/f"))
        // Host matching is case-insensitive (DNS is).
        assertTrue(PrivateFeedUrls.looksPrivate("https://PATREON.COM/rss/xyz"))
        // The suffix check does not match unrelated hosts.
        assertFalse(PrivateFeedUrls.looksPrivate("https://notpatreon.com/feed"))
        assertFalse(PrivateFeedUrls.looksPrivate("https://patreon.com.evil.example/feed"))
    }

    @Test
    fun publicFeedUrlsStayPublic() {
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/show.rss"))
        assertFalse(PrivateFeedUrls.looksPrivate("https://example.com/podcasts/my-show?lang=de"))
    }
}
