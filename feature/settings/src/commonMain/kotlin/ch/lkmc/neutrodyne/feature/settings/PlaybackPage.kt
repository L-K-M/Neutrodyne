// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceDialog
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.feeds_not_on_desktop
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.playback_cache_mb
import ch.lkmc.neutrodyne.feature.settings.resources.playback_cache_used
import ch.lkmc.neutrodyne.feature.settings.resources.playback_clear
import ch.lkmc.neutrodyne.feature.settings.resources.playback_clear_cache
import ch.lkmc.neutrodyne.feature.settings.resources.playback_clear_cache_confirm
import ch.lkmc.neutrodyne.feature.settings.resources.playback_default_speed
import ch.lkmc.neutrodyne.feature.settings.resources.playback_hardware_buttons
import ch.lkmc.neutrodyne.feature.settings.resources.playback_hardware_episode
import ch.lkmc.neutrodyne.feature.settings.resources.playback_hardware_skip
import ch.lkmc.neutrodyne.feature.settings.resources.playback_metered_allow
import ch.lkmc.neutrodyne.feature.settings.resources.playback_metered_ask
import ch.lkmc.neutrodyne.feature.settings.resources.playback_metered_never
import ch.lkmc.neutrodyne.feature.settings.resources.playback_pause_for_navigation
import ch.lkmc.neutrodyne.feature.settings.resources.playback_presets_invalid
import ch.lkmc.neutrodyne.feature.settings.resources.playback_presets_label
import ch.lkmc.neutrodyne.feature.settings.resources.playback_seconds
import ch.lkmc.neutrodyne.feature.settings.resources.playback_skip_back
import ch.lkmc.neutrodyne.feature.settings.resources.playback_skip_forward
import ch.lkmc.neutrodyne.feature.settings.resources.playback_skip_silence
import ch.lkmc.neutrodyne.feature.settings.resources.playback_smart_resume
import ch.lkmc.neutrodyne.feature.settings.resources.playback_speed_presets
import ch.lkmc.neutrodyne.feature.settings.resources.playback_speed_value
import ch.lkmc.neutrodyne.feature.settings.resources.playback_storage
import ch.lkmc.neutrodyne.feature.settings.resources.playback_stream_cache
import ch.lkmc.neutrodyne.feature.settings.resources.playback_stream_on_metered
import ch.lkmc.neutrodyne.feature.settings.resources.settings_playback
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/** Playback defaults; scoped overrides are edited in the speed sheet. */
@Composable
internal fun PlaybackPage(
    platform: PlatformKind,
    viewModel: PlaybackSettingsViewModel = metroViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    ShowUserMessages(state.messages, viewModel::onMessageShown)
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(title = stringResource(Res.string.settings_playback), navigation = { SettingsBackButton() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsRow(
                NdIcons.PlayArrow,
                stringResource(
                    Res.string.playback_default_speed,
                ),
                stringResource(Res.string.playback_speed_value, state.speed.toString()),
                {
                    dialog =
                        PlaybackDialog.SPEED.name
                },
            )
            SettingsSwitchRow(
                stringResource(Res.string.playback_skip_silence),
                null,
                state.skipSilence,
                viewModel::setSkipSilence,
            )
            SettingsRow(NdIcons.Edit, stringResource(Res.string.playback_speed_presets), state.speedPresets, {
                dialog =
                    PlaybackDialog.PRESETS.name
            })
            SettingsRow(
                NdIcons.Schedule,
                stringResource(
                    Res.string.playback_skip_back,
                ),
                secondsText(state.skipBackMs),
                {
                    dialog =
                        PlaybackDialog.BACK.name
                },
            )
            SettingsRow(
                NdIcons.Schedule,
                stringResource(
                    Res.string.playback_skip_forward,
                ),
                secondsText(state.skipForwardMs),
                {
                    dialog =
                        PlaybackDialog.FORWARD.name
                },
            )
            SettingsSwitchRow(
                stringResource(Res.string.playback_smart_resume),
                null,
                state.smartResume,
                viewModel::setSmartResume,
            )
            if (platform == PlatformKind.ANDROID) {
                SettingsRow(
                    NdIcons.PlayArrow,
                    stringResource(Res.string.playback_hardware_buttons),
                    stringResource(hardwareLabel(state.hardwareButtons)),
                    { dialog = PlaybackDialog.HARDWARE.name },
                )
                SettingsSwitchRow(
                    stringResource(Res.string.playback_pause_for_navigation),
                    null,
                    state.pauseForNavigation,
                    viewModel::setPauseForNavigation,
                )
                SettingsRow(
                    NdIcons.Wifi,
                    stringResource(
                        Res.string.playback_stream_on_metered,
                    ),
                    stringResource(meteredLabel(state.streamOnMetered)),
                    {
                        dialog =
                            PlaybackDialog.METERED.name
                    },
                )
            } else {
                ListItem(headlineContent = {
                    Text(stringResource(Res.string.playback_stream_on_metered))
                }, supportingContent = { Text(stringResource(UiRes.string.feeds_not_on_desktop)) })
            }
            Text(
                stringResource(Res.string.playback_storage),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            SettingsRow(
                NdIcons.Memory,
                stringResource(
                    Res.string.playback_stream_cache,
                ),
                stringResource(Res.string.playback_cache_mb, state.streamCacheMb),
                {
                    dialog =
                        PlaybackDialog.CACHE.name
                },
            )
            state.cacheBytes?.let {
                Text(
                    stringResource(Res.string.playback_cache_used, (it / BYTES_PER_MB).toString()),
                    Modifier.padding(horizontal = 16.dp),
                )
            }
            SettingsRow(NdIcons.Refresh, stringResource(Res.string.playback_clear_cache), null, {
                dialog =
                    PlaybackDialog.CLEAR.name
            }, enabled = !state.cacheBusy)
        }
    }
    val selected = dialog?.let { name -> PlaybackDialog.entries.firstOrNull { it.name == name } }
    when (selected) {
        PlaybackDialog.BACK, PlaybackDialog.FORWARD -> {
            val backward = selected == PlaybackDialog.BACK
            val title = if (backward) Res.string.playback_skip_back else Res.string.playback_skip_forward
            val choices =
                if (backward) {
                    PlaybackSettingKeys.SKIP_BACK_MS_VALUES
                } else {
                    PlaybackSettingKeys.SKIP_FORWARD_MS_VALUES
                }
            SettingsChoiceDialog(
                icon = NdIcons.Schedule,
                title = stringResource(title),
                options = choices.toList(),
                selected = if (backward) state.skipBackMs else state.skipForwardMs,
                label = { secondsText(it) },
                onSelect = {
                    if (backward) viewModel.setSkipBackMs(it) else viewModel.setSkipForwardMs(it)
                    dialog =
                        null
                },
                onDismissRequest = { dialog = null },
            )
        }

        PlaybackDialog.CACHE -> {
            SettingsChoiceDialog(
                icon = NdIcons.Memory,
                title = stringResource(Res.string.playback_stream_cache),
                options = PlaybackSettingKeys.STREAM_CACHE_MB_VALUES.toList(),
                selected = state.streamCacheMb,
                label = { stringResource(Res.string.playback_cache_mb, it) },
                onSelect = {
                    viewModel.setStreamCacheMb(it)
                    dialog = null
                },
                onDismissRequest = { dialog = null },
            )
        }

        PlaybackDialog.HARDWARE -> {
            SettingsChoiceDialog(
                icon = NdIcons.PlayArrow,
                title = stringResource(Res.string.playback_hardware_buttons),
                options = HardwareButtons.entries,
                selected = state.hardwareButtons,
                label = { stringResource(hardwareLabel(it)) },
                onSelect = {
                    viewModel.setHardwareButtons(it)
                    dialog = null
                },
                onDismissRequest = { dialog = null },
            )
        }

        PlaybackDialog.METERED -> {
            SettingsChoiceDialog(
                icon = NdIcons.Wifi,
                title = stringResource(Res.string.playback_stream_on_metered),
                options = StreamOnMetered.entries,
                selected = state.streamOnMetered,
                label = { stringResource(meteredLabel(it)) },
                onSelect = {
                    viewModel.setStreamOnMetered(it)
                    dialog = null
                },
                onDismissRequest = { dialog = null },
            )
        }

        PlaybackDialog.SPEED -> {
            var draft by rememberSaveable { mutableStateOf(state.speed) }
            NdDialog(
                onDismissRequest = { dialog = null },
                title = stringResource(Res.string.playback_default_speed),
                confirm =
                    NdDialogAction(stringResource(UiRes.string.action_save)) {
                        viewModel.setSpeed(draft)
                        dialog =
                            null
                    },
                dismiss = NdDialogAction(stringResource(UiRes.string.action_cancel)) { dialog = null },
            ) {
                Column {
                    Text(stringResource(Res.string.playback_speed_value, draft.toString()))
                    Slider(
                        value = draft,
                        onValueChange = { draft = PlaybackSettingKeys.normalizeSpeed(it) },
                        valueRange =
                            PlaybackSettingKeys.SPEED_MIN..PlaybackSettingKeys.SPEED_MAX,
                        steps = SPEED_SLIDER_STEPS,
                    )
                }
            }
        }

        PlaybackDialog.PRESETS -> {
            var draft by rememberSaveable { mutableStateOf(state.speedPresets) }
            val valid = PlaybackSettingKeys.parseSpeedPresets(draft) != null
            NdDialog(onDismissRequest = { dialog = null }, title = stringResource(Res.string.playback_speed_presets)) {
                Column {
                    OutlinedTextField(value = draft, onValueChange = {
                        draft = it
                    }, label = {
                        Text(
                            stringResource(Res.string.playback_presets_label),
                        )
                    }, isError = !valid, singleLine = true)
                    if (!valid) {
                        Text(
                            stringResource(Res.string.playback_presets_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        NdTextButton(stringResource(UiRes.string.action_cancel), onClick = { dialog = null })
                        NdTextButton(stringResource(UiRes.string.action_save), enabled = valid, onClick = {
                            viewModel.setSpeedPresets(draft)
                            dialog =
                                null
                        })
                    }
                }
            }
        }

        PlaybackDialog.CLEAR -> {
            NdDialog(
                onDismissRequest = {
                    dialog = null
                },
                title = stringResource(Res.string.playback_clear_cache),
                text = stringResource(Res.string.playback_clear_cache_confirm),
                confirm =
                    NdDialogAction(stringResource(Res.string.playback_clear)) {
                        viewModel.clearStreamingCache()
                        dialog =
                            null
                    },
                dismiss = NdDialogAction(stringResource(UiRes.string.action_cancel)) { dialog = null },
            )
        }

        null -> {
            Unit
        }
    }
}

@Composable
private fun secondsText(ms: Long): String =
    pluralStringResource(
        Res.plurals.playback_seconds,
        (ms / MILLIS_PER_SECOND).toInt(),
        ms / MILLIS_PER_SECOND,
    )

private fun meteredLabel(policy: StreamOnMetered): StringResource =
    when (policy) {
        StreamOnMetered.ALLOW -> Res.string.playback_metered_allow
        StreamOnMetered.ASK -> Res.string.playback_metered_ask
        StreamOnMetered.NEVER -> Res.string.playback_metered_never
    }

private enum class PlaybackDialog {
    HARDWARE,
    SPEED,
    PRESETS,
    BACK,
    FORWARD,
    METERED,
    CACHE,
    CLEAR,
}

private const val MILLIS_PER_SECOND = 1_000L
private const val BYTES_PER_MB = 1_000_000L
private const val SPEED_SLIDER_STEPS = 49

private fun hardwareLabel(mode: HardwareButtons): StringResource =
    when (mode) {
        HardwareButtons.EPISODE -> Res.string.playback_hardware_episode
        HardwareButtons.SKIP -> Res.string.playback_hardware_skip
    }
