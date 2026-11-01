// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.sleep_custom
import ch.lkmc.neutrodyne.feature.player.resources.sleep_end
import ch.lkmc.neutrodyne.feature.player.resources.sleep_end_active
import ch.lkmc.neutrodyne.feature.player.resources.sleep_extend
import ch.lkmc.neutrodyne.feature.player.resources.sleep_minutes
import ch.lkmc.neutrodyne.feature.player.resources.sleep_off
import ch.lkmc.neutrodyne.feature.player.resources.sleep_range
import ch.lkmc.neutrodyne.feature.player.resources.sleep_start
import ch.lkmc.neutrodyne.feature.player.resources.sleep_title
import ch.lkmc.neutrodyne.feature.player.resources.sleep_waiting
import org.jetbrains.compose.resources.stringResource

/**
 * The `SleepTimerKey` sheet's content (08 Speed and sleep sheets): Off presets + a custom 1–240
 * minute entry + End of episode while [timer] is `Off`, the live remaining readout with +5/+15
 * and a paused-waiting line while it runs, and Turn off in every state. Only the input draft is
 * saveable; the navigation host owns the modal window and Back. [available] is false without a
 * current item — the sheet dismisses itself since a timer command would be a no-op.
 */
@Composable
internal fun SleepTimerSheet(
    timer: SleepTimerState,
    lastMinutes: Int,
    available: Boolean,
    onSet: (SleepTimerMode) -> Unit,
    onExtend: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(available) { if (!available) dismiss() }
    var custom by rememberSaveable { mutableStateOf(lastMinutes !in SLEEP_PRESETS) }
    var input by rememberSaveable { mutableStateOf(lastMinutes.toString()) }
    val minutes = input.toIntOrNull()?.takeIf(SleepTimerMode::isValidMinutes)
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(SHEET_PADDING),
        verticalArrangement = Arrangement.spacedBy(SHEET_GAP),
    ) {
        Text(stringResource(Res.string.sleep_title), style = MaterialTheme.typography.titleLarge)
        when (timer) {
            SleepTimerState.Off -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                    SLEEP_PRESETS.forEach { value ->
                        NdFilterChip(
                            value == lastMinutes,
                            { onSet(SleepTimerMode.Minutes(value)) },
                            stringResource(Res.string.sleep_minutes, value),
                            enabled = available,
                        )
                    }
                    NdFilterChip(
                        custom,
                        { custom = true },
                        stringResource(Res.string.sleep_custom),
                        enabled = available,
                    )
                }
                if (custom) {
                    OutlinedTextField(
                        input,
                        { input = it },
                        label = { Text(stringResource(Res.string.sleep_range)) },
                        isError = input.isNotEmpty() && minutes == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    TextButton(
                        onClick = { minutes?.let { onSet(SleepTimerMode.Minutes(it)) } },
                        enabled = available && minutes != null,
                    ) {
                        Text(stringResource(Res.string.sleep_start))
                    }
                }
                TextButton({ onSet(SleepTimerMode.EndOfEpisode) }, enabled = available) {
                    Text(stringResource(Res.string.sleep_end))
                }
            }

            is SleepTimerState.Running -> {
                val remaining = playerTime(timer.remainingMs)
                Text(
                    remaining,
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.semantics { contentDescription = remaining },
                )
                if (!timer.counting) {
                    Text(
                        stringResource(Res.string.sleep_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                    TextButton({ onExtend(EXTEND_SHORT_MINUTES) }, enabled = available) {
                        Text(stringResource(Res.string.sleep_extend, EXTEND_SHORT_MINUTES))
                    }
                    TextButton({ onExtend(EXTEND_LONG_MINUTES) }, enabled = available) {
                        Text(stringResource(Res.string.sleep_extend, EXTEND_LONG_MINUTES))
                    }
                }
            }

            is SleepTimerState.EndOfEpisode -> {
                Text(
                    stringResource(Res.string.sleep_end_active),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        TextButton({ onSet(SleepTimerMode.Off) }, enabled = available) {
            Text(stringResource(Res.string.sleep_off))
        }
    }
}

private val SLEEP_PRESETS = listOf(5, 10, 15, 30, 45, 60, 90)
private const val EXTEND_SHORT_MINUTES = 5
private const val EXTEND_LONG_MINUTES = 15
private val SHEET_PADDING = 24.dp
private val SHEET_GAP = 12.dp
private val CHIP_GAP = 8.dp
