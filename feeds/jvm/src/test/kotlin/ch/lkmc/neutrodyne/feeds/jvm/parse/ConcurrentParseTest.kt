// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 03 Threading model lets two parses run concurrently (`limitedParallelism(2)`), so one
 * `XmlPullFeedParser` instance must carry no mutable state between documents: every counter,
 * warning list and namespace binding belongs to the invocation, not the parser.
 */
class ConcurrentParseTest {
    private val baseUrl = "https://example.com/feed.xml"

    /**
     * The regression shape: an RSS parse parks at its second `<item>` while a namespace-free Atom
     * document parses on the same instance. The Atom document's `atomNamespace = ""` must not leak
     * into the resumed parse — if it did, the item's RSS children would dispatch as Atom elements
     * and the episode would lose its guid, publication date and enclosure.
     */
    @Test(timeout = 30_000)
    fun interleavedAtomParseCannotStealRssItemFields() {
        val rss =
            """
            <rss version="2.0"><channel><title>t</title>
            <item><guid>g1</guid><title>One</title><pubDate>Sun, 04 Oct 2026 00:00:00 +0000</pubDate>
            <enclosure url="https://cdn.test/1.mp3" type="audio/mpeg" length="1"/></item>
            <item><guid>g2</guid><title>Two</title><pubDate>Sun, 04 Oct 2026 00:00:00 +0000</pubDate>
            <enclosure url="https://cdn.test/2.mp3" type="audio/mpeg" length="1"/></item>
            </channel></rss>
            """.trimIndent().encodeToByteArray()
        val atom = """<feed><entry><id>a1</id><title>A</title></entry></feed>""".encodeToByteArray()

        val secondItemReached = CountDownLatch(1)
        val release = CountDownLatch(1)

        // A parser that parks when a document's second `<item>` start tag arrives. Every document
        // gets its own parser through the factory, so the item counter is per-parser state.
        val factory =
            PullParserFactory {
                val delegate = PullParserFactory.Discovered.create()
                var itemsSeen = 0
                object : XmlPullParser by delegate {
                    override fun next(): Int {
                        val event = delegate.next()
                        if (event == XmlPullParser.START_TAG &&
                            delegate.name == "item" &&
                            ++itemsSeen == SECOND_ITEM
                        ) {
                            secondItemReached.countDown()
                            release.await(GATE_SECONDS, TimeUnit.SECONDS)
                        }
                        return event
                    }
                }
            }
        val parser = XmlPullFeedParser(factory)

        var rssResult: ParseResult? = null
        val rssThread = thread { rssResult = parser.parse({ Buffer().write(rss) }, null, baseUrl) }
        assertTrue(
            secondItemReached.await(GATE_SECONDS, TimeUnit.SECONDS),
            "the RSS parse never reached its second item",
        )
        try {
            val atomFeed =
                assertIs<ParseResult.Ok>(parser.parse({ Buffer().write(atom) }, null, baseUrl)).feed
            assertEquals("a1", atomFeed.items.single().guid)
        } finally {
            release.countDown()
        }
        rssThread.join(GATE_SECONDS * 1_000)

        val feed = assertIs<ParseResult.Ok>(rssResult).feed
        assertEquals(2, feed.items.size)
        val second = feed.items[1]
        assertEquals("g2", second.guid)
        assertEquals("Two", second.title)
        assertNotNull(second.pubDate, "the second item kept its publication date")
        assertEquals("https://cdn.test/2.mp3", second.enclosures.single().url)
    }

    private companion object {
        const val SECOND_ITEM = 2
        const val GATE_SECONDS = 15L
    }
}
