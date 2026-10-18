// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.ui.AvailabilityText
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
import ch.lkmc.neutrodyne.feature.player.resources.player_marked_played
import ch.lkmc.neutrodyne.feature.player.resources.player_metered_blocked
import ch.lkmc.neutrodyne.feature.player.resources.player_no_group
import ch.lkmc.neutrodyne.feature.player.resources.player_no_media
import ch.lkmc.neutrodyne.feature.player.resources.player_not_playable
import ch.lkmc.neutrodyne.feature.player.resources.player_nothing_to_play
import ch.lkmc.neutrodyne.feature.player.resources.player_offline
import ch.lkmc.neutrodyne.feature.player.resources.player_retry
import ch.lkmc.neutrodyne.feature.player.resources.player_settings
import ch.lkmc.neutrodyne.feature.player.resources.player_skipped
import ch.lkmc.neutrodyne.feature.player.resources.player_unavailable
import ch.lkmc.neutrodyne.feature.player.resources.player_write_failed
import ch.lkmc.neutrodyne.feature.player.resources.player_youtube_unavailable
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayResult
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

public enum class PlayerMessageAction { RETRY, PLAYBACK_SETTINGS }

public data class PlayerUiState(
    val now: NowPlaying? = null,
    val effective: EffectivePlayback? = null,
    val skipBackMs: Long = PlaybackSettingKeys.SKIP_BACK_MS.default,
    val skipForwardMs: Long = PlaybackSettingKeys.SKIP_FORWARD_MS.default,
    val presets: String = PlaybackSettingKeys.DEFAULT_SPEED_PRESETS,
    val consent: PlayResult.NeedsMeteredConsent? = null,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
    val miniSkip: Boolean = true,
)

