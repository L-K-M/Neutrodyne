// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Media RSS `isDefault` entries come first within their element's `media:content` list (03 Field
 * mapping) — in document order among themselves and ahead of every non-default. Prepending each
 * default at index 0 instead reversed their order and shifted the list once per default.
 */
class MediaContentOrderingTest {
    private val baseUrl = "https://example.com/feed.xml"

    @Test
    fun mediaIsDefaultKeepsDocumentOrder() {
        val xml =
            "<rss version=\"2.0\" xmlns:media=\"http://search.yahoo.com/mrss/\">" +
                "<channel><item><guid>m</guid>" +
                "<media:content url=\"https://cdn.example.com/d1.mp3\" type=\"audio/mpeg\" isDefault=\"true\"/>" +
                "<media:content url=\"https://cdn.example.com/a.mp3\" type=\"audio/mpeg\"/>" +
                "<media:content url=\"https://cdn.example.com/d2.mp3\" type=\"audio/mpeg\" isDefault=\"true\"/>" +
                "<media:content url=\"https://cdn.example.com/b.mp3\" type=\"audio/mpeg\"/>" +
                "</item></channel></rss>"
        val feed = assertIs<ParseResult.Ok>(parse(xml)).feed
        assertEquals(
            listOf(
                "https://cdn.example.com/d1.mp3",
                "https://cdn.example.com/d2.mp3",
                "https://cdn.example.com/a.mp3",
                "https://cdn.example.com/b.mp3",
            ),
            feed.items
                .single()
                .enclosures
                .map { it.url },
        )
    }

    private fun parse(xml: String): ParseResult =
        XmlPullFeedParser.discovered().parse({ Buffer().write(xml.encodeToByteArray()) }, null, baseUrl)
}
