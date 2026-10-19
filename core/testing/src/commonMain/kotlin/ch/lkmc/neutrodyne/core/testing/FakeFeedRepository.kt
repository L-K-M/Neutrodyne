// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.model.DownloadAllEstimate
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedTab
import ch.lkmc.neutrodyne.core.model.VirtualCounts
import ch.lkmc.neutrodyne.core.model.isSupportedFeedAgeLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/** In-memory feeds with fixture tabs/rows and persisted per-source preferences. */
class FakeFeedRepository(
    private val clock: Clock = TestClock(),
) : FeedRepository {
    val rows = MutableStateFlow<List<EpisodeRow>>(emptyList())
    val orders = MutableStateFlow<Map<FeedSource, FeedOrder>>(emptyMap())
    val prefs = MutableStateFlow<Map<FeedSource, FeedPrefs>>(emptyMap())
    val tabs = MutableStateFlow(listOf(FeedTab(FeedSource.All, "All", null, null, null)))

    val groupCounts = MutableStateFlow<Map<Long, FeedCounts>>(emptyMap())
    val groupCountsSinceMs = mutableListOf<Long>()
    val virtualCounts = MutableStateFlow(VirtualCounts())
    val unplayedCounts = mutableMapOf<Pair<FeedSource, Long?>, Int>()
    val downloadAllEstimates = mutableMapOf<FeedSource, DownloadAllEstimate>()
    val downloadAllRequests = mutableListOf<FeedSource>()

    override suspend fun downloadAllEstimate(source: FeedSource): DownloadAllEstimate {
        downloadAllRequests += source
        return downloadAllEstimates[source] ?: DownloadAllEstimate(emptyList(), 0, 0, 0, false)
    }

    override fun observeGroupCounts(sinceMs: Long): Flow<Map<Long, FeedCounts>> {
        groupCountsSinceMs += sinceMs
        return groupCounts
    }

    override fun observeVirtualCounts(sinceMs: Long): Flow<VirtualCounts> = virtualCounts

    override suspend fun countUnplayed(
        source: FeedSource,
        sortDateBefore: Long?,
    ): Int = unplayedCounts[source to sortDateBefore] ?: 0

    override fun observeTabs(): Flow<List<FeedTab>> = tabs

    override fun observePrefs(source: FeedSource): Flow<FeedPrefs> =
        combine(
            prefs,
            orders,
            flow {
                while (true) {
                    val now = clock.now()
                    emit(now)
                    delay(FeedPrefs.HOUR_MS - now.mod(FeedPrefs.HOUR_MS))
                }
            },
        ) { values, savedOrders, _ ->
            val value = values[source] ?: FeedPrefs()
            val order = savedOrders[source] ?: value.feedOrder
            if (source is FeedSource.Podcast) {
                FeedPrefs(feedOrder = order, playOrder = order)
            } else {
                value.copy(feedOrder = order).at(clock.now())
            }
        }.onStart {
            if (source == FeedSource.All || source == FeedSource.Ungrouped) {
                prefs.update { values ->
                    val value = values[source] ?: FeedPrefs()
                    values + (source to value.copy(lastViewedAt = value.lastViewedAt ?: clock.now()))
                }
            }
        }.distinctUntilChanged()

    override suspend fun setFilters(
        source: FeedSource,
        filters: FeedFilters,
    ) {
        require(source !is FeedSource.Podcast) { "Podcast filters are transient" }
        writeError?.let { throw it }
        prefs.update { it + (source to (it[source] ?: FeedPrefs()).copy(filters = filters.copy(minSortDate = null))) }
    }

    override suspend fun setHideOlderThanDays(
        source: FeedSource,
        days: Int?,
    ) {
        require(source !is FeedSource.Podcast) { "Podcast filters are transient" }
        require(isSupportedFeedAgeLimit(days)) { "Unsupported feed age limit: $days" }
        writeError?.let { throw it }
        prefs.update { it + (source to (it[source] ?: FeedPrefs()).copy(hideOlderThanDays = days)) }
    }

    override suspend fun markVisited(
        source: FeedSource,
        leftAt: Long,
    ) {
        if (source is FeedSource.Podcast) return
        writeError?.let { throw it }
        prefs.update { it + (source to (it[source] ?: FeedPrefs()).copy(lastViewedAt = leftAt)) }
    }

    /** Latest arguments — `pagedFeed` is keyed by (source, filters, order). */
    var lastRequest: Triple<FeedSource, FeedFilters, FeedOrder>? = null
        private set

    /** Paging generations and their cancellation, for cache-lifetime assertions. */
    val pagingRequests = mutableListOf<Triple<FeedSource, FeedFilters, FeedOrder>>()
    val cancelledPagingRequests = mutableListOf<Triple<FeedSource, FeedFilters, FeedOrder>>()

    /** When set, preference writers throw it (the Room implementation's failure path). */
    var writeError: Throwable? = null

    override fun pagedFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): Flow<PagingData<EpisodeRow>> {
        val request = Triple(source, filters, order)
        lastRequest = request
        pagingRequests += request
        return rows.map { PagingData.from(it) }.onCompletion { cause ->
            if (cause is CancellationException) cancelledPagingRequests += request
        }
    }

    override suspend fun setFeedOrder(
        source: FeedSource,
        order: FeedOrder,
    ) {
        writeError?.let { throw it }
        if (source is FeedSource.All || source is FeedSource.Ungrouped) {
            throw IllegalArgumentException("feed order is fixed for $source")
        }
        orders.value += source to order
    }
}
