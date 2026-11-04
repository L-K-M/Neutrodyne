// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.EpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.ui.PlaybackActions
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.radio.RadioEpisode
import ch.lkmc.neutrodyne.core.ui.radio.RadioPlaylist
import ch.lkmc.neutrodyne.core.ui.radio.RadioPodcast
import ch.lkmc.neutrodyne.core.ui.radio.RadioState
import ch.lkmc.neutrodyne.core.ui.radio.radioSelectionAfterRemoval
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name_empty
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name_long
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name_taken
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Radio selection is distinct from a loaded player: browsing paused must never start audio. */
@ViewModelKey(RadioViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class RadioViewModel(
    private val groups: GroupRepository,
    private val feeds: FeedRepository,
    private val podcasts: PodcastRepository,
    private val episodes: EpisodeRepository,
    liveSource: EpisodeLiveStateSource,
    private val controller: PlaybackController,
    private val playbackStates: PlaybackStateSource,
    private val settings: SettingsRepository,
    platform: PlatformInfo,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RadioState())
    public val state = mutableState.asStateFlow()
    private val visible = MutableStateFlow<Set<Long>>(emptySet())
    private var knownGroups = emptyList<Group>()
    private var live = emptyMap<Long, RowLive>()
    private var initialized = false
    private var rejectedUuid: String? = null
    private var initialSelectionPending = true
    private var undo: Removal? = null
    private var writing = false
    private var deferredRows: Rows? = null
    private var rowsRevision = 0
    private val seeks = mutableMapOf<Long, Long>()
    private val messages = UserMessages()
    private val credentials = MutableStateFlow<Long?>(null)
    public val pendingCredentials = credentials.asStateFlow()
    private val copyLinks = mutableMapOf<Long, String>()
    public val userMessages = messages.flow
    public val playback =
        PlaybackActions(
            viewModelScope,
            controller,
            playbackStates,
            messages,
            platform.kind,
            allowMeteredStreaming = {
                if (settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ALLOW) is Outcome.Failure) {
                    throw IllegalStateException("Could not save the metered-streaming policy")
                }
            },
            onFailure = { writeFailed() },
        )

    public fun pagingFor(id: Long?): kotlinx.coroutines.flow.Flow<PagingData<EpisodeRow>> =
        if (id == null) {
            flowOf(PagingData.empty())
        } else {
            feeds.pagedFeed(FeedSource.Group(id), FILTERS, FeedOrder.NEWEST_FIRST)
        }

    init {
        viewModelScope.launch {
            combine(
                groups.observeGroups(),
                settings.observe(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE),
            ) { all, encoded ->
                all to (FeedSourceRef.decode(encoded) as? FeedSourceRef.Group)?.uuid
            }.collect { (all, uuid) ->
                knownGroups = all
                mutableState.value =
                    state.value.copy(playlists = all.map { RadioPlaylist(it.id, it.name, it.memberCount) })
                val requested = uuid.takeUnless { it == rejectedUuid }
                val id =
                    all.find { it.uuid == requested }?.id
                        ?: state.value.playlistId?.takeIf { id -> all.any { it.id == id } }
                        ?: all.firstOrNull()?.id
                if (uuid != null && all.none { it.uuid == uuid } && rejectedUuid != uuid) {
                    // Reject before the fallback write: Undo must not revive a deleted selection
                    // while that write is pending or if it fails.
                    rejectedUuid = uuid
                    persistSelection(all.find { it.id == id })
                }
                if (id !=
                    state.value.playlistId
                ) {
                    switchTo(id, if (initialized) PlaylistChange.SWITCH else PlaylistChange.INITIAL)
                }
                initialized = true
            }
        }
        viewModelScope.launch {
            podcasts.observeLibraryTiles(null).collect { tiles ->
                mutableState.value =
                    state.value.copy(podcasts = tiles.map { RadioPodcast(it.podcastId, it.displayTitle) })
            }
        }
        viewModelScope.launch {
            val window =
                combine(visible, state.map { it.selectedId }.distinctUntilChanged()) { ids, selected ->
                    if (ids.isEmpty()) ids else ids + listOfNotNull(selected)
                }.distinctUntilChanged()
            liveSource.observe(window).collect { rows ->
                live = rows
                val current = state.value
                if (rows.containsKey(current.selectedId) &&
                    playbackStates.nowPlaying.value?.episodeId != current.selectedId
                ) {
                    mutableState.value = current.copy(elapsedMs = position(current.selectedId))
                }
            }
        }
        viewModelScope.launch {
            playbackStates.nowPlaying.collect { now ->
                val current = state.value
                if (now == null) {
                    mutableState.value = current.copy(playing = false)
                } else if (now.episodeId == current.selectedId) {
                    mutableState.value =
                        current.copy(
                            playing = now.isPlaying,
                            nowPlayingEpisode =
                                RadioEpisode(
                                    now.episodeId,
                                    now.title,
                                    now.podcastTitle,
                                    now.position.durationMs,
                                ),
                            elapsedMs = now.position.positionMs,
                            hasPlayed =
                                current.hasPlayed || now.isPlaying,
                        )
                } else if (now.isPlaying && now.context?.type == ContextType.GROUP &&
                    now.context?.id == current.playlistId
                ) {
                    mutableState.value =
                        current.copy(
                            selectedId = now.episodeId,
                            nowPlayingEpisode =
                                RadioEpisode(
                                    now.episodeId,
                                    now.title,
                                    now.podcastTitle,
                                    now.position.durationMs,
                                ),
                            elapsedMs = now.position.positionMs,
                            playing = true,
                            hasPlayed = true,
                        )
                }
            }
        }
        viewModelScope.launch {
            playbackStates.positionTicks.collect { tick ->
                if (tick.episodeId == state.value.selectedId && tick.episodeId !in seeks) {
                    mutableState.value = state.value.copy(elapsedMs = tick.positionMs)
                }
            }
        }
    }

    public fun onRows(
        playlistId: Long,
        rows: List<RadioEpisode>,
        complete: Boolean = true,
    ) {
        if (playlistId != state.value.playlistId) return
        if (writing) {
            deferredRows = Rows(playlistId, rows, complete)
            return
        }
        val current = state.value
        val engine = playbackStates.nowPlaying.value
        val active =
            engine?.takeIf {
                it.isPlaying && it.context?.type == ContextType.GROUP &&
                    it.context?.id == playlistId
            }
        val selected =
            when {
                active != null -> {
                    active.episodeId
                }

                initialSelectionPending -> {
                    rows.firstOrNull()?.id
                }

                else -> {
                    current.selectedId?.takeIf { id ->
                        rows.any { it.id == id } || (engine?.episodeId == id && current.nowPlayingEpisode?.id == id) ||
                            (!complete && current.selected?.id == id)
                    }
                }
            }
        if (rows.isNotEmpty()) initialSelectionPending = false
        mutableState.value =
            current.copy(
                episodes = rows,
                complete = complete,
                episodeCount = if (complete) rows.size else current.episodeCount,
                selectedId = selected,
                nowPlayingEpisode =
                    active?.let { RadioEpisode(it.episodeId, it.title, it.podcastTitle, it.position.durationMs) }
                        ?: rows.find { it.id == selected } ?: current.selected,
                elapsedMs =
                    if (selected ==
                        current.selectedId
                    ) {
                        current.elapsedMs
                    } else {
                        active?.position?.positionMs ?: position(selected)
                    },
                playing = active?.isPlaying ?: (current.playing && selected != null),
            )
        val revision = ++rowsRevision
        if (!complete) {
            viewModelScope.launch {
                suspendRunCatching { feeds.countUnplayed(FeedSource.Group(playlistId), null) }
                    .onSuccess { count ->
                        if (state.value.playlistId == playlistId && rowsRevision == revision) {
                            mutableState.value = state.value.copy(episodeCount = count)
                        }
                    }.onFailure { writeFailed() }
            }
        }
    }

    public fun onVisibleIds(ids: Set<Long>) {
        visible.value = ids
    }

    public fun selectEpisode(id: Long) {
        if (id == state.value.selectedId || state.value.episodes.none { it.id == id }) return
        val playing = state.value.playing
        mutableState.value =
            state.value.copy(
                selectedId = id,
                nowPlayingEpisode = state.value.episodes.find { it.id == id },
                elapsedMs = position(id),
                hasPlayed = position(id) > 0,
            )
        if (playing) start(id)
    }

    public fun togglePlay() {
        val current = state.value
        val id = current.selectedId ?: return
        if (current.playing) {
            controller.pause()
            return
        }
        val now = playbackStates.nowPlaying.value
        if (now?.episodeId == id && now.context?.type == ContextType.GROUP && now.context?.id == current.playlistId &&
            id !in seeks
        ) {
            playback.play(current.playlist?.let { UiText.Raw(it.name) }) { controller.play() }
        } else {
            start(id)
        }
    }

    private fun start(id: Long) {
        val group = knownGroups.find { it.id == state.value.playlistId } ?: return
        playback.play(UiText.Raw(group.name)) {
            val result = controller.playFeed(FeedSource.Group(group.id), FILTERS, group.playOrder, id)
            if (result == PlayResult.Started) {
                seeks.remove(id)?.let(controller::seekTo)
                if (state.value.selectedId == id) mutableState.value = state.value.copy(hasPlayed = true)
            }
            result
        }
    }

    public fun seek(positionMs: Long) {
        val current = state.value
        val selected = current.selected ?: return
        val position = positionMs.coerceIn(0L, selected.durationMs ?: Long.MAX_VALUE)
        if (playbackStates.nowPlaying.value?.episodeId == selected.id) {
            controller.seekTo(position)
        } else {
            seeks[selected.id] = position
        }
        mutableState.value = current.copy(elapsedMs = position)
    }

    public fun selectPlaylist(id: Long) {
        val group = knownGroups.find { it.id == id } ?: return
        if (id == state.value.playlistId) return
        rejectedUuid = null
        switchTo(id)
        persistSelection(group)
    }

    private fun persistSelection(group: Group?) {
        viewModelScope.launch {
            val ref = group?.let { FeedSourceRef.Group(it.uuid) } ?: FeedSourceRef.All
            if (settings.set(
                    AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    ref.encode(),
                ) is Outcome.Failure
            ) {
                writeFailed()
            }
        }
    }

    private fun switchTo(
        id: Long?,
        change: PlaylistChange = PlaylistChange.SWITCH,
    ) {
        if (change == PlaylistChange.SWITCH) controller.pause()
        initialSelectionPending = true
        mutableState.value =
            state.value.copy(
                playlistId = id,
                episodes = emptyList(),
                complete = false,
                episodeCount = null,
                selectedId = null,
                nowPlayingEpisode = null,
                elapsedMs = 0,
                playing = false,
                hasPlayed = false,
                removed =
                    undo
                        ?.takeIf {
                            it.playlistId ==
                                id
                        }?.episode,
            )
    }

    public fun discard(id: Long) {
        if (writing) return
        val before = state.value
        val row = before.episodes.find { it.id == id } ?: return
        val playlistId = before.playlistId ?: return
        writing = true
        viewModelScope.launch {
            suspendRunCatching { episodes.setPlayed(listOf(id), true) }
                .onSuccess {
                    undo = Removal(playlistId, row, before.episodes.indexOf(row))
                    if (state.value.playlistId == playlistId) {
                        val selected =
                            if (before.episodes.size ==
                                1
                            ) {
                                null
                            } else {
                                radioSelectionAfterRemoval(before.episodes.map { it.id }, before.selectedId, id)
                            }
                        mutableState.value =
                            state.value.copy(
                                episodes = before.episodes.filterNot { it.id == id },
                                episodeCount = before.episodeCount?.let { (it - 1).coerceAtLeast(0) },
                                selectedId = selected,
                                removed = row,
                                elapsedMs =
                                    if (selected ==
                                        before.selectedId
                                    ) {
                                        before.elapsedMs
                                    } else {
                                        position(selected)
                                    },
                                playing =
                                    before.playing && selected != null,
                            )
                        if (selected == null) {
                            controller.dismiss()
                        } else if (before.selectedId == id) {
                            if (before.playing) start(selected) else controller.pause()
                        }
                    }
                }.onFailure { writeFailed() }
            writing = false
            deferredRows?.let { rows ->
                onRows(rows.playlistId, rows.items.filterNot { it.id == id && undo?.episode?.id == id }, rows.complete)
            }
            deferredRows = null
        }
    }

    public fun undoDiscard() {
        val removal = undo ?: return
        if (writing) return
        writing = true
        viewModelScope.launch {
            suspendRunCatching { episodes.setPlayed(listOf(removal.episode.id), false) }
                .onSuccess {
                    undo = null
                    if (state.value.playlistId == removal.playlistId) {
                        val rows = state.value.episodes.toMutableList()
                        if (rows.none { it.id == removal.episode.id }) {
                            rows.add(
                                removal.index.coerceAtMost(rows.size),
                                removal.episode,
                            )
                        }
                        mutableState.value =
                            state.value.copy(
                                episodes =
                                    if (rows.any {
                                            it.sortDate != 0L
                                        }
                                    ) {
                                        rows.sortedWith(
                                            compareByDescending<RadioEpisode> {
                                                it.sortDate
                                            }.thenByDescending { it.id },
                                        )
                                    } else {
                                        rows
                                    },
                                episodeCount = state.value.episodeCount?.plus(1),
                                removed = null,
                            )
                    }
                }.onFailure { writeFailed() }
            writing = false
            deferredRows = null
        }
    }

    public fun createPlaylist(
        name: String,
        members: Set<Long>,
    ) {
        if (state.value.creating || members.isEmpty() ||
            !state.value.podcasts
                .map { it.id }
                .containsAll(members)
        ) {
            return
        }
        mutableState.value = state.value.copy(creating = true, createError = null)
        viewModelScope.launch {
            val result =
                suspendRunCatching { groups.create(GroupDraft(name.trim()), members) }.getOrElse {
                    mutableState.value =
                        state.value.copy(creating = false, createError = UiText.Res(Res.string.write_failed))
                    return@launch
                }
            when (result) {
                is Outcome.Success -> {
                    undo = null
                    knownGroups = groups.observeGroups().first()
                    selectPlaylist(result.value)
                    mutableState.value = state.value.copy(createdPlaylistId = result.value)
                }

                is Outcome.Failure -> {
                    val message =
                        when (val error = result.error) {
                            GroupError.NameEmpty -> UiText.Res(Res.string.group_edit_name_empty)
                            is GroupError.NameTooLong -> UiText.Res(Res.string.group_edit_name_long, listOf(error.max))
                            is GroupError.NameTaken -> UiText.Res(Res.string.group_edit_name_taken, listOf(name.trim()))
                            else -> UiText.Res(Res.string.write_failed)
                        }
                    mutableState.value = state.value.copy(createError = message)
                }
            }
            mutableState.value = state.value.copy(creating = false)
        }
    }

    public fun acknowledgeMessage(id: Long) {
        playback.onMessageShown(id)
        copyLinks.remove(id)
        messages.shown(id)
    }

    public fun onEnterPassword(episodeId: Long) {
        viewModelScope.launch {
            suspendRunCatching { episodes.observeEpisode(episodeId).first()?.podcastId }
                .onSuccess { credentials.value = it }
                .onFailure { writeFailed() }
        }
    }

    public fun onCredentials(value: BasicCredentials) {
        val id = credentials.value ?: return
        credentials.value = null
        viewModelScope.launch {
            val result = suspendRunCatching { podcasts.setCredentials(id, value) }.getOrNull()
            if (result == null || result is Outcome.Failure) writeFailed()
        }
    }

    public fun onCredentialsDismissed() {
        credentials.value = null
    }

    public suspend fun youtubeWatchUrl(id: Long): String? =
        suspendRunCatching {
            episodes
                .observeEpisode(
                    id,
                ).first()
                ?.externalMediaId
                ?.let(YouTubeLinks::watch)
        }.getOrNull()

    public fun onNoHandler(url: String) {
        val message = messages.post(UiText.Res(Res.string.no_handler), UiText.Res(Res.string.action_copy_link))
        copyLinks[message.id] = url
    }

    public fun consumeCopyLink(id: Long): String? = copyLinks.remove(id)

    private fun position(id: Long?): Long = seeks[id] ?: live[id]?.positionMs ?: 0L

    private fun writeFailed() {
        messages.post(UiText.Res(Res.string.write_failed))
    }

    private data class Rows(
        val playlistId: Long,
        val items: List<RadioEpisode>,
        val complete: Boolean,
    )

    private enum class PlaylistChange { INITIAL, SWITCH }

    private data class Removal(
        val playlistId: Long,
        val episode: RadioEpisode,
        val index: Int,
    )

    private companion object {
        val FILTERS = FeedFilters(unplayedOnly = true)
    }
}
