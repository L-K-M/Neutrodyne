// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.YouTubeAudioQuality
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceDialog
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.downloadExternalReasonText
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.settings_save_failed
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_audio_quality
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_auto_download
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_breaker_open
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_absent
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_disabled
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_enabled
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_failed
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_preparing
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_ready
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_retry
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_starting
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_stopped
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_engine_version
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_mark_played
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_quality_opus
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_quality_saver
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_quality_standard
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_rate_limited
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_suggest_rss
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_title
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_volume_levelling
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun YouTubePage(
    settings: SettingsRepository,
    capabilities: YouTubeCapabilitiesSource?,
    platform: PlatformKind,
    engine: YouTubeEngine? = null,
    health: YouTubeHealth? = null,
) {
    val caps by (capabilities?.capabilities ?: remember { flowOf(null) }).collectAsStateWithLifecycle(null)
    val suggest by remember(settings) { settings.observe(YouTubeSettingKeys.SUGGEST_RSS) }
        .collectAsStateWithLifecycle(YouTubeSettingKeys.SUGGEST_RSS.default)
    val mark by remember(settings) { settings.observe(YouTubeSettingKeys.MARK_PLAYED_ON_OPEN) }
        .collectAsStateWithLifecycle(YouTubeSettingKeys.MARK_PLAYED_ON_OPEN.default)
    val status by (engine?.status ?: remember { flowOf(null) }).collectAsStateWithLifecycle(null)
    val engineHealth by (health?.state ?: remember { flowOf(null) }).collectAsStateWithLifecycle(null)
    val enabled by remember(settings) { settings.observe(YouTubeSettingKeys.ENGINE_ENABLED) }
        .collectAsStateWithLifecycle(YouTubeSettingKeys.ENGINE_ENABLED.default)
    val levelling by remember(settings) { settings.observe(YouTubeSettingKeys.VOLUME_LEVELLING) }
        .collectAsStateWithLifecycle(YouTubeSettingKeys.VOLUME_LEVELLING.default)
    val quality by remember(settings) { settings.observe(YouTubeSettingKeys.AUDIO_QUALITY) }
        .collectAsStateWithLifecycle(YouTubeSettingKeys.AUDIO_QUALITY.default)
    var showQuality by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current
    val failed = stringResource(Res.string.settings_save_failed)

    fun <T : Any> save(
        key: SettingKey<T>,
        value: T,
    ) {
        scope.launch {
            if (suspendRunCatching {
                    settings.set(
                        key,
                        value,
                    )
                }.getOrNull() !is Outcome.Success
            ) {
                snackbar.showSnackbar(failed)
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NdTopAppBar(title = stringResource(Res.string.youtube_title), navigation = { SettingsBackButton() })
        caps?.externalReason?.let { Text(downloadExternalReasonText(it, platform).asString()) }
        if (caps == null) Text(downloadExternalReasonText(ExternalReason.NOT_YET_AVAILABLE, platform).asString())
        status?.let { state ->
            if (state.availability != EngineAvailability.NOT_YET_AVAILABLE) {
                if (state.availability != EngineAvailability.NOT_IN_THIS_APK) {
                    SettingsSwitchRow(
                        stringResource(Res.string.youtube_engine_enabled),
                        null,
                        enabled,
                        { save(YouTubeSettingKeys.ENGINE_ENABLED, it) },
                    )
                    SettingsRow(
                        NdIcons.Download,
                        stringResource(Res.string.youtube_audio_quality),
                        stringResource(qualityLabel(quality)),
                        { showQuality = true },
                    )
                    SettingsSwitchRow(
                        stringResource(Res.string.youtube_volume_levelling),
                        null,
                        levelling,
                        { save(YouTubeSettingKeys.VOLUME_LEVELLING, it) },
                    )
                }
                state.version?.let { Text(stringResource(Res.string.youtube_engine_version, it)) }
                Text(stringResource(engineStatusText(state.availability)))
                if (state.availability == EngineAvailability.FAILED) {
                    TextButton(onClick = { engine?.retryStart() }) {
                        Text(stringResource(Res.string.youtube_engine_retry))
                    }
                }
            }
        }
        if ((engineHealth?.rateLimitedUntil ?: 0) > LocalUiClock.current.now()) {
            Text(stringResource(Res.string.youtube_rate_limited))
        }
        if (engineHealth?.breaker == BreakerState.OPEN) {
            Text(stringResource(Res.string.youtube_breaker_open))
            TextButton(onClick = { health?.retryNow() }) {
                Text(stringResource(Res.string.youtube_engine_retry))
            }
        }
        SettingsSwitchRow(
            stringResource(Res.string.youtube_suggest_rss),
            null,
            suggest,
            { save(YouTubeSettingKeys.SUGGEST_RSS, it) },
        )
        SettingsSwitchRow(
            stringResource(Res.string.youtube_mark_played),
            null,
            mark,
            { save(YouTubeSettingKeys.MARK_PLAYED_ON_OPEN, it) },
        )
    }
    if (showQuality) {
        SettingsChoiceDialog(
            NdIcons.Download,
            stringResource(Res.string.youtube_audio_quality),
            YouTubeAudioQuality.entries,
            quality,
            { stringResource(qualityLabel(it)) },
            {
                showQuality = false
                save(YouTubeSettingKeys.AUDIO_QUALITY, it)
            },
            { showQuality = false },
        )
    }
}

private fun engineStatusText(status: EngineAvailability): StringResource =
    when (status) {
        EngineAvailability.PREPARING -> Res.string.youtube_engine_preparing
        EngineAvailability.STARTING -> Res.string.youtube_engine_starting
        EngineAvailability.READY -> Res.string.youtube_engine_ready
        EngineAvailability.FAILED -> Res.string.youtube_engine_failed
        EngineAvailability.DISABLED -> Res.string.youtube_engine_disabled
        EngineAvailability.NOT_IN_THIS_APK -> Res.string.youtube_engine_absent
        else -> Res.string.youtube_engine_stopped
    }

private fun qualityLabel(quality: YouTubeAudioQuality) =
    when (quality) {
        YouTubeAudioQuality.STANDARD -> Res.string.youtube_quality_standard
        YouTubeAudioQuality.DATA_SAVER -> Res.string.youtube_quality_saver
        YouTubeAudioQuality.OPUS -> Res.string.youtube_quality_opus
    }
