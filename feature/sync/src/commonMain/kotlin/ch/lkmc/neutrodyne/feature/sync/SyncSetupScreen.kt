// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdOutlinedButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdProgress
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.model.PreparedBackup
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_continue
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_corrupt
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_expired
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_io
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_large
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_newer
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_not_backup
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_running
import ch.lkmc.neutrodyne.core.ui.resources.backup_err_unsupported
import ch.lkmc.neutrodyne.core.ui.resources.backup_title
import ch.lkmc.neutrodyne.core.ui.resources.export_err_destination
import ch.lkmc.neutrodyne.core.ui.resources.export_err_storage
import ch.lkmc.neutrodyne.core.ui.resources.export_passwords
import ch.lkmc.neutrodyne.core.ui.resources.export_private_links
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_continue
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_address_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_backup_saved
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_backup_working
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_cancel
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_checking
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_choice_device
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_choice_device_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_choice_merge
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_choice_merge_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_choice_server
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_choice_server_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_choice_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_code_lead
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_connect_anyway
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_disclosure_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_disclosure_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_encrypted
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_get_code
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_insecure_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_insecure_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_invite_label
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_linked_done
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_linking
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_local_network_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_local_network_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_merging
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_merging_downloading
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_merging_uploading
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_method_code
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_method_invite
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_method_password
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_not_encrypted
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_password_label
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_private_feeds
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_replace_device_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_replace_device_confirm
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_replace_device_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_replace_server_backup
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_replace_server_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_replace_server_confirm
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_replace_server_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_try_again
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_upload_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_upload_confirm
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_upload_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_username_label
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.LinkFlow
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.LinkPhase
import ch.lkmc.neutrodyne.sync.api.LinkState
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncDisclosure
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * Linking a device (08 Linking a device): one screen whose body follows [LinkFlow.state]; the flow
 * itself is [SyncController]'s. Leaving before `Authorising` cancels it; leaving during `Merging`
 * only navigates away, because the merge continues.
 */
