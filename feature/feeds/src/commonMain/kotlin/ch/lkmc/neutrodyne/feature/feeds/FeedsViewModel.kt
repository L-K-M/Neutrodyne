// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The All feed's M1a ViewModel (08 Group feed pager → Data and state, reduced to its single
 * page): [feed] is the paged feed with day headers `cachedIn` the VM scope — the three-entry
 * source LRU arrives with M2's pager, when more than one source exists. [uiState] carries the
 * transient chips, the offline flag and the pull-to-refresh indicator (capped at 30 s per 08).
 * Play and download row actions are silent until M4/M6 bind their controllers.
 */
@ViewModelKey(FeedsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class FeedsViewModel(
    feedRepository: FeedRepository,
    private val episodes: EpisodeRepository,
    private val refreshController: RefreshController,
    private val network: NetworkMonitor,
    podcasts: PodcastRepository,
    private val clock: Clock,
) : ViewModel() {
    private val filters = MutableStateFlow(FeedFilters())
    private val userMessages = UserMessages()

    @OptIn(ExperimentalCoroutinesApi::class)
    public val feed: Flow<PagingData<FeedItem>> =
        filters
            .flatMapLatest {
                feedRepository.pagedFeed(FeedSource.All, it, FeedOrder.NEWEST_FIRST)
            }.withDayHeaders(clock.now())
            .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    public val uiState: StateFlow<FeedsUiState> =
        combine(
            filters,
            network.status.map { it.isConnected }.distinctUntilChanged(),
            refreshIndicator(),
            podcasts.observeLibraryTiles(null).map { it.isNotEmpty() },
            userMessages.flow,
        ) { activeFilters, online, refreshing, hasSubscriptions, messages ->
            FeedsUiState(
                filters = activeFilters,
                offline = !online,
                refreshing = refreshing,
                hasSubscriptions = hasSubscriptions,
                messages = messages,
            )
        }.stateIn(viewModelScope, SHARING, FeedsUiState())

    /** Repository-owned row actions (08 EpisodeRow); the route owns navigation and URL opens. */
    public fun onRowAction(action: EpisodeAction) {
        when (action) {
            is EpisodeAction.SetPlayed -> {
                write { episodes.setPlayed(listOf(action.episodeId), action.played) }
            }

            // PlayToggle/PlayNext/PlayLast need the player (M4), DownloadToggle the download
            // engine (M6), CheckAvailability the YouTube engine (M8), Select selection mode (M2).
            else -> {
                Unit
            }
        }
    }

    /** The overflow's "Mark all as played": 02's chain over the whole All feed (R2.6). */
    public fun markAllPlayed() {
        write { episodes.markFeedPlayed(FeedSource.All, null) }
    }

    /** Pull-to-refresh / the desktop's Refresh button (08: All → all feeds, 20 s cooldown). */
    public fun onRefresh() {
        refreshController.refreshFeed(FeedSource.All)
    }

    public fun onFiltersChange(newFilters: FeedFilters) {
        filters.value = newFilters
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

    private fun reportWriteFailed(t: Throwable) {
        Log.w(TAG, t) { "write failed" }
        userMessages.post(UiText.Res(Res.string.write_failed))
    }

    /** 08 Pull to refresh: the indicator tracks `running`, but never longer than 30 s. */
    private fun refreshIndicator(): Flow<Boolean> =
        refreshController
            .observeStatus()
            .map { it.running }
            .distinctUntilChanged()
            .transformLatest { running ->
                if (!running) {
                    emit(false)
                    return@transformLatest
                }
                emit(true)
                // The refresh itself continues past the cap, silently (08).
                delay(REFRESH_INDICATOR_MAX)
                emit(false)
            }

    private companion object {
        const val TAG = "FeedsViewModel"
        val SHARING = SharingStarted.WhileSubscribed(5_000)
        val REFRESH_INDICATOR_MAX = 30.seconds
    }
}
