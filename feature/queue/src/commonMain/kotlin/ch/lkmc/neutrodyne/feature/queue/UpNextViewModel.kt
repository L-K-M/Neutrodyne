// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.EpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.AVAILABLE_FEED_FILTER_CAPABILITIES
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.QueueRemoval
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.model.withCapabilities
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.PlaybackActions
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.RowCaps
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UpNextCurrent
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.episode_not_found
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.resources.queue_cleared
import ch.lkmc.neutrodyne.core.ui.resources.queue_removed
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class UpNextUiState(
    val current: UpNextCurrent? = null,
    val upNext: List<UpNextItem> = emptyList(),
    val context: PlayContextInfo? = null,
    val groups: List<Group> = emptyList(),
    val live: Map<Long, RowLive> = emptyMap(),
    val caps: RowCaps = RowCaps.FULL.copy(downloads = false),
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
)

/** Queue edits stay in the repository, playback starts in the controller; ticks never reload it. */
@ViewModelKey(UpNextViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class UpNextViewModel(
    private val queue: QueueRepository,
    private val controller: PlaybackController,
    private val playback: PlaybackStateSource,
    private val episodes: EpisodeRepository,
    private val feeds: FeedRepository,
    private val groups: GroupRepository,
    private val capabilities: YouTubeCapabilitiesSource,
    private val network: NetworkMonitor,
    liveSource: EpisodeLiveStateSource,
    private val settings: SettingsRepository,
    platform: PlatformInfo,
    private val podcasts: PodcastRepository,
) : ViewModel() {
    private val state = MutableStateFlow(UpNextUiState())
    val uiState = state.asStateFlow()
    private val messages = UserMessages()
    val playbackActions =
        PlaybackActions(
            viewModelScope,
            controller,
            playback,
            messages,
            platform.kind,
            allowMeteredStreaming = {
                if (settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ALLOW) is Outcome.Failure) {
                    throw IllegalStateException("Could not save streaming preference")
                }
            },
            onFailure = { messages.post(UiText.Res(Res.string.write_failed)) },
        )
    private val password = MutableStateFlow<Long?>(null)
    val passwordEpisode = password.asStateFlow()
    private val undos = mutableMapOf<Long, QueueRemoval>()
    private val copyLinks = mutableMapOf<Long, String>()
    private val visibleIds = MutableStateFlow<Set<Long>>(emptySet())
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { messages.flow.collect { list -> state.update { it.copy(messages = list) } } }
        viewModelScope.launch {
            liveSource.observe(visibleIds).collect { live -> state.update { it.copy(live = live) } }
        }
        retry()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun retry() {
        loadJob?.cancel()
        state.update { it.copy(loadFailed = false) }
        loadJob =
            viewModelScope.launch {
                suspendRunCatching {
                    combine(
                        queue.observeUpNext(),
                        queue.observeSession().flatMapLatest { session ->
                            val id = session?.currentEpisodeId
                            if (id == null) {
                                flowOf(session to null)
                            } else {
                                episodes.observeEpisode(id).map { session to it }
                            }
                        },
                        playback.nowPlaying,
                        groups.observeGroups(),
                        combine(capabilities.capabilities, network.status) { caps, net ->
                            RowCaps(caps.inAppPlayback, false, false, null, !net.isConnected)
                        },
                    ) { next, (session, detail), playing, groups, caps ->
                        val currentId = session?.currentEpisodeId ?: playing?.episodeId
                        val current =
                            if (playing != null && playing.episodeId == currentId) {
                                UpNextCurrent(
                                    playing.episodeId,
                                    playing.title,
                                    playing.podcastTitle,
                                    playing.artwork,
                                    // Buffering still toggles to Pause.
                                    playing.isPlaying || playing.playWhenReady,
                                )
                            } else {
                                detail?.let { UpNextCurrent(it.id, it.title, it.podcastTitle, it.artwork, false) }
                            }
                        UpNextUiState(
                            current = current,
                            upNext = next.filterNot { it.row.id == (current?.episodeId ?: session?.currentEpisodeId) },
                            context = session?.context,
                            groups = groups.take(GROUP_CHIP_COUNT),
                            caps = caps,
                            loaded = true,
                        )
                    }.collect { loaded ->
                        state.update { loaded.copy(messages = it.messages, live = it.live) }
                    }
                }.onFailure { state.update { it.copy(loadFailed = true) } }
            }
    }

    fun onVisibleIds(ids: Set<Long>) {
        visibleIds.value = ids
    }

    fun move(
        id: Long,
        index: Int,
    ) {
        write { queue.move(id, index) }
    }

    fun remove(id: Long) {
        write { offerUndo(queue.removeWithUndo(listOf(id)), UiText.Res(Res.string.queue_removed)) }
    }

    fun clear() {
        write { offerUndo(queue.clearWithUndo(), UiText.Res(Res.string.queue_cleared)) }
    }

    private fun offerUndo(
        removal: QueueRemoval,
        text: UiText,
    ) {
        if (removal.removedIds.isEmpty()) return
        val message = messages.post(text, UiText.Res(Res.string.action_undo))
        undos[message.id] = removal
    }

    fun undo(id: Long) {
        // Consume once; re-offer the same token if restoration fails.
        val removal = undos.remove(id) ?: return
        viewModelScope.launch {
            suspendRunCatching { queue.undoRemoval(removal) }
                .onFailure { offerUndo(removal, UiText.Res(Res.string.write_failed)) }
        }
    }

    fun stopAfter() {
        write { queue.clearContext() }
    }

    fun onAction(action: EpisodeAction) {
        when (action) {
            is EpisodeAction.PlayToggle -> {
                playbackActions.playToggle(action.episodeId, null) {
                    controller.playEpisode(action.episodeId)
                }
            }

            is EpisodeAction.PlayNext -> {
                playbackActions.playNext(listOf(action.episodeId))
            }

            is EpisodeAction.PlayLast -> {
                playbackActions.playLast(listOf(action.episodeId))
            }

            is EpisodeAction.SetPlayed -> {
                write { episodes.setPlayed(listOf(action.episodeId), action.played) }
            }

            else -> {
                Unit
            }
        }
    }

    fun playGroup(id: Long) {
        playbackActions.play(
            state.value.groups
                .find { it.id == id }
                ?.name
                ?.let { UiText.Raw(it) },
        ) {
            val source = FeedSource.Group(id)
            val prefs = feeds.observePrefs(source).first()
            controller.playFeed(
                source,
                prefs.filters.withCapabilities(AVAILABLE_FEED_FILTER_CAPABILITIES),
                prefs.playOrder,
            )
        }
    }

    fun onPlaybackMessageAction(id: Long): PlaybackMessageAction? {
        val action = playbackActions.onMessageAction(id)
        if (action is PlaybackMessageAction.EnterPassword) password.value = action.episodeId
        return action
    }

    fun dismissPassword() {
        password.value = null
    }

    fun submitPassword(credentials: BasicCredentials) {
        // Close before IO; saving must not replay or dismiss a newer dialog.
        val id = password.value ?: return
        password.value = null
        write {
            val episode = episodes.observeEpisode(id).first()
            if (episode == null) {
                messages.post(UiText.Res(Res.string.episode_not_found))
                return@write
            }
            if (podcasts.setCredentials(episode.podcastId, credentials) is Outcome.Failure) {
                messages.post(UiText.Res(Res.string.write_failed))
            }
        }
    }

    suspend fun watchLink(id: Long): String? =
        episodes
            .observeEpisode(id)
            .first()
            ?.externalMediaId
            ?.let(YouTubeLinks::watch)

    fun onNoHandler(url: String) {
        val message = messages.post(UiText.Res(Res.string.no_handler), UiText.Res(Res.string.action_copy_link))
        copyLinks[message.id] = url
    }

    fun consumeCopyLink(id: Long): String? = copyLinks.remove(id)

    fun onMessageShown(id: Long) {
        playbackActions.onMessageShown(id)
        copyLinks.remove(id)
        undos.remove(id)
        messages.shown(id)
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            suspendRunCatching { block() }.onFailure { messages.post(UiText.Res(Res.string.write_failed)) }
        }
    }

    private companion object {
        const val GROUP_CHIP_COUNT = 3
    }
}
