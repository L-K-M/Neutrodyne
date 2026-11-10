// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncApproveKey
import ch.lkmc.neutrodyne.core.navigation.SyncDevicesKey
import ch.lkmc.neutrodyne.core.navigation.SyncDiagnosticsKey
import ch.lkmc.neutrodyne.core.navigation.SyncSetupKey
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_activity_idle
import ch.lkmc.neutrodyne.feature.sync.resources.sync_activity_live
import ch.lkmc.neutrodyne.feature.sync.resources.sync_activity_syncing
import ch.lkmc.neutrodyne.feature.sync.resources.sync_collapsed
import ch.lkmc.neutrodyne.feature.sync.resources.sync_continue
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_name_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_name_label
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_renamed
import ch.lkmc.neutrodyne.feature.sync.resources.sync_devices
import ch.lkmc.neutrodyne.feature.sync.resources.sync_devices_linked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diagnostics_row
import ch.lkmc.neutrodyne.feature.sync.resources.sync_expanded
import ch.lkmc.neutrodyne.feature.sync.resources.sync_finish_link
import ch.lkmc.neutrodyne.feature.sync.resources.sync_header_this_device
import ch.lkmc.neutrodyne.feature.sync.resources.sync_header_what_syncs
import ch.lkmc.neutrodyne.feature.sync.resources.sync_intro
import ch.lkmc.neutrodyne.feature.sync.resources.sync_link_again
import ch.lkmc.neutrodyne.feature.sync.resources.sync_link_another
import ch.lkmc.neutrodyne.feature.sync.resources.sync_link_another_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_linked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_name_row
import ch.lkmc.neutrodyne.feature.sync.resources.sync_now
import ch.lkmc.neutrodyne.feature.sync.resources.sync_playback_settings
import ch.lkmc.neutrodyne.feature.sync.resources.sync_playback_settings_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_pref_save_failed
import ch.lkmc.neutrodyne.feature.sync.resources.sync_reconnect
import ch.lkmc.neutrodyne.feature.sync.resources.sync_reconnect_action
import ch.lkmc.neutrodyne.feature.sync.resources.sync_rename_this_device
import ch.lkmc.neutrodyne.feature.sync.resources.sync_revoked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_run_server
import ch.lkmc.neutrodyne.feature.sync.resources.sync_server_address
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_address_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords_confirm
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_what_syncs
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * Settings › Sync (08 Settings › Sync). The body follows [SyncController.status]; while
 * `NotConfigured` the only sync UI anywhere is this screen and the Settings row (R7.1). Every
 * entry affordance pushes `SyncSetupKey`: with the typed address while unconfigured, with the
 * known `serverUrl` for `Reconnect`, `LinkUnfinished` (which resumes whatever step the existing
 * `LinkFlow` holds — it is never cancelled or restarted here) and `Revoked`. `Linked` stays
 * read-only for linking: a replacement link is the separate confirmed unlink flow.
 */
