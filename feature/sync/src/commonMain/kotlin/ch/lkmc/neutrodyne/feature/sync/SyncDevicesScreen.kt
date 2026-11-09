// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdProgress
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.onSecondaryClick
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_last_seen
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_menu
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_name_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_name_label
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_never_seen
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_remove
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_remove_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_remove_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_removed
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_rename
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_rename_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_renamed
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_this
import ch.lkmc.neutrodyne.feature.sync.resources.sync_device_version
import ch.lkmc.neutrodyne.feature.sync.resources.sync_devices
import ch.lkmc.neutrodyne.feature.sync.resources.sync_devices_empty
import ch.lkmc.neutrodyne.feature.sync.resources.sync_devices_manage_link
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncDevice
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * Settings › Sync › Devices (08 Devices): one row per `SyncDevice` with platform icon, name,
 * "Neutrodyne {appVersion}" and "Last seen {t}" ("This device" for the current device, "Not seen
 * for 6 months" in `tertiary` while `stale`). The row menu offers Rename for every device and
 * Remove for the others — removing this device is the separate Unlink flow. Everything goes
 * through [SyncController]; the screen never touches a client or store itself.
 *
 * The screen body is keyed to the [SyncIdentity] it was opened under: an identity change pops it,
 * cancelling its coroutine scope and every in-flight operation, and each completion additionally
 * checks `controller.status.identity()` before writing — a revoke or rename issued on account A
 * can therefore never repaint account B's list or show its snackbar, while an SSE `activity`
 * update (a new `Linked` value with the same identity) never cancels an in-flight call.
 */
@Composable
fun SyncDevicesScreen(
    controller: SyncController,
    modifier: Modifier = Modifier,
) {
    val status by controller.status.collectAsStateWithLifecycle()
    // The body is keyed to the (server, account, device) identity, not to the whole `Linked`
    // value: an `activity`/`lastSyncAtMs` update recomposes here without touching the body, while
    // an unlink or relink pops the old body — its coroutine scope, in-flight operations, dialogs
    // and loaded list die with it and can never write into the new account's screen.
    key(status.identity()) {
        SyncDevicesBody(controller, status.identity(), status.serverUrl(), modifier)
    }
}

@Composable
private fun SyncDevicesBody(
    controller: SyncController,
    identity: SyncIdentity?,
    serverUrl: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current
    val clock = LocalUiClock.current

    var ui by remember { mutableStateOf(DevicesUi(loading = identity != null)) }
    var dialog by remember { mutableStateOf<DeviceDialog?>(null) }

    fun stillCurrent(): Boolean = controller.status.value.identity() == identity

    suspend fun refresh() {
        if (identity == null) {
            ui = ui.copy(loading = false, devices = emptyList(), error = null)
            return
        }
        when (val outcome = controller.devices()) {
            is Outcome.Success -> {
                if (stillCurrent()) {
                    ui = ui.copy(loading = false, devices = outcome.value, error = null, busyId = null)
                }
            }

            is Outcome.Failure -> {
                if (stillCurrent()) {
                    ui = ui.copy(loading = false, error = outcome.error, busyId = null)
                }
            }
        }
    }

    fun runOp(
        device: SyncDevice,
        op: DeviceOp,
        successMessage: String,
    ) {
        if (identity == null) return
        scope.launch {
            ui = ui.copy(busyId = device.id, opError = null)
            val outcome =
                when (op) {
                    is DeviceOp.Rename -> controller.renameDevice(device.id, op.name)
                    DeviceOp.Remove -> controller.revokeDevice(device.id)
                }
            when (outcome) {
                is Outcome.Success -> {
                    if (stillCurrent()) {
                        // The list reflects the change before the snackbar's suspension returns
                        // (`showSnackbar` only resumes on dismiss).
                        refresh()
                        snackbar.showSnackbar(successMessage)
                    }
                }

                is Outcome.Failure -> {
                    if (stillCurrent()) {
                        if (outcome.error == SyncProblem.DeviceGone) {
                            // The row is already gone on the server: reload (10 Errors) so it
                            // leaves the list, and word it — retrying the op can never succeed.
                            refresh()
                            ui = ui.copy(busyId = null, error = outcome.error, opError = null)
                        } else {
                            ui = ui.copy(busyId = null, opError = DeviceOpError(device, op, outcome.error))
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            stringResource(Res.string.sync_devices),
            style = MaterialTheme.typography.titleLarge,
        )
        when {
            ui.loading && ui.devices.isEmpty() -> {
                NdProgress.Linear(progress = null, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }

            ui.error != null && ui.devices.isEmpty() -> {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        problemText(ui.error!!, hostOf(serverUrl.orEmpty())),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    NdTextButton(label = stringResource(UiRes.string.action_retry), onClick = {
                        ui = ui.copy(loading = true, error = null)
                        scope.launch { refresh() }
                    })
                }
            }

            ui.devices.isEmpty() -> {
                Text(
                    stringResource(Res.string.sync_devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            else -> {
                ui.opError?.let { failure ->
                    val retryMessage =
                        when (val op = failure.op) {
                            is DeviceOp.Rename -> stringResource(Res.string.sync_device_renamed, op.name)
                            DeviceOp.Remove -> stringResource(Res.string.sync_device_removed, failure.device.name)
                        }
                    OperationFailure(
                        failure = failure,
                        busy = ui.busyId != null,
                        host = hostOf(serverUrl.orEmpty()),
                        onRetry = { runOp(failure.device, failure.op, retryMessage) },
                    )
                }
                ui.error?.let { problem ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            problemText(problem, hostOf(serverUrl.orEmpty())),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        NdTextButton(
                            label = stringResource(UiRes.string.action_retry),
                            onClick = {
                                ui = ui.copy(loading = true, error = null)
                                scope.launch { refresh() }
                            },
                        )
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                    items(ui.devices, key = SyncDevice::id) { device ->
                        DeviceRow(
                            device = device,
                            busy = ui.busyId == device.id,
                            nowMs = clock.now(),
                            onRename = { dialog = DeviceDialog.Rename(device) },
                            onRemove = { dialog = DeviceDialog.Remove(device) },
                        )
                    }
                }
            }
        }
        if (!serverUrl.isNullOrEmpty()) {
            val actions = LocalPlatformActions.current
            val host = hostOf(serverUrl)
            NdTextButton(
                label = stringResource(Res.string.sync_devices_manage_link, host),
                onClick = { actions.urls.open(serverUrl) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    when (val open = dialog) {
        is DeviceDialog.Rename -> {
            RenameDialog(
                device = open.device,
                busy = ui.busyId != null,
                onRename = { trimmed, message ->
                    dialog = null
                    runOp(open.device, DeviceOp.Rename(trimmed), message)
                },
                onDismiss = { dialog = null },
            )
        }

        is DeviceDialog.Remove -> {
            val removedMessage = stringResource(Res.string.sync_device_removed, open.device.name)
            NdDialog(
                onDismissRequest = { dialog = null },
                title = stringResource(Res.string.sync_device_remove_title, open.device.name),
                text = stringResource(Res.string.sync_device_remove_body),
                confirm =
                    NdDialogAction(
                        label = stringResource(Res.string.sync_device_remove),
                        role = NdActionRole.DESTRUCTIVE,
                        onClick = {
                            dialog = null
                            runOp(open.device, DeviceOp.Remove, removedMessage)
                        },
                    ),
                dismiss = NdDialogAction(stringResource(UiRes.string.action_cancel)) { dialog = null },
            )
        }

        null -> {}
    }
}

private data class DevicesUi(
    val loading: Boolean = true,
    val devices: List<SyncDevice> = emptyList(),
    val error: SyncProblem? = null,
    val busyId: String? = null,
    val opError: DeviceOpError? = null,
)

private sealed interface DeviceOp {
    data class Rename(
        val name: String,
    ) : DeviceOp

    data object Remove : DeviceOp
}

private data class DeviceOpError(
    val device: SyncDevice,
    val op: DeviceOp,
    val problem: SyncProblem,
)

private sealed interface DeviceDialog {
    data class Rename(
        val device: SyncDevice,
    ) : DeviceDialog

    data class Remove(
        val device: SyncDevice,
    ) : DeviceDialog
}

@Composable
private fun OperationFailure(
    failure: DeviceOpError,
    busy: Boolean,
    host: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            problemText(failure.problem, host),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        NdTextButton(label = stringResource(UiRes.string.action_retry), onClick = onRetry, enabled = !busy)
    }
}

@Composable
private fun DeviceRow(
    device: SyncDevice,
    busy: Boolean,
    nowMs: Long,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(device.name) },
            supportingContent = {
                Column {
                    device.appVersion?.let {
                        Text(
                            stringResource(Res.string.sync_device_version, it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val lastSeen = lastSeenLabel(device, nowMs)
                    if (lastSeen != null) {
                        Text(
                            lastSeen.asString(),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (device.stale) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            },
            leadingContent = { Icon(platformIcon(device.platform), contentDescription = device.platform) },
            trailingContent = {
                NdIconButton(
                    onClick = { menuOpen = true },
                    icon = NdIcons.MoreVert,
                    contentDescription = stringResource(Res.string.sync_device_menu),
                    enabled = !busy,
                )
            },
            modifier =
                Modifier
                    .combinedClickable(
                        onClick = { menuOpen = true },
                        onLongClick = { menuOpen = true },
                    ).onSecondaryClick { menuOpen = true },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.sync_device_rename)) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            if (!device.current) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.sync_device_remove)) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/** The second supporting line: "This device" while current, the stale text, else "Last seen". */
@Composable
private fun lastSeenLabel(
    device: SyncDevice,
    nowMs: Long,
): UiText? {
    val lastSeenAtMs = device.lastSeenAtMs
    return when {
        device.current -> {
            UiText.Res(Res.string.sync_device_this)
        }

        device.stale -> {
            UiText.Res(Res.string.sync_device_never_seen)
        }

        lastSeenAtMs != null -> {
            UiText.Res(
                Res.string.sync_device_last_seen,
                listOf(FeedDates.relative(lastSeenAtMs, nowMs)),
            )
        }

        else -> {
            null
        }
    }
}

/** `phone_android`/`desktop_windows`/`laptop_mac`/`computer`/`sync_alt` per 08 Devices. */
private fun platformIcon(platform: String): ImageVector {
    val p = platform.lowercase()
    return when {
        "android" in p -> NdIcons.PhoneAndroid
        "windows" in p -> NdIcons.DesktopWindows
        "mac" in p || "darwin" in p -> NdIcons.LaptopMac
        "linux" in p -> NdIcons.Computer
        else -> NdIcons.SyncAlt
    }
}

/**
 * The rename dialog (08 Devices): the field validates the trimmed 1–40 character rule, the saved
 * value is trimmed before [SyncController.renameDevice] sees it (the controller trims again
 * defensively, 10).
 */
@Composable
private fun RenameDialog(
    device: SyncDevice,
    busy: Boolean,
    onRename: (trimmedName: String, successMessage: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(device.name) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= MAX_DEVICE_NAME
    val renamedMessage = stringResource(Res.string.sync_device_renamed, trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_device_rename_title, device.name)) },
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
            TextButton(onClick = { onRename(trimmed, renamedMessage) }, enabled = valid && !busy) {
                Text(stringResource(UiRes.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.action_cancel)) }
        },
    )
}

private const val MAX_DEVICE_NAME = 40
