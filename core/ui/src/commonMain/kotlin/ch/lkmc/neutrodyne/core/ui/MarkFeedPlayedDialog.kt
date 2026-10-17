// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.feeds_mark_all_played
import ch.lkmc.neutrodyne.core.ui.resources.mark_played_all
import ch.lkmc.neutrodyne.core.ui.resources.mark_played_confirm
import ch.lkmc.neutrodyne.core.ui.resources.mark_played_count
import ch.lkmc.neutrodyne.core.ui.resources.mark_played_counting
import ch.lkmc.neutrodyne.core.ui.resources.mark_played_month
import ch.lkmc.neutrodyne.core.ui.resources.mark_played_no_undo
import ch.lkmc.neutrodyne.core.ui.resources.mark_played_selection_changed
import ch.lkmc.neutrodyne.core.ui.resources.mark_played_three_months
import ch.lkmc.neutrodyne.core.ui.resources.mark_played_week
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
public fun MarkFeedPlayedDialog(
    state: MarkFeedPlayedState,
    onOption: (MarkPlayedOption) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NdDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        icon = NdIcons.DoneAll,
        title = stringResource(Res.string.feeds_mark_all_played),
        confirm =
            if (!state.busy && (state.count ?: 0) > 0) {
                NdDialogAction(
                    stringResource(Res.string.mark_played_confirm),
                    role = NdActionRole.DESTRUCTIVE,
                    onClick = onConfirm,
                )
            } else {
                null
            },
        dismiss =
            if (state.phase != MarkPlayedPhase.WRITING) {
                NdDialogAction(stringResource(Res.string.action_cancel), onClick = onDismiss)
            } else {
                null
            },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.selectableGroup()) {
                for (option in MarkPlayedOption.entries) {
                    val label =
                        when (option) {
                            MarkPlayedOption.ALL -> Res.string.mark_played_all
                            MarkPlayedOption.WEEK -> Res.string.mark_played_week
                            MarkPlayedOption.MONTH -> Res.string.mark_played_month
                            MarkPlayedOption.THREE_MONTHS -> Res.string.mark_played_three_months
                        }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = OPTION_MIN_HEIGHT)
                            .selectable(
                                selected = state.option == option,
                                enabled = state.phase != MarkPlayedPhase.WRITING,
                                role = Role.RadioButton,
                                onClick = { onOption(option) },
                            ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.option == option,
                            onClick = null,
                            enabled =
                                state.phase != MarkPlayedPhase.WRITING,
                        )
                        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                if (state.selectionChanged) Text(stringResource(Res.string.mark_played_selection_changed))
                if (state.phase == MarkPlayedPhase.COUNTING) {
                    Text(stringResource(Res.string.mark_played_counting))
                } else {
                    state.count?.let { count ->
                        Text(pluralStringResource(Res.plurals.mark_played_count, count, count, state.title.asString()))
                    }
                }
                state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            }
            Text(stringResource(Res.string.mark_played_no_undo), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Each whole row is the radio target, at least the 48 dp touch target (08). */
private val OPTION_MIN_HEIGHT = 48.dp
