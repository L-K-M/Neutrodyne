// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.EpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
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
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.membership_changed
import ch.lkmc.neutrodyne.core.ui.resources.podcast_removed_from_group
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.collections.immutable.toImmutableList
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
        private val groups: GroupRepository,
        private val refreshController: RefreshController,
        private val unsubscribe: UnsubscribeUseCase,
        network: NetworkMonitor,
        liveStateSource: EpisodeLiveStateSource,
    ) : ViewModel() {
        private val filters = MutableStateFlow(FeedFilters())
        private val detail = podcasts.observePodcast(podcastId)
        private val userMessages = UserMessages()
        private val visibleIds = MutableStateFlow<Set<Long>>(emptySet())

        /** Shown Undo snackbar's message id → the membership it may restore (05 Membership). */
        private val membershipUndos = mutableMapOf<Long, Long>()

        public val live: StateFlow<Map<Long, RowLive>> =
            liveStateSource.observe(visibleIds).stateIn(viewModelScope, SHARING, emptyMap())

        public fun onVisibleIds(ids: Set<Long>) {
            visibleIds.value = ids
        }

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
            }.combine(groups.observeGroupsOf(podcastId)) { state, memberships ->
                state.copy(groups = memberships.toImmutableList())
            }.combine(userMessages.flow) { state, messages ->
                state.copy(messages = messages)
            }.stateIn(viewModelScope, SHARING, PodcastUiState())

        /** Repository-owned row actions; the route owns navigation and external URLs. */
        public fun onRowAction(action: EpisodeAction) {
            when (action) {
                is EpisodeAction.SetPlayed -> {
                    write { episodes.setPlayed(listOf(action.episodeId), action.played) }
                }

                // PlayToggle/queue actions need the player (M4); DownloadToggle needs M6.
                else -> {
                    Unit
                }
            }
        }

        public fun onFiltersChange(newFilters: FeedFilters) {
            filters.value = newFilters
        }

        /** The "Newest first" chip (05: persisted in `podcast.episodeOrder`). */
        public fun setOrder(order: FeedOrder) {
            write { feedRepository.setFeedOrder(FeedSource.Podcast(podcastId), order) }
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
            write { podcasts.retry(podcastId) }
        }

        /** The "Enter password" dialog's commit (03 Basic auth; M1b API already bound). */
        public suspend fun setCredentials(credentials: BasicCredentials): Outcome<Unit, AddPodcastError>? =
            suspendRunCatching { podcasts.setCredentials(podcastId, credentials) }
                .onFailure(::reportWriteFailed)
                .getOrNull()

        public fun unsubscribe() {
            write { unsubscribe(listOf(podcastId)) }
        }

        public fun markAllPlayed() {
            write { episodes.markFeedPlayed(FeedSource.Podcast(podcastId), null) }
        }

        /**
         * A membership chip's trailing "x" (M2, D6): drops this podcast from [group] through
         * [GroupRepository.applyMembership] and offers an Undo snackbar bound to exactly that
         * membership. A concurrent delete returns [GroupError.NotFound] — the requested end
         * state then already holds (or can't be produced), the observed flow already reflects
         * it, and nothing needs to surface.
         */
        public fun removeFromGroup(group: Group) {
            viewModelScope.launch {
                val outcome =
                    suspendRunCatching {
                        groups.applyMembership(setOf(podcastId), emptySet(), setOf(group.id))
                    }.onFailure(::reportWriteFailed)
                        .getOrNull() ?: return@launch
                when (outcome) {
                    is Outcome.Success -> {
                        val message =
                            userMessages.post(
                                UiText.Res(
                                    Res.string.podcast_removed_from_group,
                                    listOf(group.name),
                                ),
                                action = UiText.Res(Res.string.action_undo),
                            )
                        membershipUndos[message.id] = group.id
                    }

                    is Outcome.Failure -> {
                        if (outcome.error == GroupError.NotFound) {
                            Log.i(TAG) { "membership remove lost the race" }
                        } else {
                            userMessages.post(UiText.Res(Res.string.write_failed))
                        }
                    }
                }
            }
        }

        /** A snackbar action tap: only a pending chip-removal Undo has one today. */
        public fun onMessageAction(id: Long) {
            val groupId = membershipUndos.remove(id) ?: return
            viewModelScope.launch {
                val outcome =
                    suspendRunCatching {
                        groups.applyMembership(setOf(podcastId), setOf(groupId), emptySet())
                    }.onFailure(::reportWriteFailed)
                        .getOrNull() ?: return@launch
                // NotFound means the group itself is gone — the restore can't be produced.
                if (outcome is Outcome.Failure) {
                    userMessages.post(UiText.Res(Res.string.membership_changed))
                }
            }
        }

        /** The screen acks a shown snackbar so its [UserMessage] leaves the state. */
        public fun onMessageShown(id: Long) {
            // A dismissed Undo snackbar can never fire its action; drop the stale mapping.
            membershipUndos.remove(id)
            userMessages.shown(id)
        }

        /** The unsubscribe confirmation's downloaded-episode count (08's wording). */
        public suspend fun downloadedCount(): Int = podcasts.downloadedEpisodeIds(listOf(podcastId)).size

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

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        public fun interface Factory : ManualViewModelAssistedFactory {
            public fun create(podcastId: Long): PodcastViewModel
        }

        private companion object {
            const val TAG = "PodcastViewModel"
            val SHARING = SharingStarted.WhileSubscribed(5_000)
        }
    }

private fun RefreshStatus.covers(podcastId: Long): Boolean =
    running && scope is RefreshScope.Podcasts && podcastId in (scope as RefreshScope.Podcasts).ids
