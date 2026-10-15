// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.FeedMovePlausibility
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

    /**
     * Titles are publisher-controlled and the parser accepts them up to 512 KiB, so the
     * normalised-title rule must not be quadratic in their length (a 500,000-char pair is
     * ~2.5e11 Levenshtein cell updates, minutes of work while the run's locks are held). The
     * rule compares a bounded prefix instead; the small case exists so a regression fails on
     * the elapsed assertion quickly instead of stalling the suite on the 500,000-char pair.
     */
    @Test
    fun publisherSizedTitlesCompareInBoundedTime() {
        for (size in listOf(30_000, 500_000)) {
            val mark = TimeSource.Monotonic.markNow()
            val plausible =
                FeedMovePlausibility.isPlausibleMove(
                    parsedFeed(title = "a".repeat(size)),
                    parsedFeed(title = "b".repeat(size)),
                )
            val elapsed = mark.elapsedNow()
            assertFalse(plausible)
            assertTrue(elapsed < TITLE_COMPARE_BUDGET, "$size-char title comparison took $elapsed")
        }
    }

    /**
     * The bound reads the compared prefix only (03 itunes:new-feed-url, implementation note):
     * identical prefixes count as similar when the tails differ, and identical tails do not
     * rescue prefixes over the threshold.
     */
    @Test
    fun titleRuleSeesOnlyTheBoundedPrefix() {
        val longTail = "shared".repeat(40_000)
        assertFalse(
            FeedMovePlausibility.isPlausibleMove(
                parsedFeed(title = "x".repeat(256) + longTail),
                parsedFeed(title = "y".repeat(256) + longTail),
            ),
        )
        assertTrue(
            FeedMovePlausibility.isPlausibleMove(
                parsedFeed(title = "pre".repeat(86) + "one"),
                parsedFeed(title = "pre".repeat(86) + "two"),
            ),
        )
    }

    @Test
    fun emptyTitlesProvideNoEvidence() {
        for (title in listOf(null, "", "   ")) {
            assertFalse(FeedMovePlausibility.isPlausibleMove(parsedFeed(title = title), parsedFeed(title = title)))
        }
    }

    private companion object {
        const val GUID = "12345678-1234-1234-1234-123456789abc"

        /**
         * Covers linear normalisation of a 500,000-char title on a slow CI runner (230 ms seen)
         * while still failing fast without the bound, which took 3.4 s at 30,000 chars.
         */
        val TITLE_COMPARE_BUDGET = 2.seconds
    }
}