/** Window-scoped UI policy. Audio, scope resolution and storage stay behind their ports. */
@ViewModelKey(PlayerViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class PlayerViewModel(
    private val controller: PlaybackController,
    private val source: PlaybackStateSource,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val messages = UserMessages()
    private val messageActions = mutableMapOf<Long, PlayerMessageAction>()
    private val locallyPendingConsent = MutableStateFlow<PlayResult.NeedsMeteredConsent?>(null)
    private val ignoredConsent = MutableStateFlow<PlayResult.NeedsMeteredConsent?>(null)
    private val scopeWrite = MutableStateFlow<ScopeWriteResult?>(null)
    public val scopeWriteResult: StateFlow<ScopeWriteResult?> = scopeWrite.asStateFlow()

    public val uiState: StateFlow<PlayerUiState> =
        combine(
            source.nowPlaying,
            source.effectivePlayback,
            settings.observe(PlaybackSettingKeys.SKIP_BACK_MS),
            settings.observe(PlaybackSettingKeys.SKIP_FORWARD_MS),
            settings.observe(PlaybackSettingKeys.SPEED_PRESETS),
            ::PlayerUiState,
        ).combine(
            combine(source.pendingMeteredConsent, locallyPendingConsent, ignoredConsent) { retained, local, ignored ->
                (retained ?: local)?.takeUnless { it == ignored }
            },
        ) { state, consent -> state.copy(consent = consent) }
            .combine(
                settings.observe(AppearanceSettingKeys.MINI_PLAYER_SKIP),
            ) { state, show -> state.copy(miniSkip = show) }
            .combine(messages.flow) { state, messages -> state.copy(messages = messages) }
            .stateIn(viewModelScope, SHARING, PlayerUiState(now = source.nowPlaying.value))

    /** Only 1 Hz samples enter a flow. Frame interpolation belongs to the visible scrubber. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val position: StateFlow<PositionSnapshot?> =
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
                        report(event.result)
                    }

                    is PlaybackEvent.Skipped -> {
                        messages.post(
                            UiText.Res(Res.string.player_skipped, listOf(event.title, unplayableText(event.reason))),
                        )
                    }

                    is PlaybackEvent.MarkedPlayed -> {
                        Unit
                    }
                }
            }
        }
        viewModelScope.launch {
            source.pendingMeteredConsent.collect { pending ->
                if (pending == null) {
                    ignoredConsent.value = null
                } else {
                    locallyPendingConsent.value = null
                }
            }
        }
    }

    public fun positionAt(snapshot: PositionSnapshot): Long = snapshot.positionAt(clock.elapsedRealtime())

    public fun togglePlayPause() {
        if (source.nowPlaying.value?.let { it.isPlaying || it.playWhenReady } == true) {
            controller.pause()
            return
        }
        viewModelScope.launch {
            ignoredConsent.value = null
            report(suspendRunCatching { controller.play() }.getOrDefault(PlayResult.ServiceUnavailable))
        }
    }

    public fun skipBack() = controller.skipBack()

    public fun skipForward() = controller.skipForward()

    public fun previous() = controller.skipToPrevious()

    public fun next() {
        if (source.nowPlaying.value?.hasNext == true) controller.skipToNext()
    }

    public fun seekTo(
        positionMs: Long,
        episodeId: Long? = source.nowPlaying.value?.episodeId,
    ) {
        val snapshot = position.value ?: return
        if (episodeId != source.nowPlaying.value?.episodeId || snapshot.episodeId != episodeId) return
        controller.seekTo(positionMs.coerceIn(0L, snapshot.durationMs?.coerceAtLeast(0L) ?: Long.MAX_VALUE))
    }

    /** A resume during a drag must not turn a paused-only gesture into Stop. */
    public fun dismissPaused() {
        val now = source.nowPlaying.value ?: return
        if (now.isPlaying || now.playWhenReady) return
        viewModelScope.launch {
            val current = source.nowPlaying.value ?: return@launch
            if (current.episodeId != now.episodeId || current.isPlaying || current.playWhenReady) return@launch
            suspendRunCatching { controller.dismiss() }.onFailure { writeFailed() }
        }
    }

    public fun stopAndClose() {
        controller.pause()
        dismiss()
    }

    private fun dismiss() {
        viewModelScope.launch {
            suspendRunCatching { controller.dismiss() }.onFailure { writeFailed() }
        }
    }

    public fun acknowledgeScopeWrite() {
        scopeWrite.value = null
    }

    public fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ) {
        if (!speed.isFinite()) return
        writeScope { controller.setSpeed(PlaybackSettingKeys.normalizeSpeed(speed), scope) }
    }

    public fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ) {
        writeScope { controller.setSkipSilence(enabled, scope) }
    }

    private fun writeScope(write: suspend () -> ScopeWriteResult) {
        scopeWrite.value = null
        viewModelScope.launch {
            val result = suspendRunCatching { write() }.getOrDefault(ScopeWriteResult.FAILED)
            scopeWrite.value = result
            when (result) {
                ScopeWriteResult.NO_CONTEXT_GROUP -> messages.post(UiText.Res(Res.string.player_no_group))
                ScopeWriteResult.FAILED -> writeFailed()
                else -> Unit
            }
        }
    }

    public fun setPresets(text: String) {
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

    public fun continueOnMobileData() {
        ignoredConsent.value = null
        locallyPendingConsent.value = null
        controller.grantMeteredStreaming()
        viewModelScope.launch {
            report(suspendRunCatching { controller.play() }.getOrDefault(PlayResult.ServiceUnavailable))
        }
    }

    public fun declineMobileData() {
        ignoredConsent.value = uiState.value.consent
        locallyPendingConsent.value = null
    }

    public fun onMessageShown(id: Long) {
        messages.shown(id)
        messageActions.remove(id)
    }

    public fun onMessageAction(id: Long): PlayerMessageAction? = messageActions[id]

    private fun report(result: PlayResult) {
        if (result is PlayResult.NeedsMeteredConsent) {
            if (source.pendingMeteredConsent.value == null) locallyPendingConsent.value = result
            return
        }
        val text =
            when (result) {
                PlayResult.Started -> {
                    locallyPendingConsent.value = null
                    return
                }

                is PlayResult.NeedsMeteredConsent -> {
                    return
                }

                is PlayResult.NothingToPlay -> {
                    UiText.Res(
                        if (result.externalOnly) Res.string.player_external else Res.string.player_nothing_to_play,
                    )
                }

                PlayResult.MeteredBlocked -> {
                    UiText.Res(Res.string.player_metered_blocked)
                }

                PlayResult.Offline -> {
                    UiText.Res(Res.string.player_offline)
                }

                is PlayResult.NotPlayable -> {
                    unplayableText(result.reason)
                }

                PlayResult.ServiceUnavailable -> {
                    UiText.Res(Res.string.player_unavailable)
                }
            }
        val action =
            when (result) {
                PlayResult.ServiceUnavailable -> PlayerMessageAction.RETRY
                PlayResult.MeteredBlocked -> PlayerMessageAction.PLAYBACK_SETTINGS
                else -> null
            }
        val label =
            when (action) {
                PlayerMessageAction.RETRY -> UiText.Res(Res.string.player_retry)
                PlayerMessageAction.PLAYBACK_SETTINGS -> UiText.Res(Res.string.player_settings)
                null -> null
            }
        val message = messages.post(text, label)
        if (action != null) messageActions[message.id] = action
    }

    private fun unplayableText(reason: UnplayableReason): UiText =
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
                if (reason.availability == Availability.AVAILABLE) {
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

    private fun writeFailed() {
        messages.post(UiText.Res(Res.string.player_write_failed))
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
