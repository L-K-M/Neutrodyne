// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * URL resolution rules (03 Parser): raw references are bounded before RFC 3986 resolution, each
 * URL-bearing element's own `xml:base` applies and propagates through nested containers, accepted
 * URLs are absolute http(s) with an authority, and an empty query differs from an absent one.
 */
class UrlResolutionTest {
    private fun feedOf(
        xml: String,
        baseUrl: String = "https://feeds.test/dir/feed.xml",
    ): ParsedFeed =
        XmlPullFeedParser
            .discovered()
            .parse({ Buffer().write(xml.encodeToByteArray()) }, null, baseUrl)
            .let { assertIs<ParseResult.Ok>(it).feed }

    /** `"../"` × 1,000,000 must be dropped by the raw bound, not resolved quadratically (S4). */
    @Test(timeout = 10_000)
    fun dotSegmentFloodIsBoundedBeforeResolution() {
        val url = "../".repeat(1_000_000) + "e.mp3"
        val feed =
            feedOf(
                "<rss version=\"2.0\"><channel><item>" +
                    "<enclosure url=\"$url\" type=\"audio/mpeg\" length=\"1\"/>" +
                    "</item></channel></rss>",
            )
        assertTrue(
            feed.items
                .single()
                .enclosures
                .isEmpty(),
        )
        assertTrue(feed.warnings.any { it.code == WarningCode.BAD_URL })
    }

    @Test
    fun overlongXmlBaseFallsBackToParentBase() {
        val xmlBase = "https://cdn.test/" + "a".repeat(10_000) + "/"
        val feed =
            feedOf(
                "<rss version=\"2.0\"><channel><item xml:base=\"$xmlBase\">" +
                    "<enclosure url=\"e.mp3\" type=\"audio/mpeg\" length=\"1\"/>" +
                    "</item></channel></rss>",
            )
        assertEquals(
            "https://feeds.test/dir/e.mp3",
            feed.items
                .single()
                .enclosures
                .single()
                .url,
        )
    }

    /** V4/S4: an enclosure link's own `xml:base` resolves its `href`, not the feed's. */
    @Test
    fun atomEnclosureLinkAppliesItsOwnXmlBase() {
        val feed =
            feedOf(
                "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><id>e1</id>" +
                    "<link rel=\"enclosure\" xml:base=\"https://cdn.test/audio/\" href=\"ep.mp3\" " +
                    "type=\"audio/mpeg\"/>" +
                    "</entry></feed>",
                baseUrl = "https://feeds.test/feed.xml",
            )
        assertEquals(
            "https://cdn.test/audio/ep.mp3",
            feed.items
                .single()
                .enclosures
                .single()
                .url,
        )
    }

    @Test
    fun relativeXmlBaseOnAnElementMergesAgainstTheItemBase() {
        val feed =
            feedOf(
                "<rss version=\"2.0\"><channel><item xml:base=\"shows/\">" +
                    "<enclosure xml:base=\"audio/\" url=\"ep.mp3\" type=\"audio/mpeg\" length=\"1\"/>" +
                    "</item></channel></rss>",
            )
        assertEquals(
            "https://feeds.test/dir/shows/audio/ep.mp3",
            feed.items
                .single()
                .enclosures
                .single()
                .url,
        )
    }

