// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.ChapterRepository
import ch.lkmc.neutrodyne.core.domain.EpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ShowNotesImages
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.ui.DownloadRequestHandler
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.PlaybackActions
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The episode detail screen's state (08 Episode detail): [loaded] separates the skeleton from a
 * settled emission; [gone] (a `null` episode after the first emission) renders "no longer
 * available". `combine` only fires once every input has emitted, so a `null` [notes] already
 * means "no notes" and gets the empty text. [imageMode] folds `feeds.show_notes_images` and the
 * metered state into the renderer's three modes.
 */
@Immutable
public data class EpisodeUiState(
    val episode: EpisodeDetail? = null,
    val notes: ShowNotes? = null,
    val loaded: Boolean = false,
    val offline: Boolean = false,
    val imageMode: ShowNotesImageMode = ShowNotesImageMode.TAP_TO_LOAD,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
    val isNowPlaying: Boolean = false,
    val isPlaying: Boolean = false,
    val chapters: List<EpisodeChapter> = emptyList(),
    val currentChapter: CurrentChapter? = null,
    val downloadLive: RowLive? = null,
) {
    public val gone: Boolean
        get() = loaded && episode == null
}

/**
 * The `EpisodeKey` ViewModel (08 Episode detail): the episode and its show notes come from
 * `EpisodeRepository`; played/favourite writes are repository calls. Play/queue actions go to
 * `PlaybackController` through [PlaybackActions] (M4/W10); download actions use the shared request handler.
 */
