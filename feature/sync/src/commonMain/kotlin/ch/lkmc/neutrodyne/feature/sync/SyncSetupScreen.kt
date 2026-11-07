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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdProgress
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_continue
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_app_too_old
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_certificate
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_clock_skew
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_insecure_transport
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_invalid_code
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_invite_invalid
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_link_declined
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_local_network
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_merge_failed
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_not_a_server
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_offline
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_quota
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_rate_limited
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_revoked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_server
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_server_too_old
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_unreachable
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_wrong_password
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_address_hint
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
import org.jetbrains.compose.resources.stringResource

/**
 * Linking a device (08 Linking a device): one screen whose body follows [LinkFlow.state]; the flow
 * itself is [SyncController]'s. Leaving before `Authorising` cancels it; leaving during `Merging`
 * only navigates away, because the merge continues.
 */
@Composable
internal fun SyncSetupScreen(
    controller: SyncController,
    serverUrl: String,
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

    DisposableEffect(Unit) {
        onDispose {
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
                    onContinue = { controller.chooseFirstLink(choice) },
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

/** `SyncProblem` → the wording of 08's "Sync status and problem texts" and link-flow failures. */
@Composable
private fun problemText(
    error: SyncProblem,
    host: String,
): String =
    when (error) {
        SyncProblem.Offline -> {
            stringResource(Res.string.sync_error_offline)
        }

        is SyncProblem.Unreachable -> {
            stringResource(Res.string.sync_error_unreachable, host)
        }

        SyncProblem.CertificateRejected -> {
            stringResource(Res.string.sync_error_certificate, host)
        }

        SyncProblem.Revoked -> {
            stringResource(Res.string.sync_error_revoked, host)
        }

        SyncProblem.AppTooOld -> {
            stringResource(Res.string.sync_error_app_too_old, host)
        }

        SyncProblem.ServerTooOld -> {
            stringResource(Res.string.sync_error_server_too_old, host)
        }

        SyncProblem.ClockSkew -> {
            stringResource(Res.string.sync_error_clock_skew)
        }

        SyncProblem.QuotaReached -> {
            stringResource(Res.string.sync_error_quota, host)
        }

        SyncProblem.LocalNetworkPermissionDenied -> {
            stringResource(Res.string.sync_error_local_network, host)
        }

        SyncProblem.InsecureTransport -> {
            stringResource(Res.string.sync_error_insecure_transport)
        }

        SyncProblem.WrongPassword -> {
            stringResource(Res.string.sync_error_wrong_password)
        }

        SyncProblem.InvalidCode -> {
            stringResource(Res.string.sync_error_invalid_code)
        }

        SyncProblem.LinkDeclined -> {
            stringResource(Res.string.sync_error_link_declined)
        }

        SyncProblem.InviteInvalid -> {
            stringResource(Res.string.sync_error_invite_invalid)
        }

        SyncProblem.MergeFailed -> {
            stringResource(Res.string.sync_error_merge_failed)
        }

        is SyncProblem.Server -> {
            if (error.status == 404) {
                stringResource(Res.string.sync_error_not_a_server, host)
            } else {
                stringResource(Res.string.sync_error_server, host, error.status)
            }
        }
    }

private fun hostOf(url: String): String = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
