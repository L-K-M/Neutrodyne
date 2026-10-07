// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [FeedRepository] (09 `:core:testing` inventory): [rows] drives a single-shot
 * `PagingData.from` page (deterministic — the screens under test never page further) and
 * [orders] persists per-source order choices.
 */
class FakeFeedRepository : FeedRepository {
    val rows = MutableStateFlow<List<EpisodeRow>>(emptyList())
    val orders = MutableStateFlow<Map<FeedSource, FeedOrder>>(emptyMap())

    /** Latest arguments — `pagedFeed` is keyed by (source, filters, order). */
    var lastRequest: Triple<FeedSource, FeedFilters, FeedOrder>? = null
        private set

    override fun pagedFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): Flow<PagingData<EpisodeRow>> {
        lastRequest = Triple(source, filters, order)
        return rows.map { PagingData.from(it) }
    }

    override suspend fun setFeedOrder(
        source: FeedSource,
        order: FeedOrder,
    ) {
        if (source is FeedSource.All || source is FeedSource.Ungrouped) {
            throw IllegalArgumentException("feed order is fixed for $source")
        }
        orders.value += source to order
    }
}
