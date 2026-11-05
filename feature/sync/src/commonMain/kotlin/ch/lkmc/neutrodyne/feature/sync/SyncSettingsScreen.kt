// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_continue
import ch.lkmc.neutrodyne.feature.sync.resources.sync_finish_link
import ch.lkmc.neutrodyne.feature.sync.resources.sync_finishing_restore
import ch.lkmc.neutrodyne.feature.sync.resources.sync_finishing_restore_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_intro
import ch.lkmc.neutrodyne.feature.sync.resources.sync_linked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_now
import ch.lkmc.neutrodyne.feature.sync.resources.sync_reconnect
import ch.lkmc.neutrodyne.feature.sync.resources.sync_revoked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_setup_cancel
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords_confirm_body
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords_confirm_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords_help
import ch.lkmc.neutrodyne.feature.sync.resources.sync_share_passwords_share
import ch.lkmc.neutrodyne.feature.sync.resources.sync_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_what_syncs
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Sync (08 Sync screens). The body follows [SyncController.status]; while
 * `NotConfigured` the only sync UI anywhere is this screen (R7.1).
 */
@Composable
fun SyncSettingsScreen(
    controller: SyncController,
    modifier: Modifier = Modifier,
) {
    val status by controller.status.collectAsStateWithLifecycle()
    val syncing by controller.syncing.collectAsStateWithLifecycle()
    val sharing by controller.shareFeedPasswords.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
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
                    Text(stringResource(Res.string.sync_intro), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = controller::syncNow, enabled = false) {
                        Text(stringResource(Res.string.sync_continue))
                    }
                }

                is SyncStatus.Linked -> {
                    Text(
                        stringResource(Res.string.sync_linked, current.serverUrl),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = controller::syncNow, enabled = !syncing) {
                        Text(stringResource(Res.string.sync_now))
                    }
                    Text(
                        stringResource(Res.string.sync_what_syncs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ShareFeedPasswordsRow(
                        sharing = sharing,
                        host = hostOf(current.serverUrl),
                        onShare = { enabled ->
                            scope.launch { controller.setShareFeedPasswords(enabled) }
                        },
                    )
                }

                is SyncStatus.Reconnect -> {
                    Text(stringResource(Res.string.sync_reconnect, current.serverUrl))
                }

                is SyncStatus.LinkUnfinished -> {
                    Text(stringResource(Res.string.sync_finish_link, current.serverUrl))
                }

                is SyncStatus.FinishingRestore -> {
                    Text(
                        stringResource(Res.string.sync_finishing_restore),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(Res.string.sync_finishing_restore_body, hostOf(current.serverUrl)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is SyncStatus.Revoked -> {
                    Text(stringResource(Res.string.sync_revoked, current.serverUrl))
                }
            }
        }
    }
}

/**
 * 08 Sync screens, "Share feed passwords": turning it on asks first (the passwords sit on the
 * server in readable form); turning it off needs no confirmation and pushes nothing.
 */
@Composable
private fun ShareFeedPasswordsRow(
    sharing: Boolean,
    host: String,
    onShare: (Boolean) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.sync_share_passwords), style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = sharing,
                onCheckedChange = { enabled ->
                    if (enabled) asking = true else onShare(false)
                },
            )
        }
        Text(
            stringResource(Res.string.sync_share_passwords_help),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(Res.string.sync_share_passwords_confirm_title)) },
            text = { Text(stringResource(Res.string.sync_share_passwords_confirm_body, host)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onShare(true)
                    },
                ) { Text(stringResource(Res.string.sync_share_passwords_share)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(Res.string.sync_setup_cancel)) }
            },
        )
    }
}

private fun hostOf(url: String): String = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
