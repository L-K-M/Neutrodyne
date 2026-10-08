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
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?category=news"))
    }

    @Test
    fun longTokensInPathsAndValues() {
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/xKd93lskSKEa1zl4dQeF1/rss"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://feeds.example.com/feed?user=a1b2c3d4e5f6g7h8i9j0k1l2"))
        // No digit: not a token.
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/abcdefghijklmnopqrstuvwxyz"))
        // Too short: not a token.
        assertFalse(PrivateFeedUrls.looksPrivate("https://feeds.example.com/abc123def456"))
    }

    @Test
    fun hyphenatedSlugIsNotAToken() {
        // Path tokens are separator-free runs: wordy slugs with digits stay public.
        assertFalse(PrivateFeedUrls.looksPrivate("https://example.com/feeds/my-podcast-episode-123-rss/"))
        assertFalse(PrivateFeedUrls.looksPrivate("https://example.com/podcast/the-show-2025/feed"))
        assertTrue(PrivateFeedUrls.looksPrivate("https://example.com/feeds/xKd93lskSKEa1zl4dQeF1/"))
        // Query values keep the permissive pattern (UUID-shaped tokens still match there).
        assertTrue(PrivateFeedUrls.looksPrivate("https://example.com/feed?k=someLongRandomToken12345"))
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
