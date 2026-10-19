// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.jvm.html

import ch.lkmc.neutrodyne.feeds.discovery.Autodiscovery
import ch.lkmc.neutrodyne.feeds.discovery.DiscoverySource
import ch.lkmc.neutrodyne.feeds.discovery.HostAction
import ch.lkmc.neutrodyne.feeds.discovery.HostRecognizer
import okio.Buffer
import okio.Source
import okio.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutodiscoveryTest {
    private val discovery = JsoupAutodiscovery()

    @Test fun alternatesRankPodcastHonorBaseAndDropComments() {
        val rows = parse(fixture("alternate.html"))
        assertEquals(
            listOf("https://example.org/shows/podcast.xml", "https://example.org/shows/blog.xml"),
            rows.map { it.url },
        )
        assertTrue(rows.all { it.source == DiscoverySource.ALTERNATE_LINK })
    }

    @Test fun appleAnchorsPrecedeSameSiteAnchors() {
        val rows = parse(fixture("apple-only.html") + "<a href='/feed'>RSS</a>")
        assertEquals(DiscoverySource.APPLE_ANCHOR, rows.single().source)
        assertEquals(HostAction.AppleLookup("73329284"), HostRecognizer.recognize(rows.single().url))
    }

    @Test fun sameSiteAnchorsExcludeUnrelatedAndInvalidSchemes() {
        val rows =
            parse(
                "<html><a href='javascript:alert(1)'>x</a><a href='https://other.example/feed'>other</a><a href='/rss.xml'>RSS</a><a href='/comments/feed'>comments</a></html>",
            )
        assertEquals(listOf("https://example.org/rss.xml"), rows.map { it.url })
    }

    @Test fun boundedPrefixDoesNotCloseCallerSource() {
        val bytes =
            Buffer().writeUtf8(
                "<html><head>" + " ".repeat(Autodiscovery.MAX_HTML_BYTES.toInt()) +
                    "<link rel='alternate' type='text/xml' href='/late.xml'></head></html>",
            )
        var read = 0L
        var closed = false
        val source =
            object : Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long =
                    bytes.read(sink, byteCount).also {
                        if (it >
                            0
                        ) {
                            read += it
                        }
                    }

                override fun timeout() = Timeout.NONE

                override fun close() {
                    closed = true
                }
            }
        assertTrue(discovery.candidates(source, null, "https://example.org/").isEmpty())
        assertEquals(Autodiscovery.MAX_HTML_BYTES, read)
        assertFalse(closed)
    }

    @Test fun verificationCandidatesAreDeduplicatedAndCapped() {
        val rows =
            parse(
                "<html><head>" + (1..10).joinToString("") { "<link rel='alternate' type='text/xml' href='/$it.xml'>" } +
                    "</head></html>",
            )
        assertEquals(5, rows.size)
        assertEquals(2, parse(fixture("multiple.html")).size)
        assertEquals("https://example.org/feed", parse(fixture("wordpress.html")).single().url)
    }

    private fun parse(html: String) = discovery.candidates(Buffer().writeUtf8(html), null, "https://example.org/page")

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/discovery/$name")).bufferedReader().use {
            it.readText()
        }
}
