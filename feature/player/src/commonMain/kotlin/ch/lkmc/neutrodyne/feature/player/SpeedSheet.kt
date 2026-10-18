// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdAssistChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdOutlinedButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.ui.AttributionText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.speed_apply
import ch.lkmc.neutrodyne.feature.player.resources.speed_apply_to
import ch.lkmc.neutrodyne.feature.player.resources.speed_decrease
import ch.lkmc.neutrodyne.feature.player.resources.speed_increase
import ch.lkmc.neutrodyne.feature.player.resources.speed_nothing_playing
import ch.lkmc.neutrodyne.feature.player.resources.speed_presets_edit
import ch.lkmc.neutrodyne.feature.player.resources.speed_presets_field
import ch.lkmc.neutrodyne.feature.player.resources.speed_presets_invalid
import ch.lkmc.neutrodyne.feature.player.resources.speed_presets_title
import ch.lkmc.neutrodyne.feature.player.resources.speed_scope_all
import ch.lkmc.neutrodyne.feature.player.resources.speed_scope_group
import ch.lkmc.neutrodyne.feature.player.resources.speed_scope_group_any
import ch.lkmc.neutrodyne.feature.player.resources.speed_scope_group_unavailable
import ch.lkmc.neutrodyne.feature.player.resources.speed_scope_podcast
import ch.lkmc.neutrodyne.feature.player.resources.speed_skip_silence
import ch.lkmc.neutrodyne.feature.player.resources.speed_title
import ch.lkmc.neutrodyne.feature.player.resources.speed_value
import ch.lkmc.neutrodyne.feature.player.resources.speed_warning_group_silence
import ch.lkmc.neutrodyne.feature.player.resources.speed_warning_group_speed
import ch.lkmc.neutrodyne.feature.player.resources.speed_warning_podcast_silence
import ch.lkmc.neutrodyne.feature.player.resources.speed_warning_podcast_speed
import ch.lkmc.neutrodyne.feature.player.resources.speed_warning_title
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * The `SpeedKey` sheet's content (08 Speed and sleep sheets); the navigation host supplies the
 * window-based modal sheet and its dismiss gestures. Chips, the slider and the switch write at
 * the selected scope (06 Per-scope playback settings); a broader pick that would drop a narrower
 * override is confirmed first. The preset list is a global setting, not a scope write.
 */