@Composable
internal fun SyncSetupScreen(
    controller: SyncController,
    serverUrl: String,
    backup: BackupRepository,
) {
    val link = controller.link ?: return
    val state by link.state.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(serverUrl) }
    var method by remember { mutableStateOf(LinkMethod.CODE) }
    var inviteCode by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf(FirstLinkChoice.MERGE) }
    val awaiting = state as? LinkState.AwaitingInput
    val host = hostOf(awaiting?.server?.serverUrl ?: address)

    // The destructive choices wait for their confirmation; only the explicit final action calls
    // `chooseFirstLink`. `USE_DEVICE` still lacks the service's staged effect counts, so its
    // dialog warns without numbers (the exact two-stage gate arrives with the service).
    var pendingChoice by remember { mutableStateOf<FirstLinkChoice?>(null) }
    var backupPhase by remember { mutableStateOf<BackupPhase>(BackupPhase.Idle) }
    var backupError by remember { mutableStateOf<BackupError?>(null) }
    var backupJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val actions = LocalPlatformActions.current

    fun dismissConfirmation() {
        backupJob?.cancel()
        backupJob = null
        pendingChoice = null
        backupPhase = BackupPhase.Idle
        backupError = null
    }

    fun confirmChoice(confirmed: FirstLinkChoice) {
        pendingChoice = null
        backupPhase = BackupPhase.Idle
        backupError = null
        controller.chooseFirstLink(confirmed)
    }

    fun startBackup(prepared: PreparedBackup? = null) {
        backupJob?.cancel()
        backupPhase = BackupPhase.Working
        backupError = null
        backupJob =
            scope.launch {
                try {
                    val receipt =
                        prepared
                            ?: when (val outcome = backup.prepareBackup(includePasswords = false)) {
                                is Outcome.Failure -> {
                                    backupError = outcome.error
                                    backupPhase = BackupPhase.Idle
                                    return@launch
                                }

                                is Outcome.Success -> {
                                    outcome.value
                                }
                            }
                    if (prepared == null &&
                        (receipt.privacy.privateLinks > 0 || receipt.privacy.passwords > 0)
                    ) {
                        backupPhase = BackupPhase.Warning(receipt)
                        return@launch
                    }
                    val uri = actions.saver.create(receipt.fileName, BACKUP_MIME)
                    if (uri == null) {
                        // A cancelled save leaves the prepared artifact to the cleaner; nothing merges.
                        backupPhase = BackupPhase.Idle
                        return@launch
                    }
                    when (val outcome = backup.savePreparedBackup(receipt, uri)) {
                        is Outcome.Failure -> {
                            backupError = outcome.error
                            backupPhase = BackupPhase.Idle
                        }

                        is Outcome.Success -> {
                            backupPhase = BackupPhase.Saved
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    backupError = BackupError.Io
                    backupPhase = BackupPhase.Idle
                }
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            backupJob?.cancel()
            if (link.state.value !is LinkState.Merging && link.state.value !is LinkState.Authorising) {
                link.cancel()
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.sync_setup_title), style = MaterialTheme.typography.titleLarge)
        when (state) {
            is LinkState.Address -> {
                AddressStep(
                    address = address,
                    onAddress = { address = it },
                    onContinue = { controller.discover(address) },
                )
            }

            is LinkState.Discovering -> {
                Text(
                    stringResource(Res.string.sync_setup_checking, hostOf(address)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is LinkState.NeedsLocalNetworkPermission -> {
                // Android 17 asks the OS and only its result answers the flow; the desktop and
                // older Android have no prompt, so the tap itself is the answer there (10
                // Local-network gate).
                val permissionPrompt = rememberLocalNetworkPermissionPrompt()
                LocalNetworkDialog(
                    host = (state as LinkState.NeedsLocalNetworkPermission).host,
                    onAllow = {
                        if (permissionPrompt == null) {
                            controller.grantLocalNetwork(true)
                        } else {
                            permissionPrompt.request { granted ->
                                controller.grantLocalNetwork(granted)
                            }
                        }
                    },
                    onDismiss = { controller.grantLocalNetwork(false) },
                )
            }

            is LinkState.InsecureConnection -> {
                InsecureDialog(
                    host = (state as LinkState.InsecureConnection).host,
                    onConfirm = controller::confirmInsecure,
                    onDismiss = link::cancel,
                )
            }

            is LinkState.AwaitingInput -> {
                AwaitingInputStep(
                    method = method,
                    onMethod = { method = it },
                    inviteCode = inviteCode,
                    onInviteCode = { inviteCode = it },
                    username = username,
                    onUsername = { username = it },
                    password = password,
                    onPassword = { password = it },
                    disclosure = (state as LinkState.AwaitingInput).disclosure,
                    onGetCode = { controller.startLink(serverUrl = address, method = LinkMethod.CODE) },
                    onSubmitInvite = { controller.submitInvite(inviteCode) },
                    onSubmitPassword = {
                        controller.submitPassword(username, password.toCharArray())
                        password = ""
                    },
                )
            }

            is LinkState.ShowCode -> {
                ShowCodeStep(
                    state = state as LinkState.ShowCode,
                    onCancel = link::cancel,
                )
            }

            is LinkState.Authorising -> {
                Text(
                    stringResource(Res.string.sync_setup_linking),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is LinkState.ConfirmUpload -> {
                ConfirmUploadStep(
                    accountName = (state as LinkState.ConfirmUpload).accountName,
                    host = host,
                    onConfirm = { controller.chooseFirstLink(FirstLinkChoice.MERGE) },
                    onCancel = link::cancel,
                )
            }

            is LinkState.ChooseFirstLink -> {
                ChooseFirstLinkStep(
                    state = state as LinkState.ChooseFirstLink,
                    host = host,
                    choice = choice,
                    onChoice = { choice = it },
                    onContinue = {
                        when (choice) {
                            FirstLinkChoice.MERGE -> controller.chooseFirstLink(FirstLinkChoice.MERGE)
                            FirstLinkChoice.USE_SERVER -> pendingChoice = FirstLinkChoice.USE_SERVER
                            FirstLinkChoice.USE_DEVICE -> pendingChoice = FirstLinkChoice.USE_DEVICE
                        }
                    },
                )
            }

            is LinkState.Merging -> {
                MergingStep(state = state as LinkState.Merging)
            }

            is LinkState.Linked -> {
                LinkedStep(
                    accountName = (state as LinkState.Linked).accountName,
                    deviceName = (state as LinkState.Linked).deviceName,
                )
            }

            is LinkState.Failed -> {
                FailedStep(
                    error = (state as LinkState.Failed).error,
                    host = host,
                    onTryAgain = { controller.discover(address) },
                )
            }
        }
    }

    pendingChoice?.let { pending ->
        when (pending) {
            FirstLinkChoice.USE_SERVER -> {
                ReplaceServerDialog(
                    phase = backupPhase,
                    error = backupError,
                    onBackup = { startBackup() },
                    onWarningConfirm = { startBackup(it) },
                    onWarningDismiss = {
                        backupPhase = BackupPhase.Idle
                        backupError = null
                    },
                    onReplace = { confirmChoice(FirstLinkChoice.USE_SERVER) },
                    onCancel = ::dismissConfirmation,
                )
            }

            FirstLinkChoice.USE_DEVICE -> {
                ReplaceEverywhereDialog(
                    onReplace = { confirmChoice(FirstLinkChoice.USE_DEVICE) },
                    onCancel = ::dismissConfirmation,
                )
            }

            FirstLinkChoice.MERGE -> {
                Unit
            }
        }
    }
}

@Composable
private fun AddressStep(
    address: String,
    onAddress: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = onAddress,
            label = { Text(stringResource(Res.string.sync_setup_title)) },
            placeholder = { Text(stringResource(Res.string.sync_setup_address_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onContinue, enabled = address.isNotBlank()) {
            Text(stringResource(Res.string.sync_continue))
        }
    }
}

@Composable
private fun LocalNetworkDialog(
    host: String,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_setup_local_network_title)) },
        text = { Text(stringResource(Res.string.sync_setup_local_network_body, host)) },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(Res.string.sync_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_setup_cancel)) }
        },
    )
}

