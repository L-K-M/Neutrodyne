// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.feeds.identity.EpisodeKeys
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import ch.lkmc.neutrodyne.feeds.identity.TitleMatch
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed

/** Publisher evidence only: callers must never supply a locally derived podcast GUID. */
internal object FeedMovePlausibility {
    fun isPlausibleMove(
        current: ParsedFeed,
        candidate: ParsedFeed,
    ): Boolean {
        val realGuid = current.podcastGuid?.let(PodcastGuid::parse)
        if (realGuid != null && realGuid == candidate.podcastGuid?.let(PodcastGuid::parse)) return true

        val currentKeys = newestGuidKeys(current)
        if (newestGuidKeys(candidate).any { it in currentKeys }) return true

        val left = current.title?.let(TitleMatch::normalise).orEmpty()
        val right = candidate.title?.let(TitleMatch::normalise).orEmpty()
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true

        // Titles are publisher-controlled and the parser accepts up to 512 KiB: the fuzzy
        // rule compares a bounded prefix so it stays cheap whatever a feed publishes.
        val boundedLeft = left.take(MAX_TITLE_COMPARE_CHARS)
        val boundedRight = right.take(MAX_TITLE_COMPARE_CHARS)
        val length = maxOf(boundedLeft.length, boundedRight.length)
        return levenshtein(boundedLeft, boundedRight) <= length * MAX_TITLE_DISTANCE
    }

    private fun newestGuidKeys(feed: ParsedFeed): Set<String> =
        feed.items
            .sortedWith(
                compareByDescending<ParsedEpisode> { it.pubDate ?: Long.MIN_VALUE }
                    .thenBy { it.feedOrder },
            ).take(NEWEST_ITEMS)
            .map(EpisodeKeys::primary)
            .filter { it.startsWith("g:") }
            .toSet()

    private fun levenshtein(
        left: String,
        right: String,
    ): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val substitution = previous[j] + if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private const val NEWEST_ITEMS = 20
    private const val MAX_TITLE_DISTANCE = 0.2

    /**
     * The most of each normalised title the fuzzy rule compares. Real show titles are a few
     * dozen characters; the bound caps the Levenshtein at 256² cell updates while exact
     * equality beyond it still counts as a match.
     */
    private const val MAX_TITLE_COMPARE_CHARS = 256
}
