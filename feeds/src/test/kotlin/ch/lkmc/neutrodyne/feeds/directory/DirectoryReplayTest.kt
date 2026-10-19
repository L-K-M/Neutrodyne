// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.directory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Same short recordings execute on the desktop and Android host targets. */
class DirectoryReplayTest {
    @Test fun appleSearchAndLookup() {
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

    @Test fun modernAndLegacyChartOrder() {
        assertEquals(listOf("1200361736", "1322200189"), DirectoryParsers.chartIds(fixture("apple-top.json")))
        val ids = DirectoryParsers.chartIds(fixture("apple-genre.json"))
        assertEquals(2, ids.size)
        assertTrue(ids.all { it.all(Char::isDigit) })
    }

    @Test fun fyydAndIndexFields() {
        assertEquals(
            "https://feeds.npr.org/500005/podcast.xml",
            DirectoryParsers.fyyd(fixture("fyyd.json")).first().feedUrl,
        )
        val index = DirectoryParsers.podcastIndex(fixture("podcast-index.json"))
        assertEquals(1791072000000L, index.first().lastEpisodeAt)
        assertTrue(index.last().dead)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/discovery/$name")).bufferedReader().use {
            it.readText()
        }
}
