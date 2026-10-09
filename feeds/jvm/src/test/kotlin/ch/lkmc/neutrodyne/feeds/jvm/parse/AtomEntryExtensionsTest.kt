// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * iTunes, `content:encoded`, Dublin Core and PSC extension elements inside an Atom `entry` dispatch
 * through the same item handlers as in an RSS `item` (03 Field mapping).
 */
class AtomEntryExtensionsTest {
    private fun feedOf(xml: String): ParsedFeed =
        XmlPullFeedParser
            .discovered()
            .parse({ Buffer().write(xml.encodeToByteArray()) }, null, "https://feeds.test/feed.xml")
            .let { assertIs<ParseResult.Ok>(it).feed }

    @Test
    fun entryChildrenInExtensionNamespacesAreRead() {
        val feed =
            feedOf(
                "<feed xmlns=\"http://www.w3.org/2005/Atom\" " +
                    "xmlns:content=\"http://purl.org/rss/1.0/modules/content/\" " +
                    "xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\" " +
                    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                    "xmlns:psc=\"http://podlove.org/simple-chapters\">" +
                    "<entry><id>e1</id><title>Episode</title>" +
                    "<content:encoded><![CDATA[<p>Full <b>notes</b></p>]]></content:encoded>" +
                    "<summary>fallback</summary>" +
                    "<itunes:duration>60</itunes:duration>" +
                    "<itunes:season>2</itunes:season>" +
                    "<dc:date>2026-10-01T12:00:00Z</dc:date>" +
                    "<psc:chapters><psc:chapter start=\"0:00\" title=\"intro\"/></psc:chapters>" +
                    "</entry></feed>",
            )
        val item = feed.items.single()
        // content:encoded outranks the Atom summary (03 Field mapping).
        assertEquals("<p>Full <b>notes</b></p>", item.descriptionHtml)
        assertEquals(60_000L, item.durationMs)
        assertEquals(2, item.season)
        assertEquals("2026-10-01T12:00:00Z", item.rawPubDate)
        assertEquals(1_790_856_000_000L, item.pubDate)
        assertEquals(listOf("intro"), item.inlineChapters.map { it.title })
    }
}