@Composable
fun SyncSettingsScreen(
    controller: SyncController,
    address: SyncServerAddress,
    runServerUrl: String,
    modifier: Modifier = Modifier,
) {
    val status by controller.status.collectAsStateWithLifecycle()
    val syncing by controller.syncing.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.sync_title), style = MaterialTheme.typography.titleLarge)
        run {
            when (val current = status) {
                is SyncStatus.NotConfigured -> {
                    NotConfiguredBody(
                        address = address,
                        runServerUrl = runServerUrl,
                        onContinue = { navigator.push(SyncSetupKey(it)) },
                    )
                }

                is SyncStatus.Linked -> {
                    Text(
                        stringResource(Res.string.sync_linked, current.serverUrl),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            when (current.activity) {
                                SyncActivity.SYNCING -> Res.string.sync_activity_syncing
                                SyncActivity.LIVE -> Res.string.sync_activity_live
                                SyncActivity.IDLE -> Res.string.sync_activity_idle
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = controller::syncNow, enabled = !syncing) {
                        Text(stringResource(Res.string.sync_now))
                    }
                    val identity = current.identity()
                    // The linked rows are keyed to the (server, account, device) identity: an
                    // `activity`/`lastSyncAtMs` update recomposes them untouched, while an
                    // unlink or relink drops the whole section — typed input, open dialogs and
                    // late completions can never paint a different identity's screen.
                    if (identity != null) {
                        key(identity) {
                            LinkedSection(
                                controller = controller,
                                address = address,
                                identity = identity,
                                host = hostOf(current.serverUrl),
                                onDevices = { navigator.push(SyncDevicesKey) },
                                onApprove = { navigator.push(SyncApproveKey()) },
                                onDiagnostics = { navigator.push(SyncDiagnosticsKey) },
                            )
                        }
                    }
                }

                is SyncStatus.Reconnect -> {
                    Text(stringResource(Res.string.sync_reconnect, current.serverUrl))
                    Button(onClick = { navigator.push(SyncSetupKey(current.serverUrl)) }) {
                        Text(stringResource(Res.string.sync_reconnect_action))
                    }
                }

                is SyncStatus.LinkUnfinished -> {
                    Text(stringResource(Res.string.sync_finish_link, current.serverUrl))
                    Button(onClick = { navigator.push(SyncSetupKey(current.serverUrl)) }) {
                        Text(stringResource(Res.string.sync_continue))
                    }
                }

                is SyncStatus.Revoked -> {
                    Text(stringResource(Res.string.sync_revoked, current.serverUrl))
                    Button(onClick = { navigator.push(SyncSetupKey(current.serverUrl)) }) {
                        Text(stringResource(Res.string.sync_link_again))
                    }
                }
            }
        }
    }
}

/**
 * The unconfigured body (08 Settings › Sync): the address field prefilled from the kept
 * `sync.server_url` (an unlink leaves it behind for exactly this re-entry), "Continue" into
 * `SyncSetupKey`, and the README link for running a server.
 */
