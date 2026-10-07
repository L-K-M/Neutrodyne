// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The podcast detail ViewModel (08 Podcast detail), key-scoped by [podcastId] through Metro's
 * assisted [Factory] (01's key-based ViewModel convention). [feed] pages `FeedSource.Podcast`
 * with the transient chips and the persisted `episodeOrder`; "Load older" drives 03's RFC 5005
 * session. Playback and download row actions wait for M4/M6.
 */
public class PodcastViewModel
    @AssistedInject
    constructor(
        @Assisted private val podcastId: Long,
        private val podcasts: PodcastRepository,
        private val feedRepository: FeedRepository,
        private val episodes: EpisodeRepository,
        private val refreshController: RefreshController,
        private val unsubscribe: UnsubscribeUseCase,
        network: NetworkMonitor,
    ) : ViewModel() {
        private val filters = MutableStateFlow(FeedFilters())
        private val detail = podcasts.observePodcast(podcastId)

        @OptIn(ExperimentalCoroutinesApi::class)
        public val feed: Flow<PagingData<EpisodeRow>> =
            combine(filters, detail.map { it.effectiveOrder() }.distinctUntilChanged()) { f, order ->
                f to order
            }.flatMapLatest { (f, order) ->
                feedRepository.pagedFeed(FeedSource.Podcast(podcastId), f, order)
            }.cachedIn(viewModelScope)

        public val uiState: StateFlow<PodcastUiState> =
            combine(
                detail,
                podcasts.observeFeedInfo(podcastId),
                filters,
                network.status.map { it.isConnected }.distinctUntilChanged(),
                refreshController.observeStatus().map { it.covers(podcastId) }.distinctUntilChanged(),
            ) { podcast, feedInfo, f, online, refreshing ->
                PodcastUiState(
                    detail = podcast,
                    feedUrl = feedInfo?.feedUrl,
                    loaded = true,
                    filters = f,
                    offline = !online,
                    refreshing = refreshing,
                )
            }.stateIn(viewModelScope, SHARING, PodcastUiState())

        /** Repository-owned row actions; the route owns navigation and external URLs. */
        public fun onRowAction(action: EpisodeAction) {
            when (action) {
                is EpisodeAction.SetPlayed ->
                    viewModelScope.launch {
                        episodes.setPlayed(listOf(action.episodeId), action.played)
                    }
                // PlayToggle/queue actions need the player (M4); DownloadToggle needs M6.
                else -> Unit
            }
        }

        public fun onFiltersChange(newFilters: FeedFilters) {
            filters.value = newFilters
        }

        /** The "Newest first" chip (05: persisted in `podcast.episodeOrder`). */
        public fun setOrder(order: FeedOrder) {
            viewModelScope.launch {
                feedRepository.setFeedOrder(FeedSource.Podcast(podcastId), order)
            }
        }

        /** The top bar's refresh (03: forced, user-initiated). */
        public fun onRefresh() {
            refreshController.refreshFeed(FeedSource.Podcast(podcastId))
        }

        /** The "Load older episodes" row (03 RFC 5005; runs while `hasOlderPages`). */
        public fun loadOlder() {
            refreshController.loadOlderEpisodes(podcastId)
        }

        /** The banner's "Try again" (03 Per-feed states: clears `gone`/`needsCredentials`). */
        public fun retryFeed() {
            viewModelScope.launch { podcasts.retry(podcastId) }
        }

        /** The "Enter password" dialog's commit (03 Basic auth; M1b API already bound). */
        public suspend fun setCredentials(
            credentials: BasicCredentials,
        ): Outcome<Unit, AddPodcastError> = podcasts.setCredentials(podcastId, credentials)

        public fun unsubscribe() {
            viewModelScope.launch { unsubscribe(listOf(podcastId)) }
        }

        public fun markAllPlayed() {
            viewModelScope.launch { episodes.markFeedPlayed(FeedSource.Podcast(podcastId), null) }
        }

        /** The unsubscribe confirmation's downloaded-episode count (08's wording). */
        public suspend fun downloadedCount(): Int =
            podcasts.downloadedEpisodeIds(listOf(podcastId)).size

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        public fun interface Factory : ManualViewModelAssistedFactory {
            public fun create(podcastId: Long): PodcastViewModel
        }

        private companion object {
            val SHARING = SharingStarted.WhileSubscribed(5_000)
        }
    }

private fun PodcastDetail?.effectiveOrder(): FeedOrder =
    this?.episodeOrder
        ?: if (this?.showType == ShowType.SERIAL) FeedOrder.OLDEST_FIRST else FeedOrder.NEWEST_FIRST

private fun RefreshStatus.covers(podcastId: Long): Boolean =
    running && scope is RefreshScope.Podcasts && podcastId in (scope as RefreshScope.Podcasts).ids
