// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.SyncSettingsKey
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_continue
import ch.lkmc.neutrodyne.feature.sync.resources.sync_finish_link
import ch.lkmc.neutrodyne.feature.sync.resources.sync_intro
import ch.lkmc.neutrodyne.feature.sync.resources.sync_linked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_now
import ch.lkmc.neutrodyne.feature.sync.resources.sync_reconnect
import ch.lkmc.neutrodyne.feature.sync.resources.sync_revoked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_what_syncs
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Sync (08 Sync screens). The body follows [SyncController.status]; while
 * `NotConfigured` the only sync UI anywhere is this screen (R7.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    controller: SyncController,
    modifier: Modifier = Modifier,
) {
    val status by controller.status.collectAsStateWithLifecycle()
    val syncing by controller.syncing.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.sync_title)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                }

                is SyncStatus.Reconnect -> {
                    Text(stringResource(Res.string.sync_reconnect, current.serverUrl))
                }

                is SyncStatus.LinkUnfinished -> {
                    Text(stringResource(Res.string.sync_finish_link, current.serverUrl))
                }

                is SyncStatus.Revoked -> {
                    Text(stringResource(Res.string.sync_revoked, current.serverUrl))
                }
            }
        }
    }
}
