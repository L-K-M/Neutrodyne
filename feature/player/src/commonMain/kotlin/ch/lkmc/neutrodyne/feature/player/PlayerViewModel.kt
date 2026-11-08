// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.ArtworkRepository
import ch.lkmc.neutrodyne.core.domain.ChapterRepository
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.YouTubeExternalOpen
import ch.lkmc.neutrodyne.core.domain.YouTubeOpenContext
import ch.lkmc.neutrodyne.core.model.ArtworkColors
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlayerTimeDisplay
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.ui.AvailabilityText
import ch.lkmc.neutrodyne.core.ui.ExternalOpenHandler
import ch.lkmc.neutrodyne.core.ui.MeteredConsent
import ch.lkmc.neutrodyne.core.ui.PlaybackActions
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_audio_gone
import ch.lkmc.neutrodyne.feature.player.resources.player_auth_required
import ch.lkmc.neutrodyne.feature.player.resources.player_external
import ch.lkmc.neutrodyne.feature.player.resources.player_format_unsupported
import ch.lkmc.neutrodyne.feature.player.resources.player_http
import ch.lkmc.neutrodyne.feature.player.resources.player_invalid_presets
import ch.lkmc.neutrodyne.feature.player.resources.player_no_group
import ch.lkmc.neutrodyne.feature.player.resources.player_no_media
import ch.lkmc.neutrodyne.feature.player.resources.player_not_playable
import ch.lkmc.neutrodyne.feature.player.resources.player_read_failed
import ch.lkmc.neutrodyne.feature.player.resources.player_skipped
import ch.lkmc.neutrodyne.feature.player.resources.player_write_failed
import ch.lkmc.neutrodyne.feature.player.resources.player_youtube_unavailable
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PlayerUiState(
    val now: NowPlaying? = null,
    val effective: EffectivePlayback? = null,
    val skipBackMs: Long = PlaybackSettingKeys.SKIP_BACK_MS.default,
    val skipForwardMs: Long = PlaybackSettingKeys.SKIP_FORWARD_MS.default,
    val presets: String = PlaybackSettingKeys.DEFAULT_SPEED_PRESETS,
    val consent: MeteredConsent? = null,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
    val miniSkip: Boolean = true,
    val timeDisplay: PlayerTimeDisplay = PlayerTimeDisplay.REMAINING,
    val upNextCount: Int = 0,
    val sleepTimer: SleepTimerState = SleepTimerState.Off,
    val lastSleepMinutes: Int = PlaybackSettingKeys.SLEEP_LAST_MINUTES.default,
    val chapters: List<EpisodeChapter> = emptyList(),
    val currentChapter: CurrentChapter? = null,
    val colors: ArtworkColors? = null,
)

