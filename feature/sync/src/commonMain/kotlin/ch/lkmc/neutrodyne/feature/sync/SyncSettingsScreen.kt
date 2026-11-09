// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SyncApproveKey
import ch.lkmc.neutrodyne.core.navigation.SyncDevicesKey
import ch.lkmc.neutrodyne.core.navigation.SyncSetupKey
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_activity_idle
import ch.lkmc.neutrodyne.feature.sync.resources.sync_activity_live
import ch.lkmc.neutrodyne.feature.sync.resources.sync_activity_syncing
import ch.lkmc.neutrodyne.feature.sync.resources.sync_continue
import ch.lkmc.neutrodyne.feature.sync.resources.sync_devices
import ch.lkmc.neutrodyne.feature.sync.resources.sync_devices_linked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_finish_link
import ch.lkmc.neutrodyne.feature.sync.resources.sync_intro
import ch.lkmc.neutrodyne.feature.sync.resources.sync_link_again
import ch.lkmc.neutrodyne.feature.sync.resources.sync_link_another
import ch.lkmc.neutrodyne.feature.sync.resources.sync_link_another_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_linked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_now
import ch.lkmc.neutrodyne.feature.sync.resources.sync_reconnect
import ch.lkmc.neutrodyne.feature.sync.resources.sync_reconnect_action
import ch.lkmc.neutrodyne.feature.sync.resources.sync_revoked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_run_server
import ch.lkmc.neutrodyne.feature.sync.resources.sync_server_address
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_address_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_what_syncs
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Sync (08 Settings › Sync). The body follows [SyncController.status]; while
 * `NotConfigured` the only sync UI anywhere is this screen and the Settings row (R7.1). Every
 * entry affordance pushes `SyncSetupKey`: with the typed address while unconfigured, with the
 * known `serverUrl` for `Reconnect`, `LinkUnfinished` (which resumes whatever step the existing
 * `LinkFlow` holds — it is never cancelled or restarted here) and `Revoked`. `Linked` stays
 * read-only: a replacement link is the separate confirmed unlink flow.
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
                    LinkedDeviceRows(
                        controller,
                        current,
                        onDevices = { navigator.push(SyncDevicesKey) },
                        onApprove = { navigator.push(SyncApproveKey()) },
                    )
                    Text(
                        stringResource(Res.string.sync_what_syncs),
                        style = MaterialTheme.typography.bodySmall,
                    )
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

@Composable
private fun ColumnScope.LinkedDeviceRows(
    controller: SyncController,
    linked: SyncStatus.Linked,
    onDevices: () -> Unit,
    onApprove: () -> Unit,
) {
    val identity = linked.identity()
    var count by remember(identity) { mutableStateOf<Int?>(null) }
    LaunchedEffect(identity) {
        val result = controller.devices()
        if (controller.status.value.identity() == identity && result is Outcome.Success) {
            count = result.value.size
        }
    }
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
}
