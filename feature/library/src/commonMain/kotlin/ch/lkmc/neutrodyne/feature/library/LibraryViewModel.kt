// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.TitleCollator
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.domain.groupCountsWindowStart
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibraryDensity
import ch.lkmc.neutrodyne.core.model.settings.LibrarySegment
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Library presentation and batch podcast actions (08 Library). */
@OptIn(ExperimentalCoroutinesApi::class)
@ViewModelKey(LibraryViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class LibraryViewModel(
    private val podcasts: PodcastRepository,
    private val episodes: EpisodeRepository,
    private val refreshController: RefreshController,
    private val unsubscribeUseCase: UnsubscribeUseCase,
    private val settings: SettingsRepository,
    private val collator: TitleCollator,
    network: NetworkMonitor,
    private val groups: GroupRepository,
    feedRepository: FeedRepository,
    clock: Clock,
    private val youTubeHealth: YouTubeHealth,
) : ViewModel() {
    public val feedHealth = youTubeHealth.state

    public fun retryYouTubeFeeds() {
        youTubeHealth.retryNow()
    }

    private val userMessages = UserMessages()

    private data class GroupFilter(
        val groups: List<Group>,
        val memberships: Map<Long, Set<Long>>,
        val value: String,
    )

    private val groupFilter =
        combine(
            groups.observeGroups(),
            groups.observeMemberships(),
            settings.observe(AppearanceSettingKeys.LIBRARY_GROUP_FILTER),
        ) { rows, memberships, stored ->
            GroupFilter(rows, memberships, stored)
        }.distinctUntilChanged().onEach { filter ->
            if (filter.value.isNotEmpty() && filter.value != UNGROUPED_FILTER &&
                filter.groups.none { it.uuid == filter.value }
            ) {
                // Wait for both repository reads before treating a persisted UUID as deleted.
                val result = settings.set(AppearanceSettingKeys.LIBRARY_GROUP_FILTER, "")
                if (result !is Outcome.Success) reportWriteFailed(null)
            }
        }

    private val content =
        groupFilter
            .flatMapLatest { filter ->
                val group = filter.groups.firstOrNull { it.uuid == filter.value }
                podcasts.observeLibraryTiles(group?.id).map { tiles ->
                    val visible =
                        if (filter.value == UNGROUPED_FILTER) {
                            tiles.filter { filter.memberships[it.podcastId].isNullOrEmpty() }
                        } else {
                            tiles
                        }
                    LibraryUiState(
                        tiles = visible.toPersistentList(),
                        groups = filter.groups.toPersistentList(),
                        groupFilter = group?.uuid ?: filter.value.takeIf { it == UNGROUPED_FILTER }.orEmpty(),
                        loaded = true,
                    )
                }
            }.combine(groups.observeMosaics()) { state, mosaics ->
                state.copy(mosaics = mosaics.mapValues { it.value.toPersistentList() }.toPersistentMap())
            }.combine(feedRepository.observeGroupCounts(groupCountsWindowStart(clock.now()))) { state, counts ->
                state.copy(groupCounts = counts.toPersistentMap())
            }.combine(settings.observe(AppearanceSettingKeys.LIBRARY_SEGMENT)) { state, segment ->
                state.copy(segment = segment)
            }.combine(settings.observe(AppearanceSettingKeys.LIBRARY_DENSITY)) { state, density ->
                state.copy(density = density)
            }

    public val uiState: StateFlow<LibraryUiState> =
        combine(
            content,
            settings.observe(AppearanceSettingKeys.LIBRARY_TITLES),
            settings.observe(AppearanceSettingKeys.LIBRARY_SORT),
            network.status.map { it.isConnected }.distinctUntilChanged(),
            userMessages.flow,
        ) { content, showTitles, sort, online, messages ->
            content.copy(
                tiles = sortTiles(content.tiles, sort).toPersistentList(),
                showTitles = showTitles,
                sort = sort,
                offline = !online,
                messages = messages,
            )
        }.stateIn(viewModelScope, SHARING, LibraryUiState())

    public fun setSegment(segment: LibrarySegment) {
        writeSetting { settings.set(AppearanceSettingKeys.LIBRARY_SEGMENT, segment) }
    }

    /** Library storage uses the raw UUID, unlike the Feeds source encoding. */
    public fun setGroupFilter(value: String) {
        writeSetting { settings.set(AppearanceSettingKeys.LIBRARY_GROUP_FILTER, value) }
    }

    /** Navigate only after the device selection was written successfully. */
    public suspend fun selectGroup(groupId: Long): Boolean {
        val group = uiState.value.groups.firstOrNull { it.id == groupId } ?: return false
        val result =
            suspendRunCatching {
                settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, FeedSourceRef.Group(group.uuid).encode())
            }
        if (result.getOrNull() is Outcome.Success) return true

        reportWriteFailed(result.exceptionOrNull())
        return false
    }

    public fun refreshSelection(ids: List<Long>) {
        if (ids.isEmpty()) return

        refreshController.refreshNow(RefreshScope.Podcasts(ids.distinct()))
    }

    public fun markSelectionPlayed(ids: List<Long>) {
        write { ids.distinct().forEach { episodes.markFeedPlayed(FeedSource.Podcast(it), null) } }
    }

    public suspend fun downloadedCount(ids: List<Long>): Int = podcasts.downloadedEpisodeIds(ids.distinct()).size

    public fun unsubscribeSelection(ids: List<Long>) {
        if (ids.isEmpty()) return

        write { unsubscribeUseCase(ids.distinct()) }
    }

    public fun setDensity(density: LibraryDensity) {
        writeSetting { settings.set(AppearanceSettingKeys.LIBRARY_DENSITY, density) }
    }

    public fun setSort(sort: LibrarySort) {
        writeSetting { settings.set(AppearanceSettingKeys.LIBRARY_SORT, sort) }
    }

    public fun setShowTitles(show: Boolean) {
        writeSetting { settings.set(AppearanceSettingKeys.LIBRARY_TITLES, show) }
    }

    /** The tile overflow's "Refresh" (03 Per-feed states: forced, user-initiated). */
    public fun refreshPodcast(podcastId: Long) {
        refreshController.refreshFeed(FeedSource.Podcast(podcastId))
    }

    /** The tile overflow's "Mark all as played". */
    public fun markAllPlayed(podcastId: Long) {
        write { episodes.markFeedPlayed(FeedSource.Podcast(podcastId), null) }
    }

    /** The downloaded-episode count the unsubscribe confirmation names (08's dialog wording). */
    public suspend fun downloadedCount(podcastId: Long): Int = podcasts.downloadedEpisodeIds(listOf(podcastId)).size

    public fun unsubscribe(podcastId: Long) {
        write { unsubscribeUseCase(listOf(podcastId)) }
    }

    /** The screen acks a shown snackbar so its [UserMessage] leaves the state. */
    public fun onMessageShown(id: Long) {
        userMessages.shown(id)
    }

    /** A failed write is logged and surfaces as a snackbar — it never escapes the scope. */
    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            suspendRunCatching { block() }.onFailure(::reportWriteFailed)
        }
    }

    /** A settings write: the repository reports failures as `Outcome.Failure`, not a throw. */
    private fun writeSetting(block: suspend () -> Outcome<Unit, *>) {
        viewModelScope.launch {
            val result = suspendRunCatching { block() }
            if (result.getOrNull() !is Outcome.Success) reportWriteFailed(result.exceptionOrNull())
        }
    }

    private fun reportWriteFailed(t: Throwable?) {
        Log.w(TAG, t) { "write failed" }
        userMessages.post(UiText.Res(Res.string.write_failed))
    }

    private fun sortTiles(
        tiles: List<LibraryTile>,
        sort: LibrarySort,
    ): List<LibraryTile> =
        when (sort) {
            LibrarySort.TITLE -> {
                tiles.sortedWith { a, b -> collator.compare(a.displayTitle, b.displayTitle) }
            }

            LibrarySort.RECENTLY_UPDATED -> {
                tiles.sortedByDescending { it.latestEpisodeAt ?: Long.MIN_VALUE }
            }

            LibrarySort.MOST_UNPLAYED -> {
                tiles.sortedWith(
                    compareByDescending<LibraryTile> { it.unplayedCount }.then {
                        a,
                        b,
                        ->
                        collator.compare(a.displayTitle, b.displayTitle)
                    },
                )
            }

            LibrarySort.RECENTLY_ADDED -> {
                tiles.sortedByDescending { it.subscribedAt }
            }
        }

    private companion object {
        const val TAG = "LibraryViewModel"
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
