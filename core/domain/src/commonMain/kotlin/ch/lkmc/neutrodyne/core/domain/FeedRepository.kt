// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import kotlinx.coroutines.flow.Flow

/**
 * The feed contract (05 Group feeds), M1a slice: [pagedFeed] for `FeedSource.All` and
 * `FeedSource.Podcast` plus [setFeedOrder]. `observeTabs`, `observePrefs`, counts, visits,
 * `setFilters`, `markVisited`, `countUnplayed` and `downloadAllEstimate` arrive with M2's group
 * feeds; the canonical interface grows per milestone (05 Canonical APIs).
 */
interface FeedRepository {
    /**
     * One page flow of [source]'s episodes, built only through `FeedQueryBuilder` (05). The
     * implementation owns the `Pager` and 08's fixed `PagingConfig`; `cachedIn`, collection
     * lifecycle and the per-source LRU stay with the ViewModel (08 Paging hand-off).
     */
    fun pagedFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): Flow<PagingData<EpisodeRow>>

    /**
     * [FeedSource.Podcast] writes `podcast.episodeOrder`; `FeedSource.Group` writes
     * `podcast_group.feedOrder`. `All` and `Ungrouped` are fixed `NEWEST_FIRST` in v1 and throw
     * `IllegalArgumentException` (05 Sources and tabs).
     */
    suspend fun setFeedOrder(
        source: FeedSource,
        order: FeedOrder,
    )
}
