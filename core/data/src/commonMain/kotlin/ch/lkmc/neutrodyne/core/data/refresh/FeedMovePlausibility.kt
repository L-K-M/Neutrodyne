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

        val length = maxOf(left.length, right.length)
        return levenshtein(left, right) <= length * MAX_TITLE_DISTANCE
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
}