@Composable
public fun SpeedSheet(
    now: NowPlaying?,
    effective: EffectivePlayback?,
    presets: String,
    onSpeed: (Float, SettingScope) -> Unit,
    onSkipSilence: (Boolean, SettingScope) -> Unit,
    onPresets: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    resetScopeToken: Int = 0,
) {
    // `NOTHING_PLAYING` closes the sheet (08); a vanished current item means the same here.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(now == null) { if (now == null) currentOnDismiss() }

    val groupContext = now?.context?.takeIf { it.type == ContextType.GROUP }
    // Enums are not saveable; the persisted pick is its name, null meaning "follow the source".
    var scopeName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected =
        scopeName?.let { name -> SettingScope.entries.firstOrNull { it.name == name } }
            ?: scopeFor(effective?.speed?.source)
    LaunchedEffect(resetScopeToken) {
        if (resetScopeToken > 0) scopeName = SettingScope.PODCAST.name
    }
    var pending by remember(now?.episodeId) { mutableStateOf<PendingWrite?>(null) }
    var presetDialog by rememberSaveable { mutableStateOf(false) }
    var dragSpeed by remember { mutableStateOf<Float?>(null) }
    var committedSpeed by remember { mutableStateOf<Float?>(null) }
    var committedSilence by remember { mutableStateOf<Boolean?>(null) }
    val shownSpeed =
        dragSpeed ?: committedSpeed ?: effective?.speed?.value ?: PlaybackSettingKeys.DEFAULT_SPEED
    val shownSilence = committedSilence ?: effective?.skipSilence?.value ?: false

    // A resolved value supersedes the optimistic display; a lost group context drops the
    // GROUP pick back to This podcast (08: the selector falls back on NO_CONTEXT_GROUP).
    LaunchedEffect(effective?.speed?.value) { committedSpeed = null }
    LaunchedEffect(effective?.skipSilence?.value) { committedSilence = null }
    LaunchedEffect(groupContext != null) {
        if (groupContext == null && selected == SettingScope.GROUP) scopeName = SettingScope.PODCAST.name
    }

    fun commitSpeed(raw: Float) {
        val value = PlaybackSettingKeys.normalizeSpeed(raw)
        dragSpeed = null
        if (scopeRank(selected) < sourceRank(effective?.speed?.source)) {
            pending = PendingWrite.Speed(value, selected)
        } else {
            committedSpeed = value
            onSpeed(value, selected)
        }
    }

    fun requestSilence(value: Boolean) {
        if (scopeRank(selected) < sourceRank(effective?.skipSilence?.source)) {
            pending = PendingWrite.Silence(value, selected)
        } else {
            committedSilence = value
            onSkipSilence(value, selected)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(SHEET_PADDING),
        verticalArrangement = Arrangement.spacedBy(SHEET_GAP),
    ) {
        if (now == null) {
            Text(stringResource(Res.string.speed_nothing_playing), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.speed_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(Res.string.speed_value, formatSpeed(shownSpeed)),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        val presetValues =
            PlaybackSettingKeys.parseSpeedPresets(presets)
                ?: PlaybackSettingKeys.parseSpeedPresets(PlaybackSettingKeys.DEFAULT_SPEED_PRESETS).orEmpty()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
            for (preset in presetValues) {
                NdFilterChip(
                    selected = preset == shownSpeed,
                    onClick = { commitSpeed(preset) },
                    label = formatPreset(preset),
                )
            }
            NdAssistChip(
                onClick = { presetDialog = true },
                label = stringResource(Res.string.speed_presets_edit),
                leadingIcon = NdIcons.Edit,
            )
        }

        val speedLabel = stringResource(Res.string.speed_title)
        val decreaseLabel = stringResource(Res.string.speed_decrease)
        val increaseLabel = stringResource(Res.string.speed_increase)
        Row(verticalAlignment = Alignment.CenterVertically) {
            NdOutlinedButton(
                label = "−",
                onClick = { commitSpeed(shownSpeed - PlaybackSettingKeys.SPEED_STEP) },
                enabled = shownSpeed > PlaybackSettingKeys.SPEED_MIN,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = decreaseLabel },
            )
            Slider(
                value =
                    (dragSpeed ?: shownSpeed).coerceIn(
                        PlaybackSettingKeys.SPEED_MIN,
                        PlaybackSettingKeys.SPEED_MAX,
                    ),
                onValueChange = { dragSpeed = PlaybackSettingKeys.normalizeSpeed(it) },
                onValueChangeFinished = { dragSpeed?.let(::commitSpeed) },
                valueRange = PlaybackSettingKeys.SPEED_MIN..PlaybackSettingKeys.SPEED_MAX,
                steps = SLIDER_STEPS,
                modifier =
                    Modifier.weight(1f).semantics(mergeDescendants = true) {
                        contentDescription = speedLabel
                    },
            )
            NdOutlinedButton(
                label = "+",
                onClick = { commitSpeed(shownSpeed + PlaybackSettingKeys.SPEED_STEP) },
                enabled = shownSpeed < PlaybackSettingKeys.SPEED_MAX,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = increaseLabel },
            )
        }

        effective?.let {
            AttributionLine(AttributionText.describe(it.speed.source).asString())
        }

        val silenceLabel = stringResource(Res.string.speed_skip_silence)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(silenceLabel, style = MaterialTheme.typography.bodyLarge)
                effective?.let {
                    Text(
                        AttributionText.describe(it.skipSilence.source).asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = shownSilence,
                onCheckedChange = { requestSilence(it) },
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = silenceLabel },
            )
        }

        Text(stringResource(Res.string.speed_apply_to), style = MaterialTheme.typography.labelLarge)
        val scopes = listOf(SettingScope.PODCAST, SettingScope.GROUP, SettingScope.GLOBAL)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            scopes.forEachIndexed { index, scope ->
                SegmentedButton(
                    selected = selected == scope,
                    onClick = { scopeName = scope.name },
                    enabled = scope != SettingScope.GROUP || groupContext != null,
                    shape = SegmentedButtonDefaults.itemShape(index, scopes.size),
                ) {
                    Text(
                        when (scope) {
                            SettingScope.PODCAST -> {
                                stringResource(Res.string.speed_scope_podcast)
                            }

                            SettingScope.GROUP -> {
                                groupContext?.let { stringResource(Res.string.speed_scope_group, it.title) }
                                    ?: stringResource(Res.string.speed_scope_group_any)
                            }

                            SettingScope.GLOBAL -> {
                                stringResource(Res.string.speed_scope_all)
                            }
                        },
                    )
                }
            }
        }
        if (groupContext == null) {
            Text(
                stringResource(Res.string.speed_scope_group_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pending?.let { write ->
        NdDialog(
            onDismissRequest = { pending = null },
            title = stringResource(Res.string.speed_warning_title),
            text = warningText(write, effective),
            confirm =
                NdDialogAction(
                    label = stringResource(Res.string.speed_apply),
                    onClick = {
                        pending = null
                        when (write) {
                            is PendingWrite.Speed -> {
                                committedSpeed = write.value
                                onSpeed(write.value, write.scope)
                            }

                            is PendingWrite.Silence -> {
                                committedSilence = write.value
                                onSkipSilence(write.value, write.scope)
                            }
                        }
                    },
                ),
            dismiss =
                NdDialogAction(
                    label = stringResource(UiRes.string.action_cancel),
                    onClick = { pending = null },
                ),
        )
    }

    if (presetDialog) {
        var text by rememberSaveable { mutableStateOf(presets) }
        val valid = PlaybackSettingKeys.parseSpeedPresets(text) != null
        NdDialog(
            onDismissRequest = { presetDialog = false },
            title = stringResource(Res.string.speed_presets_title),
        ) {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(Res.string.speed_presets_field)) },
                    isError = !valid,
                    supportingText = {
                        if (!valid) {
                            Text(
                                stringResource(Res.string.speed_presets_invalid),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    NdTextButton(
                        label = stringResource(UiRes.string.action_cancel),
                        onClick = { presetDialog = false },
                    )
                    NdTextButton(
                        label = stringResource(UiRes.string.action_save),
                        enabled = valid,
                        onClick = {
                            presetDialog = false
                            onPresets(text.trim())
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AttributionLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun warningText(
    write: PendingWrite,
    effective: EffectivePlayback?,
): String? =
    when (write) {
        is PendingWrite.Speed -> {
            val removed = formatSpeed(effective?.speed?.value ?: write.value)
            when (val source = effective?.speed?.source) {
                SettingSource.PodcastOverride -> {
                    stringResource(Res.string.speed_warning_podcast_speed, removed)
                }

                is SettingSource.Group -> {
                    stringResource(Res.string.speed_warning_group_speed, source.name, removed)
                }

                is SettingSource.Groups -> {
                    source.groups.firstOrNull()?.let {
                        stringResource(Res.string.speed_warning_group_speed, it.name, removed)
                    }
                }

                else -> {
                    null
                }
            }
        }

        is PendingWrite.Silence -> {
            when (val source = effective?.skipSilence?.source) {
                SettingSource.PodcastOverride -> {
                    stringResource(Res.string.speed_warning_podcast_silence)
                }

                is SettingSource.Group -> {
                    stringResource(Res.string.speed_warning_group_silence, source.name)
                }

                is SettingSource.Groups -> {
                    source.groups.firstOrNull()?.let { stringResource(Res.string.speed_warning_group_silence, it.name) }
                }

                else -> {
                    null
                }
            }
        }
    }

/** A confirmed-but-unapplied write; [scope] is frozen so the warning matches what was chosen. */
private sealed interface PendingWrite {
    val scope: SettingScope

    data class Speed(
        val value: Float,
        override val scope: SettingScope,
    ) : PendingWrite

    data class Silence(
        val value: Boolean,
        override val scope: SettingScope,
    ) : PendingWrite
}

private fun scopeFor(source: SettingSource?): SettingScope =
    when (source) {
        SettingSource.PodcastOverride -> SettingScope.PODCAST
        is SettingSource.Group, is SettingSource.Groups -> SettingScope.GROUP
        else -> SettingScope.GLOBAL
    }

/** Ranks by specificity: writing a scope below the value's source rank drops that override. */
private fun scopeRank(scope: SettingScope): Int =
    when (scope) {
        SettingScope.PODCAST -> 3
        SettingScope.GROUP -> 2
        SettingScope.GLOBAL -> 1
    }

private fun sourceRank(source: SettingSource?): Int =
    when (source) {
        SettingSource.PodcastOverride -> 3
        is SettingSource.Group, is SettingSource.Groups -> 2
        else -> 1
    }

/** Fixed two decimals — "1.20" — for the sheet's value readout and warning texts. */
private fun formatSpeed(value: Float): String {
    val hundredths = (value * 100).roundToInt()
    return "${hundredths / 100}." + (hundredths % 100).toString().padStart(2, '0')
}

/** Chip labels drop a trailing zero — "1.2" — while keeping a second decimal like "1.05". */
private fun formatPreset(value: Float): String {
    val text = formatSpeed(value)
    return if (text.endsWith('0')) text.dropLast(1) else text
}

private const val SLIDER_STEPS = 49
private val SHEET_PADDING = 24.dp
private val SHEET_GAP = 12.dp
private val CHIP_GAP = 8.dp