    /** Nested containers carry their base into their children's references. */
    @Test
    fun mediaGroupXmlBasePropagatesToChildren() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:media=\"http://search.yahoo.com/mrss/\"><channel><item>" +
                    "<media:group xml:base=\"https://media.test/v/\">" +
                    "<media:content url=\"clip.mp4\" type=\"video/mp4\"/>" +
                    "</media:group></item></channel></rss>",
            )
        assertEquals(
            "https://media.test/v/clip.mp4",
            feed.items
                .single()
                .enclosures
                .single()
                .url,
        )
    }

    @Test
    fun channelAtomLinkAppliesItsOwnXmlBase() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\"><channel>" +
                    "<atom:link rel=\"next\" xml:base=\"https://pages.test/\" href=\"p2.xml\"/>" +
                    "<title>t</title></channel></rss>",
            )
        assertEquals("https://pages.test/p2.xml", feed.paging.next)
    }

    /** V5: schemes match case-insensitively; an empty authority is not a URL. */
    @Test
    fun httpSchemeIsCaseInsensitive() {
        val feed =
            feedOf(
                "<rss version=\"2.0\"><channel><item>" +
                    "<enclosure url=\"HTTPS://cdn.test/e.mp3\" type=\"audio/mpeg\" length=\"1\"/>" +
                    "</item></channel></rss>",
            )
        assertEquals(
            "HTTPS://cdn.test/e.mp3",
            feed.items
                .single()
                .enclosures
                .single()
                .url,
        )
    }

    @Test
    fun schemeOnlyUrlIsRejected() {
        val feed =
            feedOf(
                "<rss version=\"2.0\"><channel><item>" +
                    "<enclosure url=\"https://\" type=\"audio/mpeg\" length=\"1\"/>" +
                    "</item></channel></rss>",
            )
        assertTrue(
            feed.items
                .single()
                .enclosures
                .isEmpty(),
        )
        assertTrue(feed.warnings.any { it.code == WarningCode.BAD_URL })
    }

    /** V6: `?` is an explicitly empty query and replaces the base's, not a missing one. */
    @Test
    fun emptyQueryReplacesTheBaseQuery() {
        val feed =
            feedOf(
                "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><id>e1</id>" +
                    "<link rel=\"enclosure\" href=\"?\" type=\"audio/mpeg\"/>" +
                    "</entry></feed>",
                baseUrl = "https://feeds.test/feed.xml?old=1",
            )
        assertEquals(
            "https://feeds.test/feed.xml?",
            feed.items
                .single()
                .enclosures
                .single()
                .url,
        )
    }

    @Test
    fun absentQueryKeepsTheBaseQuery() {
        val feed =
            feedOf(
                "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><id>e1</id>" +
                    "<link rel=\"enclosure\" href=\"#top\" type=\"audio/mpeg\"/>" +
                    "</entry></feed>",
                baseUrl = "https://feeds.test/feed.xml?old=1",
            )
        assertEquals(
            "https://feeds.test/feed.xml?old=1#top",
            feed.items
                .single()
                .enclosures
                .single()
                .url,
        )
    }

    /** W6: `podcast:funding`'s url resolves against the element's own `xml:base`. */
    @Test
    fun fundingUrlAppliesItsOwnXmlBase() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">" +
                    "<channel><title>t</title>" +
                    "<podcast:funding url=\"donate\" xml:base=\"https://fund.test/o/\">F</podcast:funding>" +
                    "</channel></rss>",
            )
        assertEquals("https://fund.test/o/donate", feed.funding.single().url)
    }

    /** W6: the item's `xml:base` reaches `podcast:funding` through the element's own. */
    @Test
    fun itemFundingUrlMergesItemAndElementXmlBase() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">" +
                    "<channel><title>t</title>" +
                    "<item xml:base=\"ep/\"><guid>g</guid>" +
                    "<podcast:funding url=\"f\" xml:base=\"donate/\">F</podcast:funding>" +
                    "</item></channel></rss>",
            )
        assertEquals(
            "https://feeds.test/dir/ep/donate/f",
            feed.items
                .single()
                .funding
                .single()
                .url,
        )
    }

    /** W6: `podcast:person`'s img and href resolve against the element's `xml:base`. */
    @Test
    fun personImgAndHrefApplyXmlBase() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">" +
                    "<channel><title>t</title>" +
                    "<podcast:person xml:base=\"https://people.test/p/\" img=\"face.png\" " +
                    "href=\"bio\">H</podcast:person>" +
                    "</channel></rss>",
            )
        val person = feed.persons.single()
        assertEquals("https://people.test/p/face.png", person.img)
        assertEquals("https://people.test/p/bio", person.href)
    }

    /** W6: an item-level person's img resolves against the item's `xml:base`. */
    @Test
    fun itemPersonImgAppliesItemXmlBase() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">" +
                    "<channel><title>t</title>" +
                    "<item xml:base=\"ep/\"><guid>g</guid>" +
                    "<podcast:person img=\"face.png\">H</podcast:person>" +
                    "</item></channel></rss>",
            )
        assertEquals(
            "https://feeds.test/dir/ep/face.png",
            feed.items
                .single()
                .persons
                ?.single()
                ?.img,
        )
    }

    /** W6: `psc:chapter` href/image resolve — the container's `xml:base` propagates to children. */
    @Test
    fun pscChapterUrlsApplyContainerAndElementXmlBase() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:psc=\"http://podlove.org/simple-chapters\">" +
                    "<channel><title>t</title><item><guid>g</guid>" +
                    "<psc:chapters xml:base=\"ch/\">" +
                    "<psc:chapter start=\"00:01\" title=\"a\" href=\"topic/1\" image=\"pic.png\"/>" +
                    "<psc:chapter start=\"00:02\" title=\"b\" xml:base=\"https://cdn.test/x/\" " +
                    "href=\"t2\" image=\"i2.jpg\"/>" +
                    "</psc:chapters></item></channel></rss>",
            )
        val chapters = feed.items.single().inlineChapters
        assertEquals("https://feeds.test/dir/ch/topic/1", chapters[0].href)
        assertEquals("https://feeds.test/dir/ch/pic.png", chapters[0].image)
        assertEquals("https://cdn.test/x/t2", chapters[1].href)
        assertEquals("https://cdn.test/x/i2.jpg", chapters[1].image)
    }

    /** V9/C17: invalid srcset entries are dropped before their widths are ranked. */
    @Test
    fun srcsetRanksOnlyValidEntries() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">" +
                    "<channel>" +
                    "<podcast:images srcset=\"https://cdn.test/small.jpg 600w, javascript:x 3000w\"/>" +
                    "<title>t</title></channel></rss>",
            )
        val artwork = feed.artwork.single()
        assertEquals("https://cdn.test/small.jpg", artwork.url)
        assertEquals(600, artwork.width)
    }
}
