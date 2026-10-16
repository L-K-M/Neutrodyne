// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Badges count playable, visible episodes in the bounded window (05). */
data class FeedCounts(
    val unplayed: Int = 0,
    val newSinceVisit: Int = 0,
)

data class VirtualCounts(
    val all: FeedCounts = FeedCounts(),
    val ungrouped: FeedCounts = FeedCounts(),
)

object GroupCounts {
    const val WINDOW_DAYS = 30
    const val DAY_MS = 86_400_000L
}
