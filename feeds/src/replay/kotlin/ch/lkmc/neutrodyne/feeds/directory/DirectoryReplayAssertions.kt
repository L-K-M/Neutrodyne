// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.directory

import kotlin.test.assertEquals
import kotlin.test.assertTrue

object DirectoryReplayAssertions {
    fun appleSearchAndLookup(fixture: (String) -> String) {
        val search = DirectoryParsers.apple(fixture("apple-search.json"))
        assertEquals(listOf(1, 2), search.map { it.rank })
        assertEquals(listOf("121493675", "1473872585"), search.map { it.directoryId })
        assertEquals(listOf("https://feeds.npr.org/500005/podcast.xml", null), search.map { it.feedUrl })
        assertEquals(listOf(4, 1675), search.map { it.episodeCount })
        assertEquals(
            listOf(
                "NPR News Now",
                "Apple News Today",
            ),
            DirectoryParsers.apple(fixture("apple-search.json")).map {
                it.title
            },
        )
        assertEquals(
            listOf("73329284", "1200361736"),
            DirectoryParsers.apple(fixture("apple-lookup.json")).map {
                it.directoryId
            },
        )
    }

    fun modernAndLegacyChartOrder(fixture: (String) -> String) {
        assertEquals(listOf("1200361736", "1322200189"), DirectoryParsers.chartIds(fixture("apple-top.json")))
        val ids = DirectoryParsers.chartIds(fixture("apple-genre.json"))
        assertEquals(listOf("1848156557", "1502871393"), ids)
    }

    fun fyydAndIndexFields(fixture: (String) -> String) {
        val fyyd = DirectoryParsers.fyyd(fixture("fyyd.json"))
        assertEquals(listOf(1, 2), fyyd.map { it.rank })
        assertEquals(listOf("82691", "82251"), fyyd.map { it.directoryId })
        assertEquals(listOf("NPR News Now", "BBC News"), fyyd.map { it.title })
        assertEquals(
            "https://feeds.npr.org/500005/podcast.xml",
            DirectoryParsers.fyyd(fixture("fyyd.json")).first().feedUrl,
        )
        val index = DirectoryParsers.podcastIndex(fixture("podcast-index.json"))
        assertEquals(1791072000000L, index.first().lastEpisodeAt)
        assertTrue(index.last().dead)
        assertEquals(listOf(1, 2), index.map { it.rank })
        assertEquals(listOf("1", "2"), index.map { it.directoryId })
    }
}
