// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.playback_cache_clear_failed
import ch.lkmc.neutrodyne.feature.settings.resources.playback_cache_cleared
import ch.lkmc.neutrodyne.feature.settings.resources.playback_cache_read_failed
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * The values Settings › Playback renders (06 Settings; 08 Settings screen structure): the
 * playback defaults plus the streaming cache's usage and busy state. [cacheBytes] is null while
 * the first [PlaybackMaintenance.streamingCacheBytes] read is in flight or failed.
 */
public data class PlaybackSettingsUiState(
    val speed: Float = PlaybackSettingKeys.SPEED.default,
    val skipSilence: Boolean = PlaybackSettingKeys.SKIP_SILENCE.default,
    val speedPresets: String = PlaybackSettingKeys.SPEED_PRESETS.default,
    val skipBackMs: Long = PlaybackSettingKeys.SKIP_BACK_MS.default,
    val skipForwardMs: Long = PlaybackSettingKeys.SKIP_FORWARD_MS.default,
    val pauseForNavigation: Boolean = PlaybackSettingKeys.PAUSE_FOR_NAVIGATION.default,
    val streamOnMetered: StreamOnMetered = PlaybackSettingKeys.STREAM_ON_METERED.default,
    val streamCacheMb: Int = PlaybackSettingKeys.STREAM_CACHE_MB.default,
    val hardwareButtons: HardwareButtons = PlaybackSettingKeys.HARDWARE_BUTTONS.default,
    val smartResume: Boolean = PlaybackSettingKeys.SMART_RESUME.default,
    val cacheBytes: Long? = null,
    val cacheBusy: Boolean = false,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
)

/**
 * Settings › Playback's writes (06 Settings). Every row is a plain `settings.set`; the storage
 * section talks to [PlaybackMaintenance] — `streamingCacheBytes` for the usage line and
 * `clearStreamingCache` for the clear row, each under [cacheBusy] so taps can't pile up. A failed
 * write or maintenance call is logged and surfaced as a snackbar, never silently dropped.
 */
