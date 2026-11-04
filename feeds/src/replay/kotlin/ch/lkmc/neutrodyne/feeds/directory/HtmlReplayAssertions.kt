// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.directory

import ch.lkmc.neutrodyne.feeds.discovery.Autodiscovery
import ch.lkmc.neutrodyne.feeds.discovery.DiscoverySource
import okio.Buffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One set of expected candidates for desktop, Android host and Android device replay. */
object HtmlReplayAssertions {
    fun recordedCandidates(
        discovery: Autodiscovery,
        fixture: (String) -> String,
    ) {
        fun urls(name: String) =
            discovery
                .candidates(
                    Buffer().writeUtf8(fixture(name)),
                    null,
                    "https://example.org/page",
                ).map { it.url }
        assertEquals(
            listOf("https://example.org/shows/podcast.xml", "https://example.org/shows/blog.xml"),
            urls("alternate.html"),
        )
        assertEquals(listOf("https://example.org/one.xml", "https://example.org/two.xml"), urls("multiple.html"))
        assertEquals(listOf("https://example.org/feed"), urls("wordpress.html"))
        val apple = discovery.candidates(Buffer().writeUtf8(fixture("apple-only.html")), null, "https://example.org/")
        assertEquals(DiscoverySource.APPLE_ANCHOR, apple.single().source)
        assertEquals("https://podcasts.apple.com/us/podcast/science-friday/id73329284", apple.single().url)
    }

    fun boundedPrefix(discovery: Autodiscovery) {
        val input =
            Buffer().writeUtf8(
                "<html><head>" + " ".repeat(Autodiscovery.MAX_HTML_BYTES.toInt()) +
                    "<link rel='alternate' type='application/rss+xml' href='/late.xml'></head></html>",
            )
        val before = input.size
        assertTrue(discovery.candidates(input, null, "https://example.org/").isEmpty())
        assertEquals(Autodiscovery.MAX_HTML_BYTES, before - input.size)
    }
}
