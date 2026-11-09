// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_delete_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_delete_confirm
import ch.lkmc.neutrodyne.feature.sync.resources.sync_delete_data
import ch.lkmc.neutrodyne.feature.sync.resources.sync_delete_field
import ch.lkmc.neutrodyne.feature.sync.resources.sync_delete_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_unlink_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_unlink_confirm
import ch.lkmc.neutrodyne.feature.sync.resources.sync_unlink_device
import ch.lkmc.neutrodyne.feature.sync.resources.sync_unlink_title
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * The last two "Rows while linked" of Settings › Sync (08; 10 Unlink and Delete my data):
 * "Unlink this device" behind its confirmation, and "Delete my data on the server" — the row
 * in the error colour — behind the typed `DELETE` confirmation. The dialogs call
 * [SyncController.unlink] / [SyncController.deleteServerData] only on an explicit confirm;
 * Cancel, back and outside taps never reach the controller, and a confirmed op runs at most
 * once while it is in flight. A committed unlink or purge flips [SyncController.status]
 * itself (`NotConfigured`, the address kept), so the section just follows it; an
 * `Outcome.Failure` keeps the link and surfaces the typed problem with a Retry that re-sends
 * the already-confirmed op — `DELETE` is never asked for twice.
 *
 * Everything async is owned by [identity] (server, account, device), like the rest of the
 * linked section: the caller keys it to the identity, the Delete button stays disabled until
 * the field holds exactly `DELETE`, the identity is still current and no op is in flight, and
 * every op re-checks the identity before its service call and before touching UI state — a
 * stale click or a late completion can never reach or paint another account's screen.
 */
@Composable
internal fun ColumnScope.DisconnectActions(
    controller: SyncController,
    identity: SyncIdentity,
    host: String,
) {
    val scope = rememberCoroutineScope()
    val status by controller.status.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DisconnectDialog?>(null) }
    var failure by remember { mutableStateOf<DisconnectFailure?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun stillCurrent(): Boolean = controller.status.value.identity() == identity

    fun run(op: DisconnectOp) {
        // A click left over on a dying section must not even dispatch.
        if (busy || !stillCurrent()) return
        scope.launch {
            // Re-check at dispatch: the identity or the in-flight op can have changed since
            // the click.
            if (busy || !stillCurrent()) return@launch
            busy = true
            failure = null
            val outcome =
                when (op) {
                    DisconnectOp.Unlink -> controller.unlink()
                    DisconnectOp.Delete -> controller.deleteServerData(DELETE_CONFIRMATION)
                }
            if (stillCurrent()) {
                busy = false
                if (outcome is Outcome.Failure) {
                    failure = DisconnectFailure(op, outcome.error)
                }
            }
        }
    }

    NdTextButton(
        label = stringResource(Res.string.sync_unlink_device),
        onClick = {
            failure = null
            dialog = DisconnectDialog.Unlink
        },
    )
    NdTextButton(
        label = stringResource(Res.string.sync_delete_data),
        onClick = {
            failure = null
            dialog = DisconnectDialog.Delete
        },
        role = NdActionRole.DESTRUCTIVE,
    )

    failure?.let { current ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                problemText(current.problem, host),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            NdTextButton(
                label = stringResource(UiRes.string.action_retry),
                onClick = { run(current.op) },
                enabled = !busy,
            )
        }
    }

    when (dialog) {
        DisconnectDialog.Unlink -> {
            NdDialog(
                onDismissRequest = { dialog = null },
                title = stringResource(Res.string.sync_unlink_title),
                text = stringResource(Res.string.sync_unlink_body),
                confirm =
                    NdDialogAction(
                        label = stringResource(Res.string.sync_unlink_confirm),
                        role = NdActionRole.DESTRUCTIVE,
                        onClick = {
                            dialog = null
                            run(DisconnectOp.Unlink)
                        },
                    ),
                dismiss = NdDialogAction(stringResource(UiRes.string.action_cancel)) { dialog = null },
            )
        }

        DisconnectDialog.Delete -> {
            DeleteServerDataDialog(
                host = host,
                busy = busy || status.identity() != identity,
                onConfirm = {
                    dialog = null
                    run(DisconnectOp.Delete)
                },
                onDismiss = { dialog = null },
            )
        }

        null -> {}
    }
}

/**
 * "Delete my data on the server" (08 "Rows while linked"): the title names the host, the body
 * is the full destruction disclosure — records, account backups, the unlink of every device
 * and the 7-day nightly-backup retention — and the field takes the untranslated confirmation.
 * The Delete button enables only on exactly `DELETE`: never case-folded, never trimmed, never
 * a look-alike script. `AlertDialog` rather than `NdDialog` like the other input dialogs —
 * its confirm needs an `enabled` the shared action does not carry.
 */
@Composable
private fun DeleteServerDataDialog(
    host: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_delete_title, host)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.sync_delete_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(Res.string.sync_delete_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            NdTextButton(
                label = stringResource(Res.string.sync_delete_confirm),
                onClick = onConfirm,
                enabled = typed == DELETE_CONFIRMATION && !busy,
                role = NdActionRole.DESTRUCTIVE,
            )
        },
        dismissButton = {
            NdTextButton(label = stringResource(UiRes.string.action_cancel), onClick = onDismiss)
        },
    )
}

/** The exact, untranslated confirmation `deleteServerData` requires (08, 10). */
private const val DELETE_CONFIRMATION = "DELETE"

/** Which confirmed disconnect op a click or a retry starts. */
private enum class DisconnectOp {
    Unlink,
    Delete,
}

/** A failed op kept for its Retry action; [op] re-sends the same confirmed call. */
private data class DisconnectFailure(
    val op: DisconnectOp,
    val problem: SyncProblem,
)

private sealed interface DisconnectDialog {
    data object Unlink : DisconnectDialog

    data object Delete : DisconnectDialog
}