@ViewModelKey(PlaybackSettingsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class PlaybackSettingsViewModel(
    private val settings: SettingsRepository,
    private val maintenance: PlaybackMaintenance,
) : ViewModel() {
    private val userMessages = UserMessages()
    private val cacheBytes = MutableStateFlow<Long?>(null)
    private val cacheBusy = MutableStateFlow(true)

    init {
        viewModelScope.launch { refreshCacheUsage() }
    }

    public val uiState: StateFlow<PlaybackSettingsUiState> =
        combine(
            settings.observe(PlaybackSettingKeys.SPEED),
            settings.observe(PlaybackSettingKeys.SKIP_SILENCE),
            settings.observe(PlaybackSettingKeys.SPEED_PRESETS),
            settings.observe(PlaybackSettingKeys.SKIP_BACK_MS),
            settings.observe(PlaybackSettingKeys.SKIP_FORWARD_MS),
            ::PlaybackSettingsUiState,
        ).combine(settings.observe(PlaybackSettingKeys.PAUSE_FOR_NAVIGATION)) { state, pause ->
            state.copy(pauseForNavigation = pause)
        }.combine(settings.observe(PlaybackSettingKeys.STREAM_ON_METERED)) { state, policy ->
            state.copy(streamOnMetered = policy)
        }.combine(settings.observe(PlaybackSettingKeys.STREAM_CACHE_MB)) { state, mb ->
            state.copy(streamCacheMb = mb)
        }.combine(settings.observe(PlaybackSettingKeys.HARDWARE_BUTTONS)) { state, hardware ->
            state.copy(hardwareButtons = hardware)
        }.combine(settings.observe(PlaybackSettingKeys.SMART_RESUME)) { state, smart ->
            state.copy(smartResume = smart)
        }.combine(cacheBytes) { state, bytes ->
            state.copy(cacheBytes = bytes)
        }.combine(cacheBusy) { state, busy ->
            state.copy(cacheBusy = busy)
        }.combine(userMessages.flow) { state, messages ->
            state.copy(messages = messages)
        }.stateIn(viewModelScope, SHARING, PlaybackSettingsUiState())

    /** The default-speed chooser's write; the sheet clamps to the same 0.05-step range. */
    public fun setSpeed(speed: Float) {
        if (!speed.isFinite()) return
        set(PlaybackSettingKeys.SPEED, PlaybackSettingKeys.normalizeSpeed(speed))
    }

    public fun setSkipSilence(enabled: Boolean) {
        set(PlaybackSettingKeys.SKIP_SILENCE, enabled)
    }

    /**
     * The presets dialog's write. [text] must satisfy [PlaybackSettingKeys.parseSpeedPresets] —
     * the dialog keeps the user on the field until it does, so a null parse here is a no-op.
     */
    public fun setSpeedPresets(text: String) {
        val speeds = PlaybackSettingKeys.parseSpeedPresets(text) ?: return
        set(PlaybackSettingKeys.SPEED_PRESETS, speeds.joinToString(","))
    }

    public fun setSkipBackMs(ms: Long) {
        set(PlaybackSettingKeys.SKIP_BACK_MS, ms)
    }

    public fun setSkipForwardMs(ms: Long) {
        set(PlaybackSettingKeys.SKIP_FORWARD_MS, ms)
    }

    public fun setHardwareButtons(mode: HardwareButtons) {
        set(PlaybackSettingKeys.HARDWARE_BUTTONS, mode)
    }

    public fun setSmartResume(enabled: Boolean) {
        set(PlaybackSettingKeys.SMART_RESUME, enabled)
    }

    public fun setPauseForNavigation(enabled: Boolean) {
        set(PlaybackSettingKeys.PAUSE_FOR_NAVIGATION, enabled)
    }

    public fun setStreamOnMetered(policy: StreamOnMetered) {
        set(PlaybackSettingKeys.STREAM_ON_METERED, policy)
    }

    public fun setStreamCacheMb(mb: Int) {
        set(PlaybackSettingKeys.STREAM_CACHE_MB, mb)
    }

    /** The clear row's action: clear, then re-read usage so the summary reflects the result. */
    public fun clearStreamingCache() {
        if (cacheBusy.value) return
        cacheBusy.value = true
        viewModelScope.launch {
            try {
                suspendRunCatching {
                    maintenance.clearStreamingCache()
                    maintenance.streamingCacheBytes()
                }.onSuccess { remaining ->
                    cacheBytes.value = remaining
                    userMessages.post(UiText.Res(Res.string.playback_cache_cleared))
                }.onFailure { failure ->
                    reportCacheFailed(Res.string.playback_cache_clear_failed, failure)
                }
            } finally {
                cacheBusy.value = false
            }
        }
    }

    /** The screen acks a shown snackbar so its [UserMessage] leaves the state. */
    public fun onMessageShown(id: Long) {
        userMessages.shown(id)
    }

    private suspend fun refreshCacheUsage() {
        cacheBusy.value = true
        suspendRunCatching { maintenance.streamingCacheBytes() }
            .onSuccess { cacheBytes.value = it }
            .onFailure { failure -> reportCacheFailed(Res.string.playback_cache_read_failed, failure) }
        cacheBusy.value = false
    }

    private fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ) {
        viewModelScope.launch {
            val outcome = suspendRunCatching { settings.set(key, value) }.getOrNull()
            if (outcome !is Outcome.Success) reportWriteFailed(null)
        }
    }

    /** A failed write is logged and surfaces as a snackbar — it never escapes the scope. */
    private fun reportWriteFailed(t: Throwable?) {
        Log.w(TAG, t) { "settings write failed" }
        userMessages.post(UiText.Res(Res.string.settings_save_failed))
    }

    private fun reportCacheFailed(
        text: StringResource,
        t: Throwable,
    ) {
        Log.w(TAG, t) { "streaming cache maintenance failed" }
        userMessages.post(UiText.Res(text))
    }

    private companion object {
        const val TAG = "PlaybackSettingsViewModel"
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
