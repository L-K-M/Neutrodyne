// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-check of the hand-written RFC 3986 splitter against `java.net.URI` on real feed and enclosure
 * URL shapes (03 URL normalisation): every URI that `java.net.URI` accepts must split to the same
 * authority/path/query, and the cases where `java.net.URI` throws are accepted by our lenient splitter.
 */
class UrlSplitterUriCrossCheckTest {
    private val urls =
        listOf(
            "https://feeds.example.com/show.rss",
            "https://feeds.example.com:8443/show.rss",
            "http://example.com/podcasts/my-show?lang=de",
            "https://cdn.example.com/episodes/42.mp3?token=abc&expires=123",
            "https://example.com/a%2Fb/c?x=1",
            "https://sub.domain.example.co.uk/feed/",
            "https://example.com/path/../other/file.mp3",
            "https://feeds.example.com/track/9D6KMQ2/aff.mp3?awCollectionId=123",
            "https://chtbl.com/track/ABC123/feeds.soundcloud.fm/x.mp3",
            "https://dts.podtrac.com/redirect.mp3/media.example.com/ep1.mp3",
            "https://pdst.fm/e/media.example.com/ep1.mp3",
            "https://chrt.fm/track/AB12CD/cdn.example.com/ep.mp3",
            "https://media.example.com/ep?utm_source=rss&utm_medium=feed",
            "https://example.com/feed.xml#section",
            "https://user:pass@example.com/feed.xml",
            "https://example.com:65535/x",
            "http://[2001:db8::1]:8080/feed",
            "HTTPS://EXAMPLE.COM/Feed",
            "https://example.com/Québec.rss",
        )

    @Test
    fun splitterAgreesWithJavaNetUriWhereUriParses() {
        var compared = 0
        for (url in urls) {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: continue
            val parts = splitLenient(url)

            assertEquals(uri.host?.lowercase(), parts.host?.lowercase(), "host of $url")
            assertEquals(uri.port.takeIf { it >= 0 }, parts.port?.toIntOrNull(), "port of $url")
            assertEquals(uri.rawPath ?: "", parts.path, "path of $url")
            assertEquals(uri.rawQuery?.takeIf { it.isNotEmpty() }, parts.query, "query of $url")
            compared++
        }
        assertEquals(
            urls.size,
            compared,
            "every fixture URL should be accepted by java.net.URI, compared only $compared",
        )
    }

    @Test
    fun lenientCasesUriRejectsStillSplit() {
        // `java.net.URI` throws on these, or finds no host (underscore case); the splitter accepts
        // them all. (A non-ASCII path like Québec.rss is legal to `URI` and sits in `urls` above.)
        val lenient =
            listOf(
                "https://example.com/a b.mp3" to "/a b.mp3",
                "https://example.com/100%" to "/100%",
                "https://ex_ample.example.com/feed.xml" to "/feed.xml",
            )
        for ((url, expectedPath) in lenient) {
            val parts = splitLenient(url)
            assertTrue(!parts.host.isNullOrEmpty(), "host of $url")
            assertEquals(expectedPath, parts.path, "path of $url")
            assertTrue(
                runCatching { java.net.URI(url) }.getOrNull()?.host.isNullOrEmpty(),
                "java.net.URI should throw or find no host for $url",
            )
        }
    }

    @Test
    fun ipv6CrossCheckCoversTheBracketSpelling() {
        // java.net.URI keeps the brackets in getHost(); splitLenient does too, so the pair must
        // compare equal with brackets on both sides — a regression stripping them would pass a
        // host check on a URL without the literal.
        val uri = java.net.URI("http://[2001:db8::1]:8080/feed")
        val parts = splitLenient("http://[2001:db8::1]:8080/feed")
        assertEquals("[2001:db8::1]", uri.host)
        assertEquals(uri.host, parts.host)
        assertEquals(uri.port, parts.port?.toIntOrNull())
    }
}
