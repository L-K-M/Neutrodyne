// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PlayContextSpec

/** Read-only, main-safe context queries (05); IO is off main, failures/cancellation propagate. */
interface PlayContextResolver {
    /** Freeze age bound now; carry filters/order, always imply UNPLAYED.
     * All/Ungrouped force NEWEST_FIRST; Group/Podcast use caller-resolved effective play order. No session mutation. */
    suspend fun spec(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): PlayContextSpec

    /**
     * First eligible item excluding current/Up next; null means none. Read capabilities per call.
     * Oldest-first groups first try the subscription bound, then unbounded if empty; podcasts do
     * not use that bound. PlayStarter, not this query, handles the automatic queue-first choice.
     */
    suspend fun startItem(
        spec: PlayContextSpec,
        eligibility: ContextEligibility = ContextEligibility.PLAYABLE,
    ): Long?
}

/** Probe external-only emptiness without changing any other context predicate. */
enum class ContextEligibility { PLAYABLE, INCLUDE_EXTERNAL_YOUTUBE }