@Composable
private fun InsecureDialog(
    host: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_setup_insecure_title)) },
        text = { Text(stringResource(Res.string.sync_setup_insecure_body, host)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.sync_setup_connect_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_setup_cancel)) }
        },
    )
}

@Composable
private fun AwaitingInputStep(
    method: LinkMethod,
    onMethod: (LinkMethod) -> Unit,
    inviteCode: String,
    onInviteCode: (String) -> Unit,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    disclosure: SyncDisclosure,
    onGetCode: () -> Unit,
    onSubmitInvite: () -> Unit,
    onSubmitPassword: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DisclosureCard(disclosure)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MethodButton(LinkMethod.CODE, method, stringResource(Res.string.sync_setup_method_code), onMethod)
            MethodButton(LinkMethod.INVITE, method, stringResource(Res.string.sync_setup_method_invite), onMethod)
            MethodButton(LinkMethod.PASSWORD, method, stringResource(Res.string.sync_setup_method_password), onMethod)
        }
        when (method) {
            LinkMethod.CODE -> {
                Button(onClick = onGetCode, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.sync_setup_get_code))
                }
            }

            LinkMethod.INVITE -> {
                InviteInput(
                    code = inviteCode,
                    onCode = onInviteCode,
                    onSubmit = onSubmitInvite,
                )
            }

            LinkMethod.PASSWORD -> {
                PasswordInput(
                    username = username,
                    onUsername = onUsername,
                    password = password,
                    onPassword = onPassword,
                    onSubmit = onSubmitPassword,
                )
            }
        }
    }
}

