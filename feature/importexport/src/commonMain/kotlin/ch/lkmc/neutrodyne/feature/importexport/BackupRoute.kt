// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.DateFormatter
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncSettingsKey
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.NavBackButton
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_continue
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.backup_android
import ch.lkmc.neutrodyne.core.ui.resources.backup_auto
import ch.lkmc.neutrodyne.core.ui.resources.backup_computer
import ch.lkmc.neutrodyne.core.ui.resources.backup_create
import ch.lkmc.neutrodyne.core.ui.resources.backup_discard
import ch.lkmc.neutrodyne.core.ui.resources.backup_discard_question
import ch.lkmc.neutrodyne.core.ui.resources.backup_export
import ch.lkmc.neutrodyne.core.ui.resources.backup_export_opml
import ch.lkmc.neutrodyne.core.ui.resources.backup_foreign
import ch.lkmc.neutrodyne.core.ui.resources.backup_last
import ch.lkmc.neutrodyne.core.ui.resources.backup_lock
import ch.lkmc.neutrodyne.core.ui.resources.backup_never
import ch.lkmc.neutrodyne.core.ui.resources.backup_password_note
import ch.lkmc.neutrodyne.core.ui.resources.backup_restore_file
import ch.lkmc.neutrodyne.core.ui.resources.backup_restore_section
import ch.lkmc.neutrodyne.core.ui.resources.backup_section
import ch.lkmc.neutrodyne.core.ui.resources.backup_snapshot_now
import ch.lkmc.neutrodyne.core.ui.resources.backup_sync
import ch.lkmc.neutrodyne.core.ui.resources.backup_title
import ch.lkmc.neutrodyne.core.ui.resources.backup_too_large_snapshot
import ch.lkmc.neutrodyne.core.ui.resources.backup_transport
import ch.lkmc.neutrodyne.core.ui.resources.backup_updated
import ch.lkmc.neutrodyne.core.ui.resources.backup_working
import ch.lkmc.neutrodyne.core.ui.resources.export_include_passwords
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BackupRoute(
    debug: Boolean = false,
    viewModel: BackupViewModel = metroViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val actions = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val now = LocalUiClock.current.now()
    var discard by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(viewModel) {
        viewModel.opened()
        onDispose { }
    }
    ShowUserMessages(messages, viewModel::acknowledgeMessage)
    val pick: suspend (ch.lkmc.neutrodyne.core.model.PreparedBackup) -> String? = {
        actions.saver.create(it.fileName, BACKUP_MIME)
    }
    Scaffold(topBar = {
        NdTopAppBar(title = stringResource(Res.string.backup_title), navigation = { NavBackButton() })
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(stringResource(Res.string.backup_export))
            NdTextButton(stringResource(Res.string.backup_export_opml), { navigator.push(ExportKey(null)) })
            Section(stringResource(Res.string.backup_section))
            BackupSwitch(
                stringResource(Res.string.export_include_passwords),
                state.includePasswords,
                !state.busy && state.warning == null,
                viewModel::setPasswords,
            )
            Text(stringResource(Res.string.backup_password_note), style = MaterialTheme.typography.bodySmall)
            NdButton(
                stringResource(Res.string.backup_create),
                { scope.launch { viewModel.create(pick) } },
                enabled =
                    !state.busy && state.warning == null,
            )
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(Res.string.backup_working))
            }
            Text(
                if (state.lastBackupAt ==
                    0L
                ) {
                    stringResource(Res.string.backup_never)
                } else {
                    stringResource(Res.string.backup_last, FeedDates.relative(state.lastBackupAt, now).asString())
                },
            )
            Section(stringResource(Res.string.backup_restore_section))
            NdTextButton(stringResource(Res.string.backup_restore_file), {
                scope.launch {
                    viewModel
                        .chooseRestore { actions.files.pickFile(listOf("*/*"), listOf("zip")) }
                        ?.let { navigator.push(ImportKey(it.toString())) }
                }
            }, enabled = !state.busy && state.warning == null)
            val snapshot = state.snapshot
            if (snapshot?.supported == false) {
                Text(stringResource(Res.string.backup_computer))
                NdTextButton(stringResource(Res.string.backup_sync), { navigator.push(SyncSettingsKey) })
            }
            if (snapshot?.supported == true) {
                Section(stringResource(Res.string.backup_android))
                BackupSwitch(
                    stringResource(Res.string.backup_auto),
                    snapshot.enabled,
                    !state.busy,
                    viewModel::setSnapshotEnabled,
                )
                Text(stringResource(Res.string.backup_lock))
                Text(stringResource(Res.string.backup_transport))
                snapshot.lastWrittenAt?.let { at ->
                    Text(
                        stringResource(
                            Res.string.backup_updated,
                            DateFormatter.date(at),
                            backupSize(snapshot.bytes ?: 0).asString(),
                        ),
                    )
                }
                if (snapshot.lastError != null) {
                    Text(
                        if (snapshot.lastError ==
                            "too_large"
                        ) {
                            stringResource(Res.string.backup_too_large_snapshot)
                        } else {
                            backupErrorText(ch.lkmc.neutrodyne.core.domain.BackupError.Io).asString()
                        },
                    )
                }
                if (snapshot.foreignPending) {
                    Text(stringResource(Res.string.backup_foreign))
                    NdTextButton(stringResource(Res.string.backup_restore_file), {
                        scope.launch { viewModel.restoreSnapshot()?.let { navigator.push(ImportKey(it.toString())) } }
                    }, enabled = !state.busy)
                    NdTextButton(stringResource(Res.string.backup_discard), { discard = true }, enabled = !state.busy)
                }
                if (debug) {
                    NdTextButton(
                        stringResource(Res.string.backup_snapshot_now),
                        viewModel::writeSnapshotNow,
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
    state.warning?.let { prepared ->
        NdDialog(
            onDismissRequest = viewModel::cancelWarning,
            title = stringResource(Res.string.backup_title),
            confirm =
                NdDialogAction(stringResource(Res.string.action_continue)) {
                    scope.launch { viewModel.confirmWarning(pick) }
                },
            dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onClick = viewModel::cancelWarning),
        ) { PrivateLinksWarning(prepared.privacy.privateLinks, prepared.privacy.passwords) }
    }
    state.acquisitionError?.let { error ->
        NdDialog(
            onDismissRequest = viewModel::dismissAcquisitionError,
            text = error.asString(),
            confirm = NdDialogAction(stringResource(Res.string.back), onClick = viewModel::dismissAcquisitionError),
        )
    }
    if (discard) {
        NdDialog(
            onDismissRequest = { discard = false },
            text = stringResource(Res.string.backup_discard_question),
            confirm =
                NdDialogAction(stringResource(Res.string.backup_discard)) {
                    discard = false
                    viewModel.discardSnapshot()
                },
            dismiss = NdDialogAction(stringResource(Res.string.action_cancel)) { discard = false },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun BackupSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

private const val BACKUP_MIME = "application/zip"
