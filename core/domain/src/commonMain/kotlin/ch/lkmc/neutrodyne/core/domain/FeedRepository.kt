// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedTab
import ch.lkmc.neutrodyne.core.model.VirtualCounts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Feeds, persisted view preferences, ordered tabs and device visits (05 Group feeds). */
interface FeedRepository {
    fun observeTabs(): Flow<List<FeedTab>>

    /** Hour-rounded lower bound slides hourly while collected, capped at the 30-day badge window. */
    fun observeGroupCounts(sinceMs: Long): Flow<Map<Long, FeedCounts>>

    fun observeVirtualCounts(sinceMs: Long): Flow<VirtualCounts>

    /** Unbounded by badge windows/view filters; freeze the cutoff until the guarded write returns. */
    suspend fun countUnplayed(
        source: FeedSource,
        sortDateBefore: Long?,
    ): Int

    fun observePrefs(source: FeedSource): Flow<FeedPrefs>

    /** Persisted preferences; visit/play-order changes alone must not replace a pager. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pagedFeed(source: FeedSource): Flow<PagingData<EpisodeRow>> =
        observePrefs(source)
            .map { it.filters to it.feedOrder }
            .distinctUntilChanged()
            .flatMapLatest { (filters, order) -> pagedFeed(source, filters, order) }

    /** Ignores minSortDate; Podcast filters are transient and throw IllegalArgumentException. */
    suspend fun setFilters(
        source: FeedSource,
        filters: FeedFilters,
    )

    /** Allowed values: null, 1, 3, 7, 14, 30, 90, 365. Podcast throws IllegalArgumentException. */
    suspend fun setHideOlderThanDays(
        source: FeedSource,
        days: Int?,
    )

    /** Device-local visit time; Podcast has no feed visit state and is a no-op. */
    suspend fun markVisited(
        source: FeedSource,
        leftAt: Long,
    )

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
