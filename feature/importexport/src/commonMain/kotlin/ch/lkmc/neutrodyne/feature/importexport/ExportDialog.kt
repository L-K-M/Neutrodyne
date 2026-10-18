// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_continue
import ch.lkmc.neutrodyne.core.ui.resources.action_share
import ch.lkmc.neutrodyne.core.ui.resources.export_group_title_export
import ch.lkmc.neutrodyne.core.ui.resources.export_group_title_share
import ch.lkmc.neutrodyne.core.ui.resources.export_include_passwords
import ch.lkmc.neutrodyne.core.ui.resources.export_include_youtube
import ch.lkmc.neutrodyne.core.ui.resources.export_layout_flat
import ch.lkmc.neutrodyne.core.ui.resources.export_layout_flat_note
import ch.lkmc.neutrodyne.core.ui.resources.export_layout_grouped
import ch.lkmc.neutrodyne.core.ui.resources.export_passwords
import ch.lkmc.neutrodyne.core.ui.resources.export_private_links
import ch.lkmc.neutrodyne.core.ui.resources.export_save
import ch.lkmc.neutrodyne.core.ui.resources.export_title
import ch.lkmc.neutrodyne.core.ui.resources.export_youtube_note
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The dialog body [ExportRoute] renders inside the nav host's `Dialog` window (08 Export
 * dialog): layout radios and the YouTube/password switches while [ExportUiState.warning] is
 * `null`, the R1.9 counts plus Continue/Cancel while it is set. [canShare] is
 * `LocalPlatformActions.share != null`, so the desktop offers only "Save to file".
 */
@Composable
internal fun ExportDialogContent(
    state: ExportUiState,
    canShare: Boolean,
    onLayout: (ExportLayout) -> Unit,
    onYouTube: (Boolean) -> Unit,
    onPasswords: (Boolean) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onConfirmWarning: () -> Unit,
    onCancelWarning: () -> Unit,
) {
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(Modifier.padding(DIALOG_PADDING).verticalScroll(rememberScrollState())) {
            Text(
                exportTitle(state.groupName),
                style = MaterialTheme.typography.headlineSmall,
                color = AlertDialogDefaults.titleContentColor,
            )
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = PROGRESS_GAP))
            }
            Spacer(Modifier.height(BODY_GAP))
            val warning = state.warning
            if (warning != null) {
                PrivateLinksWarning(warning.privateLinks, warning.passwords)
            } else {
                OptionsBody(state, onLayout, onYouTube, onPasswords)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = ACTIONS_GAP),
                horizontalArrangement = Arrangement.End,
            ) {
                if (warning != null) {
                    NdTextButton(stringResource(Res.string.action_cancel), onCancelWarning)
                    NdButton(stringResource(Res.string.action_continue), onConfirmWarning)
                } else {
                    if (canShare) {
                        NdTextButton(
                            stringResource(Res.string.action_share),
                            onShare,
                            enabled = state.ready && !state.busy,
                        )
                    }
                    NdButton(
                        stringResource(Res.string.export_save),
                        onSave,
                        enabled = state.ready && !state.busy,
                    )
                }
            }
        }
    }
}

/** Android titles the action "Share … as OPML"; the desktop "Export … as OPML" (05 Group actions). */
@Composable
private fun exportTitle(groupName: String?): String =
    when {
        groupName == null -> {
            stringResource(Res.string.export_title)
        }

        LocalPlatformKind.current == PlatformKind.ANDROID -> {
            stringResource(Res.string.export_group_title_share, groupName)
        }

        else -> {
            stringResource(Res.string.export_group_title_export, groupName)
        }
    }

/** The R1.9 counts, quoted from the prepared artifact — never from a re-read of the library. */
@Composable
internal fun PrivateLinksWarning(
    privateLinks: Int,
    passwords: Int,
) {
    Column {
        if (privateLinks > 0) {
            Text(
                pluralStringResource(
                    Res.plurals.export_private_links,
                    privateLinks,
                    privateLinks,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (passwords > 0) {
            Text(
                pluralStringResource(
                    Res.plurals.export_passwords,
                    passwords,
                    passwords,
                ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = WARNING_GAP),
            )
        }
    }
}

@Composable
private fun OptionsBody(
    state: ExportUiState,
    onLayout: (ExportLayout) -> Unit,
    onYouTube: (Boolean) -> Unit,
    onPasswords: (Boolean) -> Unit,
) {
    val enabled = state.ready && !state.busy
    Column {
        RadioRow(
            label = stringResource(Res.string.export_layout_grouped),
            selected = state.layout == ExportLayout.GROUPED,
            enabled = enabled,
            onClick = { onLayout(ExportLayout.GROUPED) },
        )
        RadioRow(
            label = stringResource(Res.string.export_layout_flat),
            selected = state.layout == ExportLayout.FLAT,
            enabled = enabled,
            onClick = { onLayout(ExportLayout.FLAT) },
            note = stringResource(Res.string.export_layout_flat_note),
        )
        SwitchRow(
            label = stringResource(Res.string.export_include_youtube),
            checked = state.includeYouTube,
            enabled = enabled,
            onToggle = onYouTube,
            note = stringResource(Res.string.export_youtube_note),
        )
        SwitchRow(
            label = stringResource(Res.string.export_include_passwords),
            checked = state.includePasswords,
            enabled = enabled,
            onToggle = onPasswords,
        )
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    note: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, enabled = enabled, role = Role.RadioButton)
                .padding(vertical = ROW_GAP),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = RADIO_LABEL_GAP)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    note: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, onValueChange = onToggle, enabled = enabled, role = Role.Switch)
                .padding(vertical = ROW_GAP),
    ) {
        Column(Modifier.weight(1f).padding(end = SWITCH_LABEL_GAP)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private val DIALOG_PADDING = 24.dp
private val PROGRESS_GAP = 12.dp
private val BODY_GAP = 16.dp
private val ACTIONS_GAP = 24.dp
private val WARNING_GAP = 8.dp
private val ROW_GAP = 6.dp
private val RADIO_LABEL_GAP = 16.dp
private val SWITCH_LABEL_GAP = 16.dp