@Composable
private fun DisclosureCard(disclosure: SyncDisclosure) {
    val privateFeeds =
        if (disclosure.privateFeedLinks > 0) {
            stringResource(Res.string.sync_setup_private_feeds, disclosure.privateFeedLinks)
        } else {
            ""
        }
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(Res.string.sync_setup_disclosure_title, disclosure.host),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(Res.string.sync_setup_disclosure_body, privateFeeds),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    if (disclosure.encrypted) {
                        Res.string.sync_setup_encrypted
                    } else {
                        Res.string.sync_setup_not_encrypted
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InviteInput(
    code: String,
    onCode: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = onCode,
            label = { Text(stringResource(Res.string.sync_setup_invite_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSubmit, enabled = code.isNotBlank()) {
            Text(stringResource(Res.string.sync_continue))
        }
    }
}

@Composable
private fun PasswordInput(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsername,
            label = { Text(stringResource(Res.string.sync_setup_username_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text(stringResource(Res.string.sync_setup_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSubmit,
            enabled = username.isNotBlank() && password.isNotEmpty(),
        ) {
            Text(stringResource(Res.string.sync_continue))
        }
    }
}

@Composable
private fun MethodButton(
    target: LinkMethod,
    current: LinkMethod,
    label: String,
    onMethod: (LinkMethod) -> Unit,
) {
    if (target == current) {
        Button(onClick = { onMethod(target) }) { Text(label) }
    } else {
        OutlinedButton(onClick = { onMethod(target) }) { Text(label) }
    }
}

@Composable
private fun ShowCodeStep(
    state: LinkState.ShowCode,
    onCancel: () -> Unit,
) {
    // The code is read aloud spelled out, so screen readers do not stumble over letter pairs.
    val spoken = state.userCode.chunked(1).joinToString(" ")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            state.userCode.chunked(4).joinToString("-"),
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace,
            modifier =
                Modifier.semantics {
                    contentDescription = spoken
                },
        )
        Text(
            stringResource(Res.string.sync_setup_code_lead, state.verificationUri),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onCancel) { Text(stringResource(Res.string.sync_setup_cancel)) }
    }
}

@Composable
private fun ConfirmUploadStep(
    accountName: String,
    host: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.sync_setup_upload_title, accountName),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(Res.string.sync_setup_upload_body, host), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onConfirm) { Text(stringResource(Res.string.sync_setup_upload_confirm)) }
        OutlinedButton(onClick = onCancel) { Text(stringResource(Res.string.sync_setup_cancel)) }
    }
}

@Composable
private fun ChooseFirstLinkStep(
    state: LinkState.ChooseFirstLink,
    host: String,
    choice: FirstLinkChoice,
    onChoice: (FirstLinkChoice) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.sync_setup_choice_title, host),
            style = MaterialTheme.typography.titleMedium,
        )
        ChoiceCard(
            selected = choice == FirstLinkChoice.MERGE,
            title = stringResource(Res.string.sync_setup_choice_merge),
            body =
                stringResource(
                    Res.string.sync_setup_choice_merge_body,
                    state.localPodcasts,
                    state.localGroups,
                    "",
                    state.serverPodcasts,
                    state.serverGroups,
                ),
            onSelect = { onChoice(FirstLinkChoice.MERGE) },
        )
        ChoiceCard(
            selected = choice == FirstLinkChoice.USE_SERVER,
            title = stringResource(Res.string.sync_setup_choice_server),
            body = stringResource(Res.string.sync_setup_choice_server_body),
            onSelect = { onChoice(FirstLinkChoice.USE_SERVER) },
        )
        ChoiceCard(
            selected = choice == FirstLinkChoice.USE_DEVICE,
            title = stringResource(Res.string.sync_setup_choice_device),
            body = stringResource(Res.string.sync_setup_choice_device_body),
            onSelect = { onChoice(FirstLinkChoice.USE_DEVICE) },
        )
        Button(onClick = onContinue) { Text(stringResource(Res.string.sync_continue)) }
    }
}

/** The local state of the USE_SERVER confirmation's backup step. */
private sealed interface BackupPhase {
    data object Idle : BackupPhase

    data object Working : BackupPhase

    /** The prepared artifact carries private links or passwords: 05's R1.9 warning comes first. */
    data class Warning(
        val prepared: PreparedBackup,
    ) : BackupPhase

    /** The backup is saved; the user still confirms Replace explicitly. */
    data object Saved : BackupPhase
}

private const val BACKUP_MIME = "application/zip"

/**
 * 08 Linking a device, "Use the server's library on this device": the confirmation before the
 * replace. "Save a backup first" runs 05's manual backup flow (prepare, R1.9 warning, save) and
 * stops there — only "Replace" calls the destructive choice. Cancel just closes the dialog.
 */
