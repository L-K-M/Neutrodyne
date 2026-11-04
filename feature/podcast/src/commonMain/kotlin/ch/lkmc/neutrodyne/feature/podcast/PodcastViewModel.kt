// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.EpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.MarkFeedPlayedResult
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.domain.YouTubeChannelRepository
import ch.lkmc.neutrodyne.core.domain.YouTubeExternalOpen
import ch.lkmc.neutrodyne.core.domain.YouTubeOpenContext
import ch.lkmc.neutrodyne.core.model.AVAILABLE_FEED_FILTER_CAPABILITIES
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.model.withCapabilities
import ch.lkmc.neutrodyne.core.ui.DownloadAllController
import ch.lkmc.neutrodyne.core.ui.DownloadRequestHandler
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeSelectionController
import ch.lkmc.neutrodyne.core.ui.ExternalOpenHandler
import ch.lkmc.neutrodyne.core.ui.MarkFeedPlayedController
import ch.lkmc.neutrodyne.core.ui.MarkPlayedOutcome
import ch.lkmc.neutrodyne.core.ui.PlaybackActions
import ch.lkmc.neutrodyne.core.ui.RowCaps
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.membership_changed
import ch.lkmc.neutrodyne.core.ui.resources.podcast_removed_from_group
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
        private val channels: YouTubeChannelRepository,
        private val feedRepository: FeedRepository,
        private val episodes: EpisodeRepository,
        private val groups: GroupRepository,
        private val refreshController: RefreshController,
        private val unsubscribe: UnsubscribeUseCase,
        private val settings: SettingsRepository,
        private val playbackController: PlaybackController,
        playbackStates: PlaybackStateSource,
        platformInfo: PlatformInfo,
        network: NetworkMonitor,
        liveStateSource: EpisodeLiveStateSource,
        clock: Clock,
        downloadController: DownloadController,
        externalOpenService: YouTubeExternalOpen,
        capabilities: YouTubeCapabilitiesSource,
    ) : ViewModel() {
        init {
            viewModelScope.launch {
                suspendRunCatching { channels.ensureChannelArt(podcastId) }
                    .onFailure { Log.w("PodcastViewModel", it) { "Channel artwork refresh failed" } }
            }
        }

        private val filters = MutableStateFlow(FeedFilters())
        private val detail = podcasts.observePodcast(podcastId)
        private val userMessages = UserMessages()
        public val externalOpen =
            ExternalOpenHandler(
                viewModelScope,
                userMessages,
                mark = { externalOpenService.onOpened(it, YouTubeOpenContext.EXTERNAL_OR_UNPLAYABLE) },
                undo = { externalOpenService.undo(it) },
                onFailure = ::reportWriteFailed,
            )
        public val downloads =
            DownloadRequestHandler(
                viewModelScope,
                downloadController,
                userMessages,
                allowAlways = {
                    check(
                        settings.set(DownloadSettingKeys.MANUAL_METERED, ManualMeteredPolicy.ALWAYS) is Outcome.Success,
                    )
                },
                markNotificationPrompted = {
                    check(settings.set(DownloadSettingKeys.NOTIFICATION_PROMPTED, true) is Outcome.Success)
                },
                onFailure = ::reportWriteFailed,
            )

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
            }.combine(capabilities.capabilities) { state, caps ->
                state.copy(
                    rowCaps =
                        RowCaps(
                            caps.inAppPlayback,
                            caps.downloads,
                            caps.enrichment,
                            null,
                            state.offline,
                        ),
                )
            }.stateIn(viewModelScope, SHARING, PodcastUiState())

        public val episodeSelection =
            EpisodeSelectionController(viewModelScope, episodes::setPlayed, ::reportWriteFailed)

        /**
         * Shared start/queue/consent handling (08 Issues, results and events); the route also
         * resolves playback snackbar actions through `playback.onMessageAction`.
         */
        public val playback =
            PlaybackActions(
                viewModelScope,
                playbackController,
                playbackStates,
                userMessages,
                platformInfo.kind,
                allowMeteredStreaming = ::allowMeteredStreaming,
                onFailure = ::reportWriteFailed,
            )

        /** Selection mode's "Play next"/"Play last" (08 Selection mode); clears the selection. */
        public fun playSelectionNext() {
            val ids = episodeSelection.ids.value.toList()
            playback.playNext(ids) { episodeSelection.deselect(ids) }
        }

        public fun playSelectionLast() {
            val ids = episodeSelection.ids.value.toList()
            playback.playLast(ids) { episodeSelection.deselect(ids) }
        }

        /** Repository-owned row actions; the route owns navigation and external URLs. */
        public fun onRowAction(action: EpisodeAction) {
            when (action) {
                is EpisodeAction.Select -> {
                    episodeSelection.toggle(action.episodeId)
                }

                is EpisodeAction.SetPlayed -> {
                    write { episodes.setPlayed(listOf(action.episodeId), action.played) }
                }

                // 05 Start rules: this screen's source is its own podcast with the transient
                // chips and the effective order; the tapped row is the start anchor.
                is EpisodeAction.PlayToggle -> {
                    playback.playToggle(action.episodeId, feedTitle()) {
                        playbackController.playFeed(
                            FeedSource.Podcast(podcastId),
                            filters.value,
                            uiState.value.effectiveOrder,
                            action.episodeId,
                        )
                    }
                }

                is EpisodeAction.DownloadToggle -> {
                    downloads.toggle(action.episodeId)
                }

                is EpisodeAction.PlayNext -> {
                    playback.playNext(listOf(action.episodeId))
                }

                is EpisodeAction.PlayLast -> {
                    playback.playLast(listOf(action.episodeId))
                }

                // Availability checks need the YouTube engine (M8).
                else -> {
                    Unit
                }
            }
        }

        public fun onFiltersChange(newFilters: FeedFilters) {
            filters.value = newFilters.withCapabilities(AVAILABLE_FEED_FILTER_CAPABILITIES)
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

        public val downloadAll: DownloadAllController =
            DownloadAllController(
                viewModelScope,
                feedRepository::downloadAllEstimate,
                downloadController,
                downloads::askNotifications,
            )

        public val markPlayed: MarkFeedPlayedController =
            MarkFeedPlayedController(viewModelScope, clock, feedRepository::countUnplayed) { source, before, count ->
                when (val result = episodes.markFeedPlayed(source, before, count)) {
                    is MarkFeedPlayedResult.Played -> {
                        MarkPlayedOutcome.Played
                    }

                    is MarkFeedPlayedResult.SelectionChanged -> {
                        MarkPlayedOutcome.SelectionChanged(result.actualCount)
                    }
                }
            }

        /** The overflow's "Mark all as played…" (08): opens the counted D9 confirmation. */
        public fun markAllPlayed() {
            markPlayed.open(
                FeedSource.Podcast(podcastId),
                UiText.Raw(uiState.value.detail?.displayTitle ?: return),
            )
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
            playback.onMessageShown(id)
            externalOpen.shown(id)
            userMessages.shown(id)
        }

        /** The refused YouTube start's watch URL, when the episode still has one (08). */
        public suspend fun youtubeWatchUrl(episodeId: Long): String? =
            suspendRunCatching { episodes.observeEpisode(episodeId).first()?.externalMediaId }
                .getOrNull()
                ?.let(YouTubeLinks::watch)

        /** The show's title for "Nothing unplayed in '{context}'" (08). */
        private fun feedTitle(): UiText? =
            uiState.value.detail
                ?.displayTitle
                ?.let(UiText::Raw)

        /** Always-consent persists `playback.stream_on_metered = ALLOW` (08's metered dialog). */
        private suspend fun allowMeteredStreaming() {
            when (val result = settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ALLOW)) {
                is Outcome.Success -> {
                    Unit
                }

                is Outcome.Failure -> {
                    error("playback.stream_on_metered write failed: ${result.error}")
                }
            }
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
