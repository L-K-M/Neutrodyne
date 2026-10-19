// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

abstract class YouTubeDateProvenanceTest(
    private val parser: FeedParser,
) {
    @Test fun publishedAndUpdatedStaySeparateWhileGenericAtomKeepsFallback() {
        val xml = requireNotNull(javaClass.getResource("/youtube/m8-atom.xml")).readText()
        val feed =
            (
                parser.parse(
                    { Buffer().writeUtf8(xml) },
                    null,
                    "https://www.youtube.com/feed",
                ) as ParseResult.Ok
            ).feed
        val published = feed.items[0]
        assertEquals(1_792_324_800_000L, published.atomDates!!.publishedAtMs)
        assertEquals(published.pubDate, published.atomDates!!.publishedAtMs)
        val missing = feed.items[1]
        assertNotNull(missing.pubDate)
        assertNull(missing.atomDates!!.publishedAtMs)
        assertNull(missing.atomDates!!.rawPublished)
        assertEquals(missing.pubDate, missing.atomDates!!.updatedAtMs)
        val malformed = feed.items[2]
        assertNotNull(malformed.pubDate)
        assertNull(malformed.atomDates!!.publishedAtMs)
        assertEquals("not-a-date", malformed.atomDates!!.rawPublished)
    }
}
