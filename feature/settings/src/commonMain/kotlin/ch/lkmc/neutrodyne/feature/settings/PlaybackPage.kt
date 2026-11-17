// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.BatToggleFace
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.EngravedHeading
import ch.lkmc.neutrodyne.core.designsystem.radio.Faceplate
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.NeedleWindow
import ch.lkmc.neutrodyne.core.designsystem.radio.PanelLabel
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.designsystem.radio.StepCounter
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceRow
import ch.lkmc.neutrodyne.core.ui.SettingsRow
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.feeds_not_on_desktop
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_off
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_on
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.core.ui.root.ndTextFieldFocus
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.playback_cache_mb
import ch.lkmc.neutrodyne.feature.settings.resources.playback_cache_used
import ch.lkmc.neutrodyne.feature.settings.resources.playback_clear
import ch.lkmc.neutrodyne.feature.settings.resources.playback_clear_cache
import ch.lkmc.neutrodyne.feature.settings.resources.playback_clear_cache_confirm
import ch.lkmc.neutrodyne.feature.settings.resources.playback_default_speed
import ch.lkmc.neutrodyne.feature.settings.resources.playback_metered_allow
import ch.lkmc.neutrodyne.feature.settings.resources.playback_metered_ask
import ch.lkmc.neutrodyne.feature.settings.resources.playback_metered_never
import ch.lkmc.neutrodyne.feature.settings.resources.playback_pause_for_navigation
import ch.lkmc.neutrodyne.feature.settings.resources.playback_presets_invalid
import ch.lkmc.neutrodyne.feature.settings.resources.playback_presets_label
import ch.lkmc.neutrodyne.feature.settings.resources.playback_seconds
import ch.lkmc.neutrodyne.feature.settings.resources.playback_seconds_short
import ch.lkmc.neutrodyne.feature.settings.resources.playback_skip_back
import ch.lkmc.neutrodyne.feature.settings.resources.playback_skip_forward
import ch.lkmc.neutrodyne.feature.settings.resources.playback_skip_silence
import ch.lkmc.neutrodyne.feature.settings.resources.playback_smart_resume
import ch.lkmc.neutrodyne.feature.settings.resources.playback_speed_presets
import ch.lkmc.neutrodyne.feature.settings.resources.playback_speed_value
import ch.lkmc.neutrodyne.feature.settings.resources.playback_step_down
import ch.lkmc.neutrodyne.feature.settings.resources.playback_step_up
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
        // 08 Scrollbars: the desktop draws an overlay thumb at the page's end edge.
        val scroll = rememberScrollState()
        val bars = LocalScrollbars.current
        val barColor = ndScrollbarColor(bars)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .ndScrollbar(scroll, bars, barColor),
        ) {
            PlaybackPanel(
                state,
                platform,
                PlaybackPanelActions(
                    setSpeed = viewModel::setSpeed,
                    setSkipBackMs = viewModel::setSkipBackMs,
                    setSkipForwardMs = viewModel::setSkipForwardMs,
                    setSkipSilence = viewModel::setSkipSilence,
                    setSmartResume = viewModel::setSmartResume,
                    setPauseForNavigation = viewModel::setPauseForNavigation,
                    openSpeedDialog = { dialog = PlaybackDialog.SPEED.name },
                ),
            )
            SettingsRow(NdIcons.Edit, stringResource(Res.string.playback_speed_presets), state.speedPresets, {
                dialog =
                    PlaybackDialog.PRESETS.name
            })
            if (platform == PlatformKind.ANDROID) {
                SettingsChoiceRow(
                    NdIcons.Wifi,
                    stringResource(Res.string.playback_stream_on_metered),
                    null,
                    StreamOnMetered.entries,
                    state.streamOnMetered,
                    { stringResource(meteredLabel(it)) },
                    viewModel::setStreamOnMetered,
                )
            } else {
                SettingsRow(
                    NdIcons.Wifi,
                    stringResource(Res.string.playback_stream_on_metered),
                    stringResource(UiRes.string.feeds_not_on_desktop),
                    null,
                )
            }
            EngravedHeading(
                stringResource(Res.string.playback_storage),
                Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp),
            )
            SettingsChoiceRow(
                NdIcons.Memory,
                stringResource(Res.string.playback_stream_cache),
                null,
                PlaybackSettingKeys.STREAM_CACHE_MB_VALUES.toList(),
                state.streamCacheMb,
                { stringResource(Res.string.playback_cache_mb, it) },
                viewModel::setStreamCacheMb,
            )
            state.cacheBytes?.let {
                Text(
                    stringResource(Res.string.playback_cache_used, (it / BYTES_PER_MB).toString()),
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
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
                    }, isError = !valid, singleLine = true, modifier = Modifier.ndTextFieldFocus())
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

/**
 * The playback controls mounted on a metal panel (PO-55): the default speed as a counter beside
 * a needle dial (its window opens the exact-speed dialog), skip back and forward as counters
 * that step through the allowed intervals, and bat-handle switches for the on/off defaults.
 */
