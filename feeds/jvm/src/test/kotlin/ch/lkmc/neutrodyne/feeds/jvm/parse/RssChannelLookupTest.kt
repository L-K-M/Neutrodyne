// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.parse.ParseFailure
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The RSS 2.0 root reads its first `<channel>` child, skipping any other child of `<rss>` (03
 * Parser setup and charset step 7). Acast feeds put an XHTML `<script>` before the channel.
 */
class RssChannelLookupTest {
    private fun parse(xml: String): ParseResult =
        XmlPullFeedParser
            .discovered()
            .parse({ Buffer().write(xml.encodeToByteArray()) }, null, "https://feeds.test/feed.xml")

    private fun titleOf(xml: String): String? = assertIs<ParseResult.Ok>(parse(xml)).feed.title

    @Test
    fun elementBeforeTheChannelIsSkipped() {
        val xml =
            "<rss version=\"2.0\"><script src=\"https://feeds.test/p.js\" " +
                "xmlns=\"http://www.w3.org/1999/xhtml\"></script>" +
                "<channel><title>Real</title></channel></rss>"
        assertEquals("Real", titleOf(xml))
    }

    @Test
    fun selfClosingAndTextBearingElementsBeforeTheChannelAreSkipped() {
        val xml =
            "<rss version=\"2.0\"><meta/><note>text <b>bold</b></note>" +
                "<channel><title>Real</title></channel></rss>"
        assertEquals("Real", titleOf(xml))
    }

    @Test
    fun channelNestedInsideAForeignElementIsNotTheFeedChannel() {
        val xml =
            "<rss version=\"2.0\"><wrapper><channel><title>Decoy</title></channel></wrapper>" +
                "<channel><title>Real</title></channel></rss>"
        assertEquals("Real", titleOf(xml))
    }

    @Test
    fun rssWithoutAChannelChildIsNotAFeed() {
        val xml = "<rss version=\"2.0\"><script/><wrapper><channel><title>Decoy</title></channel></wrapper></rss>"
        assertEquals(ParseFailure.NOT_A_FEED, assertIs<ParseResult.Failed>(parse(xml)).reason)
    }

    @Test
    fun emptyRssIsNotAFeed() {
        assertEquals(ParseFailure.NOT_A_FEED, assertIs<ParseResult.Failed>(parse("<rss version=\"2.0\"/>")).reason)
    }
}