/** Window-scoped presentation; starts and consent use the same policy as episode screens. */
@ViewModelKey(PlayerViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class PlayerViewModel(
    private val controller: PlaybackController,
    private val source: PlaybackStateSource,
    private val settings: SettingsRepository,
    private val clock: Clock,
    platform: PlatformInfo,
    queue: QueueRepository,
    private val chapters: ChapterRepository,
    private val artwork: Provider<ArtworkRepository>,
    externalOpenService: YouTubeExternalOpen,
    val youtubeHealth: YouTubeHealth? = null,
) : ViewModel() {
    private val messages = UserMessages()
    val externalOpen =
        ExternalOpenHandler(
            viewModelScope,
            messages,
            mark = { externalOpenService.onOpened(it, YouTubeOpenContext.EXTERNAL_OR_UNPLAYABLE) },
            undo = { externalOpenService.undo(it) },
            onFailure = { writeFailed() },
        )
    private val timeWrites = Mutex()
    private val playbackActions =
        PlaybackActions(
            viewModelScope,
            controller,
            source,
            messages,
            platform.kind,
            allowMeteredStreaming = {
                if (settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ALLOW) is Outcome.Failure) {
                    throw IllegalStateException("Could not save streaming preference")
                }
            },
            onFailure = { writeFailed() },
        )
    private val scopeWrite = MutableStateFlow<ScopeWriteResult?>(null)
    val scopeWriteResult: StateFlow<ScopeWriteResult?> = scopeWrite.asStateFlow()

    /** Follows the current episode; a chapter read failure degrades to an empty list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val chapterRows =
        source.nowPlaying
            .map { it?.episodeId }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(null to emptyList<EpisodeChapter>())
                } else {
                    chapters
                        .observe(id)
                        .map { id to it }
                        .onStart { emit(id to emptyList()) }
                        .catch { emit(id to emptyList()) }
                }
            }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val artworkColors =
        source.nowPlaying
            .map { item -> item?.let { Triple(it.episodeId, it.artwork.key, it.podcastId) } }
            .distinctUntilChanged()
            .flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(null to null)
                } else {
                    artwork()
                        .observeColors(identity.second, identity.third)
                        .map { identity to it }
                        .onStart { emit(identity to null) }
                }
            }

    val uiState: StateFlow<PlayerUiState> =
        combine(
            source.nowPlaying,
            source.effectivePlayback,
            settings.observe(PlaybackSettingKeys.SKIP_BACK_MS),
            settings.observe(PlaybackSettingKeys.SKIP_FORWARD_MS),
            settings.observe(PlaybackSettingKeys.SPEED_PRESETS),
            ::PlayerUiState,
        ).combine(playbackActions.meteredConsent) { state, consent -> state.copy(consent = consent) }
            .combine(
                settings.observe(AppearanceSettingKeys.MINI_PLAYER_SKIP),
            ) { state, show -> state.copy(miniSkip = show) }
            .combine(
                settings.observe(AppearanceSettingKeys.PLAYER_TIME),
            ) { state, time -> state.copy(timeDisplay = time) }
            .combine(
                queue.observeUpNext().retryPlayerRead(emptyList()) {
                    messages.post(UiText.Res(Res.string.player_read_failed))
                },
            ) { state, next -> state.copy(upNextCount = next.count { it.row.id != state.now?.episodeId }) }
            .combine(source.sleepTimer) { state, timer -> state.copy(sleepTimer = timer) }
            .combine(
                settings.observe(PlaybackSettingKeys.SLEEP_LAST_MINUTES),
            ) { state, minutes ->
                state.copy(
                    lastSleepMinutes =
                        minutes.takeIf(SleepTimerMode::isValidMinutes)
                            ?: PlaybackSettingKeys.SLEEP_LAST_MINUTES.default,
                )
            }.combine(chapterRows) { state, rows ->
                state.copy(chapters = rows.second.takeIf { rows.first == state.now?.episodeId }.orEmpty())
            }.combine(source.currentChapter) { state, current ->
                state.copy(currentChapter = current?.takeIf { it.episodeId == state.now?.episodeId })
            }.combine(messages.flow) { state, pending -> state.copy(messages = pending) }
            .combine(artworkColors) { state, result ->
                state.copy(
                    colors =
                        result.second.takeIf {
                            result.first ==
                                state.now?.let { Triple(it.episodeId, it.artwork.key, it.podcastId) }
                        },
                )
            }.stateIn(viewModelScope, SHARING, PlayerUiState(now = source.nowPlaying.value))

    /** The visible scrubber interpolates these 1 Hz samples, never a frame-rate flow. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val position: StateFlow<PositionSnapshot?> =
        source.nowPlaying
            .flatMapLatest { now ->
                if (now == null) {
                    flowOf(null)
                } else {
                    source.positionTicks.filter { it.episodeId == now.episodeId }.onStart { emit(now.position) }
                }
            }.stateIn(viewModelScope, SHARING, source.nowPlaying.value?.position)

    init {
        viewModelScope.launch {
            source.events.collect { event ->
                when (event) {
                    is PlaybackEvent.StartRefused -> {
                        playbackActions.onStartRefused(event.result)
                    }

                    is PlaybackEvent.Skipped -> {
                        messages.post(
                            UiText.Res(Res.string.player_skipped, listOf(event.title, skippedReason(event.reason))),
                        )
                    }

                    is PlaybackEvent.MarkedPlayed, PlaybackEvent.SleepTimerFired -> {
                        Unit
                    }
                }
            }
        }
    }

    fun positionAt(snapshot: PositionSnapshot): Long = snapshot.positionAt(clock.elapsedRealtime())

    fun togglePlayPause() {
        val now = source.nowPlaying.value
        if (now != null) {
            playbackActions.playToggle(now.episodeId, null) { controller.play() }
        } else {
            retryPlayback()
        }
    }

    fun retryPlayback() {
        playbackActions.play(null) { controller.play() }
    }

    fun setPanelHidden(hidden: Boolean) {
        viewModelScope.launch {
            if (settings.set(AppearanceSettingKeys.PLAYER_PANEL_HIDDEN, hidden) is Outcome.Failure) writeFailed()
        }
    }

    fun skipBack() = controller.skipBack()

    fun skipForward() = controller.skipForward()

    /** Chapter steps take over the transport pair while chapters exist; the episode pair lives in the overflow. */
    fun previous() {
        if (uiState.value.chapters.isNotEmpty()) controller.previousChapter() else controller.skipToPrevious()
    }

    fun next() {
        if (uiState.value.chapters.isNotEmpty()) controller.nextChapter() else nextEpisode()
    }

    fun previousEpisode() = controller.skipToPrevious()

    fun nextEpisode() {
        if (source.nowPlaying.value?.hasNext == true) controller.skipToNext()
    }

    /**
     * The sheet's timer write (06 Sleep timer): `Minutes` must pass the shared range check and is
     * the only mode that persists `playback.sleep_last_minutes`; timer state itself stays
     * service-local. A failed preference write is a snackbar, never silent.
     */
    fun setSleepTimer(mode: SleepTimerMode) {
        if (source.nowPlaying.value == null) return
        if (mode is SleepTimerMode.Minutes && !SleepTimerMode.isValidMinutes(mode.minutes)) return
        controller.setSleepTimer(mode)
        if (mode !is SleepTimerMode.Minutes) return
        viewModelScope.launch {
            if (suspendRunCatching {
                    settings.set(PlaybackSettingKeys.SLEEP_LAST_MINUTES, mode.minutes)
                }.getOrNull() !is Outcome.Success
            ) {
                writeFailed()
            }
        }
    }

    fun extendSleepTimer(minutes: Int) {
        if (SleepTimerMode.isValidMinutes(minutes)) controller.extendSleepTimer(minutes)
    }

    /** Show-notes timestamps and chapter taps seek a loaded current item; else they start at the position. */
    fun onTimestamp(positionMs: Long) {
        val now = source.nowPlaying.value ?: return
        playbackActions.seekOrPlay(now.episodeId, positionMs, now.position.durationMs)
    }

    fun seekTo(
        positionMs: Long,
        episodeId: Long? = source.nowPlaying.value?.episodeId,
    ) {
        val snapshot = position.value ?: return
        if (episodeId != source.nowPlaying.value?.episodeId || snapshot.episodeId != episodeId) return
        controller.seekTo(positionMs.coerceIn(0L, snapshot.durationMs?.coerceAtLeast(0L) ?: Long.MAX_VALUE))
    }

    fun dismissPaused() {
        val now = source.nowPlaying.value ?: return
        if (now.isPlaying || now.playWhenReady) return
        viewModelScope.launch {
            val current = source.nowPlaying.value ?: return@launch
            if (current.episodeId != now.episodeId || current.isPlaying || current.playWhenReady) return@launch
            suspendRunCatching { controller.dismiss() }.onFailure { writeFailed() }
        }
    }

    fun stopAndClose() {
        controller.pause()
        viewModelScope.launch { suspendRunCatching { controller.dismiss() }.onFailure { writeFailed() } }
    }

    fun continueOnMobileData() = playbackActions.consentOnce()

    fun alwaysOnMobileData() = playbackActions.consentAlways()

    fun declineMobileData() = playbackActions.consentDismiss()

    fun onMessageShown(id: Long) {
        externalOpen.shown(id)
        messages.shown(id)
        playbackActions.onMessageShown(id)
    }

    fun onMessageAction(id: Long): PlaybackMessageAction? = playbackActions.onMessageAction(id)

    fun acknowledgeScopeWrite() {
        scopeWrite.value = null
    }

    fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ) {
        if (speed.isFinite()) writeScope { controller.setSpeed(PlaybackSettingKeys.normalizeSpeed(speed), scope) }
    }

    fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ) {
        writeScope { controller.setSkipSilence(enabled, scope) }
    }

    private fun writeScope(write: suspend () -> ScopeWriteResult) {
        scopeWrite.value = null
        viewModelScope.launch {
            val result = suspendRunCatching(write).getOrDefault(ScopeWriteResult.FAILED)
            scopeWrite.value = result
            when (result) {
                ScopeWriteResult.NO_CONTEXT_GROUP -> messages.post(UiText.Res(Res.string.player_no_group))
                ScopeWriteResult.FAILED -> writeFailed()
                else -> Unit
            }
        }
    }

    fun setPresets(text: String) {
        if (PlaybackSettingKeys.parseSpeedPresets(text) == null) {
            messages.post(UiText.Res(Res.string.player_invalid_presets))
            return
        }
        viewModelScope.launch {
            if (suspendRunCatching {
                    settings.set(
                        PlaybackSettingKeys.SPEED_PRESETS,
                        text,
                    )
                }.getOrNull() !is Outcome.Success
            ) {
                writeFailed()
            }
        }
    }

    fun toggleTimeDisplay() {
        viewModelScope.launch {
            suspendRunCatching {
                timeWrites.withLock {
                    val next =
                        when (settings.get(AppearanceSettingKeys.PLAYER_TIME)) {
                            PlayerTimeDisplay.REMAINING -> PlayerTimeDisplay.TOTAL
                            PlayerTimeDisplay.TOTAL -> PlayerTimeDisplay.REMAINING
                        }
                    if (settings.set(AppearanceSettingKeys.PLAYER_TIME, next) is Outcome.Failure) writeFailed()
                }
            }.onFailure { writeFailed() }
        }
    }

    private fun writeFailed() {
        messages.post(UiText.Res(Res.string.player_write_failed))
    }

    private fun skippedReason(reason: UnplayableReason): UiText =
        when (reason) {
            is UnplayableReason.Http -> {
                if (reason.status == HTTP_NOT_FOUND || reason.status == HTTP_GONE) {
                    UiText.Res(Res.string.player_audio_gone)
                } else {
                    UiText.Res(Res.string.player_http, listOf(reason.status))
                }
            }

            UnplayableReason.AuthRequired -> {
                UiText.Res(Res.string.player_auth_required)
            }

            UnplayableReason.UnsupportedFormat -> {
                UiText.Res(Res.string.player_format_unsupported)
            }

            UnplayableReason.NoMedia -> {
                UiText.Res(Res.string.player_no_media)
            }

            is UnplayableReason.YouTube -> {
                if (reason.availability ==
                    Availability.AVAILABLE
                ) {
                    UiText.Res(Res.string.player_not_playable)
                } else {
                    AvailabilityText.describe(reason.availability)
                }
            }

            UnplayableReason.YouTubeExtraction -> {
                UiText.Res(Res.string.player_youtube_unavailable)
            }

            is UnplayableReason.YouTubeExternal -> {
                UiText.Res(Res.string.player_external)
            }
        }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