@Composable
private fun PlaybackPanel(
    state: PlaybackSettingsUiState,
    platform: PlatformKind,
    actions: PlaybackPanelActions,
) {
    Faceplate(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(3.dp, PanelShape),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val speedLabel = stringResource(Res.string.playback_default_speed)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepCounter(
                    label = speedLabel,
                    value = stringResource(Res.string.playback_speed_value, state.speed.toString()),
                    onDecrease =
                        stepSpeed(state.speed, -SPEED_KEY_STEP)?.let { slower -> { actions.setSpeed(slower) } },
                    onIncrease =
                        stepSpeed(state.speed, SPEED_KEY_STEP)?.let { faster -> { actions.setSpeed(faster) } },
                    decreaseLabel = stringResource(Res.string.playback_step_down, speedLabel),
                    increaseLabel = stringResource(Res.string.playback_step_up, speedLabel),
                    onWindowClick = actions.openSpeedDialog,
                )
                NeedleWindow(
                    (state.speed - PlaybackSettingKeys.SPEED_MIN) /
                        (PlaybackSettingKeys.SPEED_MAX - PlaybackSettingKeys.SPEED_MIN),
                    Modifier.weight(1f).height(SPEED_DIAL_HEIGHT).padding(bottom = 12.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkipCounter(
                    stringResource(Res.string.playback_skip_back),
                    state.skipBackMs,
                    PlaybackSettingKeys.SKIP_BACK_MS_VALUES,
                    actions.setSkipBackMs,
                    Modifier.weight(1f),
                )
                SkipCounter(
                    stringResource(Res.string.playback_skip_forward),
                    state.skipForwardMs,
                    PlaybackSettingKeys.SKIP_FORWARD_MS_VALUES,
                    actions.setSkipForwardMs,
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelSwitch(
                    stringResource(Res.string.playback_skip_silence),
                    state.skipSilence,
                    actions.setSkipSilence,
                    Modifier.weight(1f),
                )
                PanelSwitch(
                    stringResource(Res.string.playback_smart_resume),
                    state.smartResume,
                    actions.setSmartResume,
                    Modifier.weight(1f),
                )
                if (platform == PlatformKind.ANDROID) {
                    PanelSwitch(
                        stringResource(Res.string.playback_pause_for_navigation),
                        state.pauseForNavigation,
                        actions.setPauseForNavigation,
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** What the panel's controls write; the page wires them to its view model. */
private class PlaybackPanelActions(
    val setSpeed: (Float) -> Unit,
    val setSkipBackMs: (Long) -> Unit,
    val setSkipForwardMs: (Long) -> Unit,
    val setSkipSilence: (Boolean) -> Unit,
    val setSmartResume: (Boolean) -> Unit,
    val setPauseForNavigation: (Boolean) -> Unit,
    val openSpeedDialog: () -> Unit,
)

/** A skip interval counter that steps to the neighbouring allowed value. */
@Composable
private fun SkipCounter(
    label: String,
    ms: Long,
    values: Set<Long>,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = values.sorted()
    val shorter = sorted.lastOrNull { it < ms }
    val longer = sorted.firstOrNull { it > ms }
    StepCounter(
        label = label,
        value = stringResource(Res.string.playback_seconds_short, ms / MILLIS_PER_SECOND),
        onDecrease = shorter?.let { { onChange(it) } },
        onIncrease = longer?.let { { onChange(it) } },
        decreaseLabel = stringResource(Res.string.playback_step_down, label),
        increaseLabel = stringResource(Res.string.playback_step_up, label),
        modifier = modifier,
        spokenValue = secondsText(ms),
    )
}

/**
 * An on/off default as a bat-handle switch on the panel with its engraved name below. The whole
 * cell is the switch, named by [label].
 */
@Composable
private fun PanelSwitch(
    label: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .toggleable(on, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BatToggleFace(
            on,
            stringResource(UiRes.string.radio_switch_on),
            stringResource(UiRes.string.radio_switch_off),
            Modifier.size(PANEL_SWITCH_WIDTH, PANEL_SWITCH_HEIGHT),
        )
        PanelLabel(label, Modifier.fillMaxWidth().wrapContentWidth(), textAlign = TextAlign.Center)
    }
}

/** The speed one key press away, or null past the end of the range. */
private fun stepSpeed(
    speed: Float,
    step: Float,
): Float? {
    val next = PlaybackSettingKeys.normalizeSpeed(speed + step)
    return next.takeIf { it != speed }
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

private enum class PlaybackDialog { SPEED, PRESETS, CLEAR }

private const val MILLIS_PER_SECOND = 1_000L
private const val BYTES_PER_MB = 1_000_000L
private const val SPEED_SLIDER_STEPS = 49
private const val SPEED_KEY_STEP = 0.1f
private val PanelShape = RoundedCornerShape(8.dp)
private val SPEED_DIAL_HEIGHT = 56.dp
private val PANEL_SWITCH_WIDTH = 40.dp
private val PANEL_SWITCH_HEIGHT = 50.dp