public class EpisodeViewModel
    @AssistedInject
    constructor(
        @Assisted private val episodeId: Long,
        private val episodes: EpisodeRepository,
        private val chapters: ChapterRepository,
        private val podcasts: PodcastRepository,
        private val settings: SettingsRepository,
        private val playbackController: PlaybackController,
        playbackStates: PlaybackStateSource,
        platformInfo: PlatformInfo,
        network: NetworkMonitor,
        downloadController: DownloadController,
        liveStateSource: EpisodeLiveStateSource,
    ) : ViewModel() {
        private val visibleDownloadIds = MutableStateFlow<Set<Long>>(emptySet())
        private val userMessages = UserMessages()
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

        private val failedLinks = mutableMapOf<Long, String>()

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

        /** The Enter password dialog's target podcast, set by an AuthRequired snackbar action. */
        private val credentialsPrompt = MutableStateFlow<Long?>(null)
        public val pendingCredentials: StateFlow<Long?> = credentialsPrompt.asStateFlow()

        private val screenState =
            combine(
                episodes.observeEpisode(episodeId),
                episodes.observeShowNotes(episodeId),
                network.status,
                settings.observe(FeedsSettingKeys.SHOW_NOTES_IMAGES),
                userMessages.flow,
            ) { episode, notes, net, images, messages ->
                EpisodeUiState(
                    episode = episode,
                    notes = notes,
                    loaded = true,
                    offline = !net.isConnected,
                    imageMode = imageMode(images, net.isMetered),
                    messages = messages,
                )
            }

        public val uiState: StateFlow<EpisodeUiState> =
            combine(screenState, playbackStates.nowPlaying) { state, now ->
                val current = now?.episodeId == episodeId
                state.copy(isNowPlaying = current, isPlaying = current && now?.isPlaying == true)
            }.combine(chapters.observe(episodeId)) { state, rows -> state.copy(chapters = rows) }
                .combine(playbackStates.currentChapter) { state, current ->
                    state.copy(currentChapter = current?.takeIf { it.episodeId == episodeId })
                }.combine(liveStateSource.observe(visibleDownloadIds)) { state, live ->
                    state.copy(downloadLive = live[episodeId])
                }.stateIn(viewModelScope, SHARING, EpisodeUiState())

        init {
            viewModelScope.launch { suspendRunCatching { chapters.ensureLoaded(episodeId) } }
        }

        public fun onVisible() {
            visibleDownloadIds.value = setOf(episodeId)
        }

        /** Release the window even while stateIn retains its upstream during the sharing grace. */
        public fun onHidden() {
            visibleDownloadIds.value = emptySet()
        }

        public fun onTimestamp(positionMs: Long) {
            playback.seekOrPlay(episodeId, positionMs, uiState.value.episode?.durationMs)
        }

        /** Repository-owned actions; the route owns navigation, URLs, share and clipboard. */
        public fun onAction(action: EpisodeAction) {
            when (action) {
                is EpisodeAction.SetPlayed -> {
                    write { episodes.setPlayed(listOf(action.episodeId), action.played) }
                }

                // The detail screen starts the single episode; the shared toggle
                // pauses/resumes it when it is already the current item.
                is EpisodeAction.PlayToggle -> {
                    playback.playToggle(action.episodeId, null) {
                        playbackController.playEpisode(action.episodeId)
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

        /**
         * An `AuthRequired` snackbar's Enter action (08): the episode's podcast owns the
         * credentials, so the prompt resolves it before the dialog opens.
         */
        public fun onEnterPassword(episodeId: Long) {
            viewModelScope.launch {
                val podcastId =
                    suspendRunCatching { episodes.observeEpisode(episodeId).first()?.podcastId }
                        .getOrNull() ?: return@launch
                credentialsPrompt.value = podcastId
            }
        }

        /** The Enter password dialog's commit; failures surface as a snackbar (03 Basic auth). */
        public fun onCredentials(credentials: BasicCredentials) {
            val podcastId = credentialsPrompt.value ?: return
            credentialsPrompt.value = null
            viewModelScope.launch {
                val outcome = suspendRunCatching { podcasts.setCredentials(podcastId, credentials) }.getOrNull()
                if (outcome == null || outcome is Outcome.Failure) {
                    reportWriteFailed(IllegalStateException("setCredentials($podcastId) failed"))
                }
            }
        }

        public fun onCredentialsDismissed() {
            credentialsPrompt.value = null
        }

        /** The refused YouTube start's watch URL, when the episode still has one (08). */
        public suspend fun youtubeWatchUrl(episodeId: Long): String? =
            suspendRunCatching { episodes.observeEpisode(episodeId).first()?.externalMediaId }
                .getOrNull()
                ?.let(YouTubeLinks::watch)

        /** The screen acks a shown snackbar so its [UserMessage] leaves the state. */
        public fun onMessageShown(id: Long) {
            failedLinks.remove(id)
            playback.onMessageShown(id)
            userMessages.shown(id)
        }

        public fun onLinkOpenFailed(url: String) {
            val message =
                userMessages.post(
                    UiText.Res(Res.string.no_handler),
                    action = UiText.Res(Res.string.action_copy_link),
                )
            failedLinks[message.id] = url
        }

        /** Only the failed link attached to this message may be copied by the route. */
        public fun onMessageAction(id: Long): String? = failedLinks[id]

        public fun setFavorite(favorite: Boolean) {
            write { episodes.setFavorite(episodeId, favorite) }
        }

        public fun setPlayed(played: Boolean) {
            write { episodes.setPlayed(listOf(episodeId), played) }
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

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        public fun interface Factory : ManualViewModelAssistedFactory {
            public fun create(episodeId: Long): EpisodeViewModel
        }

        private companion object {
            const val TAG = "EpisodeViewModel"
            val SHARING = SharingStarted.WhileSubscribed(5_000)
        }
    }

/** `feeds.show_notes_images` + metered → the renderer's three modes (03 Images and links, PO-21). */
private fun imageMode(
    setting: ShowNotesImages,
    metered: Boolean,
): ShowNotesImageMode =
    when (setting) {
        ShowNotesImages.ALWAYS -> {
            ShowNotesImageMode.SHOWN
        }

        ShowNotesImages.TAP_TO_LOAD -> {
            ShowNotesImageMode.TAP_TO_LOAD
        }

        ShowNotesImages.WIFI_ONLY -> {
            if (metered) ShowNotesImageMode.BLOCKED else ShowNotesImageMode.SHOWN
        }
    }
