// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.FeedMovePlausibility
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedMovePlausibilityTest {
    @Test
    fun equalRealPodcastGuidsCountIgnoringCase() {
        assertTrue(
            FeedMovePlausibility.isPlausibleMove(
                parsedFeed(title = "Left", podcastGuid = GUID.uppercase()),
                parsedFeed(title = "Right", podcastGuid = GUID),
            ),
        )
    }

    @Test
    fun invalidAndNilGuidsNeverCount() {
        for (guid in listOf("not-a-guid", "00000000-0000-0000-0000-000000000000")) {
            assertFalse(
                FeedMovePlausibility.isPlausibleMove(
                    parsedFeed(title = "Left", podcastGuid = guid),
                    parsedFeed(title = "Right", podcastGuid = guid),
                ),
            )
        }
    }

    @Test
    fun absentRealGuidDoesNotDeriveEvidenceFromEqualLinks() {
        val current = parsedFeed(title = "Left").copy(link = "https://example.com/feed")
        val candidate = parsedFeed(title = "Right", podcastGuid = PodcastGuid.derive(requireNotNull(current.link)))
        assertFalse(FeedMovePlausibility.isPlausibleMove(current, candidate))
    }

    @Test
    fun sharedGuidKeyCounts() {
        assertTrue(
            FeedMovePlausibility.isPlausibleMove(
                parsedFeed(title = "Left", items = listOf(parsedEpisode(order = 0, guid = " shared "))),
                parsedFeed(
                    title = "Right",
                    items =
                        listOf(
                            parsedEpisode(order = 0, guid = "shared", enclosureUrl = "https://new.example/ep.mp3"),
                        ),
                ),
            ),
        )
    }

    @Test
    fun sharedNonGuidIdentityDoesNotCount() {
        val episode = parsedEpisode(order = 0, guid = null)
        assertFalse(
            FeedMovePlausibility.isPlausibleMove(
                parsedFeed(title = "Left", items = listOf(episode)),
                parsedFeed(title = "Right", items = listOf(episode)),
            ),
        )
    }

    @Test
    fun twentiethNewestItemCountsButTwentyFirstDoesNot() {
        val items = (1..21).map { parsedEpisode(order = it, guid = "ep$it", pubDate = it.toLong()) }
        val current = parsedFeed(title = "Left", items = items)
        for ((guid, expected) in listOf("ep2" to true, "ep1" to false)) {
            val candidate = parsedFeed(title = "Right", items = listOf(parsedEpisode(order = 0, guid = guid)))
            kotlin.test.assertEquals(expected, FeedMovePlausibility.isPlausibleMove(current, candidate))
            kotlin.test.assertEquals(expected, FeedMovePlausibility.isPlausibleMove(candidate, current))
        }
    }

    @Test
    fun undatedItemsUseFeedOrder() {
        val items = (0..20).reversed().map { parsedEpisode(order = it, guid = "ep$it") }
        assertTrue(
            FeedMovePlausibility.isPlausibleMove(
                parsedFeed(title = "Left", items = items),
                parsedFeed(title = "Right", items = listOf(parsedEpisode(order = 0, guid = "ep19"))),
            ),
        )
        assertFalse(
            FeedMovePlausibility.isPlausibleMove(
                parsedFeed(title = "Left", items = items),
                parsedFeed(title = "Right", items = listOf(parsedEpisode(order = 0, guid = "ep20"))),
            ),
        )
    }

    @Test
    fun normalisedTitlesCount() {
        assertTrue(
            FeedMovePlausibility.isPlausibleMove(
                parsedFeed(title = "  ＴＥＳＴ   ‘Show’ — title  "),
                parsedFeed(title = "test 'show' - title"),
            ),
        )
    }

    @Test
    fun titleThresholdIncludesExactlyPointEight() {
        assertTrue(FeedMovePlausibility.isPlausibleMove(parsedFeed(title = "abcde"), parsedFeed(title = "abcdx")))
        assertFalse(FeedMovePlausibility.isPlausibleMove(parsedFeed(title = "abcd"), parsedFeed(title = "abcx")))
        assertFalse(FeedMovePlausibility.isPlausibleMove(parsedFeed(title = "abcde"), parsedFeed(title = "abcxy")))
    }

    @Test
    fun titleSimilarityUsesLongerLengthAndEditDistance() {
        assertTrue(FeedMovePlausibility.isPlausibleMove(parsedFeed(title = "abcd"), parsedFeed(title = "abcde")))
        assertTrue(FeedMovePlausibility.isPlausibleMove(parsedFeed(title = "abcde"), parsedFeed(title = "abcd")))
        assertFalse(FeedMovePlausibility.isPlausibleMove(parsedFeed(title = "abcd"), parsedFeed(title = "abcxyz")))
    }

    @Test
    fun emptyTitlesProvideNoEvidence() {
        for (title in listOf(null, "", "   ")) {
            assertFalse(FeedMovePlausibility.isPlausibleMove(parsedFeed(title = title), parsedFeed(title = title)))
        }
    }

    private companion object {
        const val GUID = "12345678-1234-1234-1234-123456789abc"
    }
}