@Composable
private fun ColumnScope.NotConfiguredBody(
    address: SyncServerAddress,
    runServerUrl: String,
    onContinue: (String) -> Unit,
) {
    var serverAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(address) {
        if (serverAddress == null) {
            serverAddress = address.serverUrl().orEmpty()
        }
    }
    Text(stringResource(Res.string.sync_intro), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = serverAddress.orEmpty(),
        onValueChange = { serverAddress = it },
        label = { Text(stringResource(Res.string.sync_server_address)) },
        placeholder = { Text(stringResource(Res.string.sync_setup_address_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onContinue(serverAddress.orEmpty().trim()) },
        enabled = !serverAddress.isNullOrBlank(),
    ) {
        Text(stringResource(Res.string.sync_continue))
    }
    val actions = LocalPlatformActions.current
    NdTextButton(
        label = stringResource(Res.string.sync_run_server),
        onClick = { actions.urls.open(runServerUrl) },
    )
}

/**
 * The rows shown while linked (08 "Rows while linked"), minus the pieces later slices own (the
 * server disclosure and Diagnostics). This device offers its name as other
 * devices see it — resolved from `SyncController.devices()`, with the local `sync.device_name`
 * as the provisional value — renamed through `renameDevice` (1–40 characters, trimmed). The two
 * device-local switches show only their persisted values: toggles call the setters, a failed
 * write keeps the saved state and shows an actionable error, and nothing writes on load. "Share
 * feed passwords" asks the specified confirmation before it turns on; turning off needs none.
 *
 * Everything async is owned by [identity] (server, account, device): the keyed caller drops the
 * whole section on an identity change, and each completion additionally checks
 * `controller.status.identity()` before writing — a stale save, dialog or device list can never
 * reach a different account's screen.
 */
@Composable
private fun ColumnScope.LinkedSection(
    controller: SyncController,
    address: SyncServerAddress,
    identity: SyncIdentity,
    host: String,
    onDevices: () -> Unit,
    onApprove: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current
    // Serializes the UI's own write/read-back pairs so an older completion can't win over a
    // newer one; the controller serializes the durable commits themselves.
    val writeMutex = remember { Mutex() }

    var count by remember { mutableStateOf<Int?>(null) }
    var ownName by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var playback by remember { mutableStateOf(true) }
    var share by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<LinkedDialog?>(null) }
    var failure by remember { mutableStateOf<LinkedFailure?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun stillCurrent(): Boolean = controller.status.value.identity() == identity

    suspend fun reloadDevices() {
        val result = controller.devices()
        // The count and name rows keep their last values when a refresh fails.
        if (result is Outcome.Success && stillCurrent()) {
            count = result.value.size
            result.value.firstOrNull { it.id == identity.deviceId }?.let { ownName = it.name }
        }
    }

    fun rename(
        newName: String,
        doneMessage: String,
    ) {
        scope.launch {
            failure = null
            busy = true
            when (val outcome = controller.renameDevice(identity.deviceId, newName)) {
                is Outcome.Success -> {
                    if (stillCurrent()) {
                        busy = false
                        ownName = newName
                        reloadDevices()
                        snackbar.showSnackbar(doneMessage)
                    }
                }

                is Outcome.Failure -> {
                    if (stillCurrent()) {
                        busy = false
                        failure = RenameFailure(newName, outcome.error)
                    }
                }
            }
        }
    }

    fun writeSwitch(
        kind: SwitchKind,
        target: Boolean,
    ) {
        scope.launch {
            failure = null
            busy = true
            writeMutex.withLock {
                val outcome =
                    when (kind) {
                        SwitchKind.PLAYBACK -> controller.setSyncPlaybackSettings(target)
                        SwitchKind.PASSWORDS -> controller.setShareFeedPasswords(target)
                    }
                if (stillCurrent()) {
                    // The persisted value drives the control: a failed write left it unchanged.
                    when (kind) {
                        SwitchKind.PLAYBACK -> playback = address.syncPlaybackSettings()
                        SwitchKind.PASSWORDS -> share = address.shareFeedPasswords()
                    }
                    busy = false
                    if (outcome is Outcome.Failure) {
                        failure = SwitchFailure(kind, target)
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // Reads only: the switches and the name row start from the stored values, and the
        // server list then names this device as other devices see it.
        if (ownName == null) {
            ownName = address.deviceName()
        }
        playback = address.syncPlaybackSettings()
        share = address.shareFeedPasswords()
        loaded = true
        reloadDevices()
    }

    Text(stringResource(Res.string.sync_header_this_device), style = MaterialTheme.typography.titleSmall)
    ListItem(
        headlineContent = { Text(stringResource(Res.string.sync_name_row)) },
        supportingContent =
            ownName?.takeIf { it.isNotBlank() }?.let { name ->
                { Text(name) }
            },
        modifier = Modifier.clickable { dialog = LinkedDialog.RenameSelf },
    )
    ListItem(
        headlineContent = { Text(stringResource(Res.string.sync_link_another)) },
        supportingContent = { Text(stringResource(Res.string.sync_link_another_hint)) },
        modifier = Modifier.clickable(onClick = onApprove),
    )
    ListItem(
        headlineContent = { Text(stringResource(Res.string.sync_devices)) },
        supportingContent = count?.let { n -> { Text(pluralStringResource(Res.plurals.sync_devices_linked, n, n)) } },
        modifier = Modifier.clickable(onClick = onDevices),
    )
    WhatSyncsRow()
    if (loaded) {
        SyncSwitchRow(
            title = stringResource(Res.string.sync_playback_settings),
            summary = stringResource(Res.string.sync_playback_settings_hint),
            checked = playback,
            onCheckedChange = { writeSwitch(SwitchKind.PLAYBACK, it) },
        )
        SyncSwitchRow(
            title = stringResource(Res.string.sync_share_passwords),
            summary = stringResource(Res.string.sync_share_passwords_hint),
            checked = share,
            onCheckedChange = { target ->
                if (target) {
                    dialog = LinkedDialog.ConfirmShare
                } else {
                    writeSwitch(SwitchKind.PASSWORDS, false)
                }
            },
        )
    }
    failure?.let { current ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                when (current) {
                    is SwitchFailure -> stringResource(Res.string.sync_pref_save_failed)
                    is RenameFailure -> problemText(current.problem, host)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            when (current) {
                is SwitchFailure -> {
                    NdTextButton(
                        label = stringResource(UiRes.string.action_retry),
                        onClick = { writeSwitch(current.kind, current.target) },
                        enabled = !busy,
                    )
                }

                is RenameFailure -> {
                    val renamedMessage = stringResource(Res.string.sync_device_renamed, current.name)
                    NdTextButton(
                        label = stringResource(UiRes.string.action_retry),
                        onClick = { rename(current.name, renamedMessage) },
                        enabled = !busy,
                    )
                }
            }
        }
    }

    DisconnectActions(controller, identity, host)

    ListItem(
        headlineContent = { Text(stringResource(Res.string.sync_diagnostics_row)) },
        modifier = Modifier.clickable(onClick = onDiagnostics),
    )

    when (dialog) {
        LinkedDialog.RenameSelf -> {
            OwnNameDialog(
                currentName = ownName.orEmpty(),
                onRename = { trimmed, doneMessage ->
                    dialog = null
                    rename(trimmed, doneMessage)
                },
                onDismiss = { dialog = null },
            )
        }

        LinkedDialog.ConfirmShare -> {
            NdDialog(
                onDismissRequest = { dialog = null },
                title = stringResource(Res.string.sync_share_passwords_title),
                text = stringResource(Res.string.sync_share_passwords_body, host),
                confirm =
                    NdDialogAction(
                        label = stringResource(Res.string.sync_share_passwords_confirm),
                        onClick = {
                            dialog = null
                            writeSwitch(SwitchKind.PASSWORDS, true)
                        },
                    ),
                dismiss = NdDialogAction(stringResource(UiRes.string.action_cancel)) { dialog = null },
            )
        }

        null -> {}
    }
}

/**
 * The expandable "What syncs" row (08 "Rows while linked", 10 What syncs): the section header is
 * the toggle, announced as a Button with the expanded/collapsed state description, and expanding
 * it reveals the static scope text. It sits in the keyed linked section, so an `activity` update
 * keeps the expansion while a relink resets it.
 */
@Composable
private fun WhatSyncsRow() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandedLabel = stringResource(Res.string.sync_expanded)
    val collapsedLabel = stringResource(Res.string.sync_collapsed)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) expandedLabel else collapsedLabel
                    },
        ) {
            Text(
                stringResource(Res.string.sync_header_what_syncs),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                NdIcons.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            Text(
                stringResource(Res.string.sync_what_syncs),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Which of the two device-local switches a write targets. */
private enum class SwitchKind {
    PLAYBACK,
    PASSWORDS,
}

/** A failed write kept for its Retry action. */
private sealed interface LinkedFailure

private data class SwitchFailure(
    val kind: SwitchKind,
    val target: Boolean,
) : LinkedFailure

private data class RenameFailure(
    val name: String,
    val problem: SyncProblem,
) : LinkedFailure

private sealed interface LinkedDialog {
    data object RenameSelf : LinkedDialog

    data object ConfirmShare : LinkedDialog
}

/**
 * A device-local sync switch row (08 Settings › Sync): the whole row is the toggle target. The
 * trailing `Switch` is display-only — the persisted value, passed as [checked], is the state it
 * shows.
 */
@Composable
private fun SyncSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier =
            modifier.toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    )
}

/**
 * Renaming this device (08 Settings › Sync: dialog, 1–40 characters trimmed): the same field and
 * validation the Devices list uses for the other devices' names. The dialog lives inside the
 * keyed section, so typed input survives `activity` updates and dies with its identity.
 */
@Composable
private fun OwnNameDialog(
    currentName: String,
    onRename: (trimmedName: String, doneMessage: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= MAX_DEVICE_NAME
    val renamedMessage = stringResource(Res.string.sync_device_renamed, trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_rename_this_device)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.sync_device_name_label)) },
                supportingText = { Text(stringResource(Res.string.sync_device_name_hint)) },
                isError = !valid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(trimmed, renamedMessage) }, enabled = valid) {
                Text(stringResource(UiRes.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.action_cancel)) }
        },
    )
}

private const val MAX_DEVICE_NAME = 40
