// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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

/** The navigation host owns the modal window and Back; only the input draft is saveable. */
@Composable
public fun SleepTimerSheet(
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
        modifier.padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.sleep_title), style = MaterialTheme.typography.titleLarge)
        when (timer) {
            SleepTimerState.Off -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SLEEP_PRESETS.forEach { value ->
                        NdFilterChip(value == lastMinutes, {
                            onSet(SleepTimerMode.Minutes(value))
                        }, stringResource(Res.string.sleep_minutes, value), enabled = available)
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
                        enabled =
                            available && minutes != null,
                    ) {
                        Text(stringResource(Res.string.sleep_start))
                    }
                }
                TextButton(
                    { onSet(SleepTimerMode.EndOfEpisode) },
                    enabled = available,
                ) { Text(stringResource(Res.string.sleep_end)) }
            }

            is SleepTimerState.Running -> {
                Text(playerTime(timer.remainingMs), style = MaterialTheme.typography.displayMedium)
                if (!timer.counting) Text(stringResource(Res.string.sleep_waiting))
                Row {
                    TextButton(
                        { onExtend(5) },
                        enabled = available,
                    ) { Text(stringResource(Res.string.sleep_extend, 5)) }
                    TextButton(
                        { onExtend(15) },
                        enabled = available,
                    ) { Text(stringResource(Res.string.sleep_extend, 15)) }
                }
            }

            is SleepTimerState.EndOfEpisode -> {
                Text(stringResource(Res.string.sleep_end_active))
            }
        }
        TextButton({ onSet(SleepTimerMode.Off) }, enabled = available) { Text(stringResource(Res.string.sleep_off)) }
    }
}

private val SLEEP_PRESETS = listOf(5, 10, 15, 30, 45, 60, 90)
