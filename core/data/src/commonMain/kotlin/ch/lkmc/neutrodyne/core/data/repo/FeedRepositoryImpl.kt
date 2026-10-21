// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.groupCountsWindowStart
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedTab
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.model.VirtualCounts
import ch.lkmc.neutrodyne.core.model.isSupportedFeedAgeLimit
import ch.lkmc.neutrodyne.core.model.settings.GroupsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Feed queries stay in FeedQueryBuilder; preferences never broaden its observed entity set. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class FeedRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : FeedRepository {
    private val visitMutex = Mutex()

    override fun observeTabs(): Flow<List<FeedTab>> =
        combine(
            db.groupDao().observeGroups(),
            db.groupDao().observeHasUngroupedPodcasts(),
            settings.observe(GroupsSettingKeys.SHOW_UNGROUPED_TAB),
        ) { groups, hasUngrouped, showUngrouped ->
            buildList {
                add(FeedTab(FeedSource.All, "All", null, null, null))
                groups.filter { it.group.showAsTab }.forEach { row ->
                    val group = row.group
                    add(FeedTab(FeedSource.Group(group.id), group.name, group.colorArgb, group.iconKey, group.uuid))
                }
                if (showUngrouped && hasUngrouped) {
                    add(FeedTab(FeedSource.Ungrouped, "Ungrouped", null, null, null))
                }
            }
        }.distinctUntilChanged()

    override fun observePrefs(source: FeedSource): Flow<FeedPrefs> {
        val stored =
            when (source) {
                is FeedSource.Group -> {
                    db.groupDao().observeGroup(source.groupId).map { row ->
                        row?.group?.let { group ->
                            FeedPrefs(
                                group.feedOrder,
                                group.playOrder,
                                FeedFilters.fromFlags(group.filterFlags, group.mediaFilter),
                                group.hideOlderThanDays,
                                group.lastViewedAt,
                            )
                        } ?: FeedPrefs()
                    }
                }

                is FeedSource.Podcast -> {
                    db.podcastDao().observeById(source.podcastId).map { podcast ->
                        val order =
                            podcast?.episodeOrder ?: if (podcast?.showType == ShowType.SERIAL) {
                                FeedOrder.OLDEST_FIRST
                            } else {
                                FeedOrder.NEWEST_FIRST
                            }
                        FeedPrefs(feedOrder = order, playOrder = order)
                    }
                }

                FeedSource.All, FeedSource.Ungrouped -> {
                    observeVirtualPrefs(source)
                }
            }
        return combine(stored, hours()) { prefs, _ -> prefs.at(clock.now()) }.distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeGroupCounts(sinceMs: Long): Flow<Map<Long, FeedCounts>> =
        countWindows(sinceMs)
            .flatMapLatest { (since, now) ->
                db
                    .feedDao()
                    .observeGroupCounts(FeedQueryBuilder.groupCounts(since, now))
                    .map { rows -> rows.associate { it.groupId to it.counts } }
            }.distinctUntilChanged()

    override fun observeVirtualCounts(sinceMs: Long): Flow<VirtualCounts> =
        combine(
            observeVirtualCounts(FeedSource.All, sinceMs),
            observeVirtualCounts(FeedSource.Ungrouped, sinceMs),
        ) { all, ungrouped -> VirtualCounts(all, ungrouped) }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeVirtualCounts(
        source: FeedSource,
        sinceMs: Long,
    ): Flow<FeedCounts> =
        combine(countWindows(sinceMs), observePrefs(source)) { (since, _), prefs ->
            maxOf(since, prefs.filters.minSortDate ?: since) to requireNotNull(prefs.lastViewedAt)
        }.distinctUntilChanged().flatMapLatest { (since, visit) ->
            db.feedDao().observeVirtualCounts(FeedQueryBuilder.virtualCounts(source, since, visit))
        }

    override suspend fun countUnplayed(
        source: FeedSource,
        sortDateBefore: Long?,
    ): Int = db.feedDao().countUnplayed(FeedQueryBuilder.countUnplayed(source, sortDateBefore))

    private fun countWindows(sinceMs: Long): Flow<Pair<Long, Long>> =
        flow {
            val initialHour = clock.now().let { it - it.mod(FeedPrefs.HOUR_MS) }
            val initialSince = sinceMs - sinceMs.mod(FeedPrefs.HOUR_MS)
            emitAll(
                hours().map { now ->
                    val hour = now - now.mod(FeedPrefs.HOUR_MS)
                    val windowStart = groupCountsWindowStart(hour)
                    maxOf(initialSince + hour - initialHour, windowStart) to hour
                },
            )
        }

    private fun hours(): Flow<Long> =
        flow {
            while (true) {
                val now = clock.now()
                emit(now)
                delay(FeedPrefs.HOUR_MS - now.mod(FeedPrefs.HOUR_MS))
            }
        }

    private fun observeVirtualPrefs(source: FeedSource): Flow<FeedPrefs> =
        flow {
            val visitKey = visitKey(source)
            visitMutex.withLock {
                if (settings.get(visitKey) == 0L) persist(visitKey, clock.now())
            }
            emitAll(
                combine(
                    settings.observe(flagsKey(source)),
                    settings.observe(mediaKey(source)),
                    settings.observe(ageKey(source)),
                    settings.observe(visitKey),
                ) { flags, media, days, visited ->
                    FeedPrefs(
                        filters = FeedFilters.fromFlags(flags, media),
                        hideOlderThanDays = days.takeUnless { it == 0 },
                        lastViewedAt = visited,
                    )
                },
            )
        }

    override suspend fun setFilters(
        source: FeedSource,
        filters: FeedFilters,
    ) {
        when (source) {
            is FeedSource.Podcast -> {
                throw IllegalArgumentException("Podcast filters are transient")
            }

            is FeedSource.Group -> {
                db.groupDao().setFilters(source.groupId, filters.toFlags(), filters.media, clock.now())
            }

            FeedSource.All, FeedSource.Ungrouped -> {
                persist(flagsKey(source), filters.toFlags())
                persist(mediaKey(source), filters.media)
            }
        }
    }

    override suspend fun setHideOlderThanDays(
        source: FeedSource,
        days: Int?,
    ) {
        require(source !is FeedSource.Podcast) { "Podcast filters are transient" }
        require(isSupportedFeedAgeLimit(days)) { "Unsupported feed age limit: $days" }
        when (source) {
            is FeedSource.Group -> db.groupDao().setHideOlderThanDays(source.groupId, days, clock.now())
            FeedSource.All, FeedSource.Ungrouped -> persist(ageKey(source), days ?: 0)
            is FeedSource.Podcast -> error("Validated above")
        }
    }

    override suspend fun markVisited(
        source: FeedSource,
        leftAt: Long,
    ) {
        when (source) {
            is FeedSource.Group -> db.groupDao().markVisited(source.groupId, leftAt)
            FeedSource.All, FeedSource.Ungrouped -> visitMutex.withLock { persist(visitKey(source), leftAt) }
            is FeedSource.Podcast -> Unit
        }
    }

    // The canonical feed writers return Unit; a failed settings write must propagate to callers.
    private suspend fun <T : Any> persist(
        key: SettingKey<T>,
        value: T,
    ) {
        when (val result = settings.set(key, value)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> throw IllegalStateException("Could not persist ${key.name}: ${result.error}")
        }
    }

    private fun flagsKey(source: FeedSource) =
        when (source) {
            FeedSource.All -> GroupsSettingKeys.ALL_FILTER_FLAGS
            FeedSource.Ungrouped -> GroupsSettingKeys.UNGROUPED_FILTER_FLAGS
            else -> error("Not a virtual feed: $source")
        }

    private fun mediaKey(source: FeedSource) =
        when (source) {
            FeedSource.All -> GroupsSettingKeys.ALL_MEDIA_FILTER
            FeedSource.Ungrouped -> GroupsSettingKeys.UNGROUPED_MEDIA_FILTER
            else -> error("Not a virtual feed: $source")
        }

    private fun ageKey(source: FeedSource) =
        when (source) {
            FeedSource.All -> GroupsSettingKeys.ALL_HIDE_OLDER_THAN_DAYS
            FeedSource.Ungrouped -> GroupsSettingKeys.UNGROUPED_HIDE_OLDER_THAN_DAYS
            else -> error("Not a virtual feed: $source")
        }

    private fun visitKey(source: FeedSource) =
        when (source) {
            FeedSource.All -> GroupsSettingKeys.ALL_LAST_VIEWED_AT
            FeedSource.Ungrouped -> GroupsSettingKeys.UNGROUPED_LAST_VIEWED_AT
            else -> error("Not a virtual feed: $source")
        }

    override fun pagedFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): Flow<PagingData<EpisodeRow>> =
        Pager(PAGING_CONFIG) {
            db.feedDao().page(FeedQueryBuilder.page(source, filters, order))
        }.flow.map { data -> data.map { it.toEpisodeRow() } }

    override suspend fun setFeedOrder(
        source: FeedSource,
        order: FeedOrder,
    ) {
        when (source) {
            is FeedSource.Podcast -> {
                db.podcastDao().setEpisodeOrder(source.podcastId, order)
            }

            is FeedSource.Group -> {
                db.groupDao().setFeedOrder(source.groupId, order, clock.now())
            }

            FeedSource.All, FeedSource.Ungrouped -> {
                throw IllegalArgumentException("feed order is fixed NEWEST_FIRST for $source")
            }
        }
    }

    private companion object {
        /** 08 Paging hand-off: page size and prefetch distance share one value. */
        const val PAGE_SIZE = 40
        const val INITIAL_LOAD_SIZE = 80
        const val MAX_SIZE = 400

        val PAGING_CONFIG =
            PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = true,
                maxSize = MAX_SIZE,
            )
    }
}