@Composable
private fun ReplaceServerDialog(
    phase: BackupPhase,
    error: BackupError?,
    onBackup: () -> Unit,
    onWarningConfirm: (PreparedBackup) -> Unit,
    onWarningDismiss: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    NdDialog(
        onDismissRequest = onCancel,
        title = stringResource(Res.string.sync_setup_replace_server_title),
        confirm =
            if (phase == BackupPhase.Working) {
                null
            } else {
                NdDialogAction(
                    stringResource(Res.string.sync_setup_replace_server_confirm),
                    NdActionRole.DESTRUCTIVE,
                    onClick = onReplace,
                )
            },
        dismiss = NdDialogAction(stringResource(Res.string.sync_setup_cancel), onClick = onCancel),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.sync_setup_replace_server_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (phase == BackupPhase.Working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(Res.string.sync_setup_backup_working),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (phase == BackupPhase.Saved) {
                Text(
                    stringResource(Res.string.sync_setup_backup_saved),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.let {
                Text(
                    backupErrorText(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (phase != BackupPhase.Working) {
                NdOutlinedButton(
                    label = stringResource(Res.string.sync_setup_replace_server_backup),
                    onClick = onBackup,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (phase is BackupPhase.Warning) {
        NdDialog(
            onDismissRequest = onWarningDismiss,
            title = stringResource(UiRes.string.backup_title),
            confirm =
                NdDialogAction(stringResource(UiRes.string.action_continue)) {
                    onWarningConfirm(phase.prepared)
                },
            dismiss =
                NdDialogAction(stringResource(UiRes.string.action_cancel), onClick = onWarningDismiss),
        ) {
            PrivateLinksWarning(phase.prepared.privacy.privateLinks, phase.prepared.privacy.passwords)
        }
    }
}

/**
 * 08 Linking a device, "Use this device's library everywhere": the confirmation before the
 * replace. The exact effect counts need the service's staged pull, which this branch's link
 * service does not expose yet, so the warning names the effect without numbers; the service's
 * later `ConfirmReplaceEverywhere` step can replace this local gate.
 */
@Composable
private fun ReplaceEverywhereDialog(
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    NdDialog(
        onDismissRequest = onCancel,
        title = stringResource(Res.string.sync_setup_replace_device_title),
        text = stringResource(Res.string.sync_setup_replace_device_body),
        confirm =
            NdDialogAction(
                stringResource(Res.string.sync_setup_replace_device_confirm),
                NdActionRole.DESTRUCTIVE,
                onClick = onReplace,
            ),
        dismiss = NdDialogAction(stringResource(Res.string.sync_setup_cancel), onClick = onCancel),
    )
}

@Composable
private fun PrivateLinksWarning(
    privateLinks: Int,
    passwords: Int,
) {
    Column {
        if (privateLinks > 0) {
            Text(
                pluralStringResource(UiRes.plurals.export_private_links, privateLinks, privateLinks),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (passwords > 0) {
            Text(
                pluralStringResource(UiRes.plurals.export_passwords, passwords, passwords),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** `BackupError` → core/ui's existing backup wording (05 Error handling and failure modes). */
@Composable
private fun backupErrorText(error: BackupError): String =
    when (error) {
        BackupError.NotABackup -> stringResource(UiRes.string.backup_err_not_backup)
        is BackupError.NewerFormat -> stringResource(UiRes.string.backup_err_newer)
        is BackupError.Corrupt -> stringResource(UiRes.string.backup_err_corrupt, error.entry)
        BackupError.TooLarge -> stringResource(UiRes.string.backup_err_large)
        BackupError.Io -> stringResource(UiRes.string.backup_err_io)
        BackupError.Storage -> stringResource(UiRes.string.export_err_storage)
        BackupError.RestoreRunning -> stringResource(UiRes.string.backup_err_running)
        BackupError.DestinationUnavailable -> stringResource(UiRes.string.export_err_destination)
        BackupError.PreparedExpired -> stringResource(UiRes.string.backup_err_expired)
        BackupError.Unsupported -> stringResource(UiRes.string.backup_err_unsupported)
    }

@Composable
private fun ChoiceCard(
    selected: Boolean,
    title: String,
    body: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MergingStep(state: LinkState.Merging) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (state.phase == LinkPhase.UPLOADING) {
                stringResource(Res.string.sync_setup_merging_uploading)
            } else {
                stringResource(Res.string.sync_setup_merging_downloading)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.total > 0) {
            NdProgress.Linear(
                progress = state.done.toFloat() / state.total.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(Res.string.sync_setup_merging, state.done, state.total),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LinkedStep(
    accountName: String,
    deviceName: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.sync_setup_linked_done, accountName, deviceName),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FailedStep(
    error: SyncProblem,
    host: String,
    onTryAgain: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(problemText(error, host), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onTryAgain) { Text(stringResource(Res.string.sync_setup_try_again)) }
    }
}
