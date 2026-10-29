// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlayerTimeDisplay
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.ui.AvailabilityText
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
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
) : ViewModel() {
    private val messages = UserMessages()
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
                queue.observeUpNext().catch {
                    messages.post(UiText.Res(Res.string.player_read_failed))
                    emit(emptyList())
                },
            ) { state, next -> state.copy(upNextCount = next.count { it.row.id != state.now?.episodeId }) }
            .combine(messages.flow) { state, pending -> state.copy(messages = pending) }
            .stateIn(viewModelScope, SHARING, PlayerUiState(now = source.nowPlaying.value))

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

                    is PlaybackEvent.MarkedPlayed -> {
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

    fun skipBack() = controller.skipBack()

    fun skipForward() = controller.skipForward()

    fun previous() = controller.skipToPrevious()

    fun next() {
        if (source.nowPlaying.value?.hasNext == true) controller.skipToNext()
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
                    ch.lkmc.neutrodyne.core.model.Availability.AVAILABLE
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
