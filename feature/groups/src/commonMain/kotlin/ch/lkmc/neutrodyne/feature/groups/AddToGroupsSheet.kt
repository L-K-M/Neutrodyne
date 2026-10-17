// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdAssistChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.membership_create
import ch.lkmc.neutrodyne.core.ui.resources.membership_done
import ch.lkmc.neutrodyne.core.ui.resources.membership_name
import ch.lkmc.neutrodyne.core.ui.resources.membership_new
import ch.lkmc.neutrodyne.core.ui.resources.membership_title_many
import ch.lkmc.neutrodyne.core.ui.resources.membership_title_one
import org.jetbrains.compose.resources.stringResource

/** The navigation host supplies the window-based modal sheet and its dismiss gestures. */
@Composable
internal fun AddToGroupsSheet(
    state: AddToGroupsUiState,
    onToggle: (Long) -> Unit,
    onName: (String) -> Unit,
    onCreate: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (state.podcastTitle != null) {
                stringResource(Res.string.membership_title_one, state.podcastTitle)
            } else {
                stringResource(Res.string.membership_title_many, state.podcastCount)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.chips.forEach { chip ->
                NdFilterChip(
                    state = chip.selection,
                    label = chip.group.name,
                    onClick = { onToggle(chip.group.id) },
                    enabled = !state.busy,
                )
            }
            NdAssistChip(
                label = stringResource(Res.string.membership_new),
                onClick = { onName("") },
                enabled = !state.busy,
            )
        }
        state.name?.let { name ->
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text(stringResource(Res.string.membership_name)) },
                singleLine = true,
                enabled = !state.busy,
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it.asString()) } },
            )
            NdTextButton(
                label = stringResource(Res.string.membership_create),
                onClick = onCreate,
                enabled = !state.busy,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NdTextButton(label = stringResource(Res.string.action_cancel), onClick = onDismiss)
            NdButton(
                label = stringResource(Res.string.membership_done),
                onClick = onDone,
                enabled =
                    state.ready && !state.busy,
            )
        }
    }
}
