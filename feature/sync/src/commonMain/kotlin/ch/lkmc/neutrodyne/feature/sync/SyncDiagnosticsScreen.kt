// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdProgress
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.resources.action_copy
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_account
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_clock_offset
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_collection_episode
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_collection_group
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_collection_member
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_collection_other
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_collection_podcast
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_collection_session
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_collection_setting
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_collection_upnext
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_cursor
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_device
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_held
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_lan
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_last_error
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_last_round
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_last_sync
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_parked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_pending
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_protocol
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_reconnects
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_refresh
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_rounds_empty
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_section_rounds
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_section_server
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_section_sync
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_server_version
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_stream
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_title
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_unavailable
import ch.lkmc.neutrodyne.feature.sync.resources.sync_diag_unknown
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsSnapshot
import ch.lkmc.neutrodyne.sync.api.SyncPendingCollection
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * Settings › Sync › Diagnostics (08 Sync diagnostics): a visual shell like [Diagnostics] for
 * 10's lines — server version and protocol, account and device, last sync and the last round, up
 * to twenty in-memory round summaries, pending outbox rows by collection, parked and held counts,
 * clock offset, live-connection state and reconnects, the local-network gate (Android), the last
 * error and the cursor prefix. Never the token, feed/server URLs or the full cursor — the
 * snapshot cannot carry them. "Copy" writes the same lines as plain text to the local clipboard
 * on tap only. All state is the ViewModel's; the screen holds no async work of its own.
 */
@Composable
internal fun SyncDiagnosticsScreen(
    modifier: Modifier = Modifier,
    vm: SyncDiagnosticsViewModel = metroViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    ShowUserMessages(messages, vm::onMessageShown)

    val clipboard = LocalClipboardManager.current

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.sync_diag_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::refresh) {
                Icon(
                    NdIcons.Refresh,
                    contentDescription = stringResource(Res.string.sync_diag_refresh),
                )
            }
        }
        val snapshot = state.snapshot
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            when {
                state.failed -> {
                    Text(
                        stringResource(Res.string.sync_diag_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    NdTextButton(
                        label = stringResource(UiRes.string.action_retry),
                        onClick = vm::refresh,
                    )
                }

                snapshot != null -> {
                    SelectionContainer {
                        Column { SnapshotLines(snapshot, vm.showLanGate) }
                    }
                }

                else -> {
                    NdProgress.Linear(
                        progress = null,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = snapshot != null && !state.loading,
                onClick = {
                    val text = vm.plainText() ?: return@TextButton
                    clipboard.setText(AnnotatedString(text))
                    vm.copied()
                },
            ) {
                Text(stringResource(UiRes.string.action_copy))
            }
        }
    }
}

@Composable
private fun SnapshotLines(
    snapshot: SyncDiagnosticsSnapshot,
    showLanGate: Boolean,
) {
    val unknown = stringResource(Res.string.sync_diag_unknown)
    val nowMs = LocalUiClock.current.now()

    Column {
        SectionHeader(Res.string.sync_diag_section_server)
        Line(Res.string.sync_diag_server_version, snapshot.serverVersion ?: unknown)
        Line(Res.string.sync_diag_protocol, snapshot.protocol?.toString() ?: unknown)
        Line(Res.string.sync_diag_account, snapshot.accountName ?: unknown)
        Line(Res.string.sync_diag_device, snapshot.deviceName ?: unknown)
        // The LAN gate is Android's (10); on the desktop its enum carries no real state.
        if (showLanGate) {
            Line(Res.string.sync_diag_lan, snapshot.lanGate.name)
        }

        SectionHeader(Res.string.sync_diag_section_sync)
        Line(
            Res.string.sync_diag_last_sync,
            snapshot.lastSyncAtMs?.let { FeedDates.relative(it, nowMs).asString() } ?: unknown,
        )
        Line(Res.string.sync_diag_clock_offset, "${snapshot.clockOffsetMs} ms")
        Line(Res.string.sync_diag_stream, snapshot.streamState.name)
        Line(Res.string.sync_diag_reconnects, snapshot.reconnectCount.toString())
        Line(Res.string.sync_diag_last_error, snapshot.lastErrorCode ?: unknown)
        Line(Res.string.sync_diag_cursor, snapshot.cursorPrefix ?: unknown)
        Line(Res.string.sync_diag_parked, snapshot.parkedCount.toString())
        Line(Res.string.sync_diag_held, snapshot.heldCount.toString())
        Text(
            stringResource(Res.string.sync_diag_pending),
            style = MaterialTheme.typography.bodySmall,
        )
        for (collection in SyncPendingCollection.entries) {
            val count = "${collectionLabel(collection)}: ${snapshot.pendingByCollection[collection] ?: 0}"
            Text(
                count,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        SectionHeader(Res.string.sync_diag_section_rounds)
        val lastRound = snapshot.lastRound
        if (lastRound == null) {
            Text(
                stringResource(Res.string.sync_diag_rounds_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Line(Res.string.sync_diag_last_round, roundLine(lastRound, nowMs))
            for (round in snapshot.rounds) {
                Text(roundLine(round, nowMs), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionHeader(label: StringResource) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Line(
    label: StringResource,
    value: String,
) {
    val text = "${stringResource(label)}: $value"
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** `{reason} · {relative time} · {report fields or failure}` — one history line. */
@Composable
private fun roundLine(
    round: SyncRoundSummary,
    nowMs: Long,
): String =
    listOfNotNull(round.reason.name, FeedDates.relative(round.completedAtMs, nowMs).asString(), round.result())
        .joinToString(" · ")

@Composable
private fun collectionLabel(collection: SyncPendingCollection): String =
    stringResource(
        when (collection) {
            SyncPendingCollection.PODCAST -> Res.string.sync_diag_collection_podcast
            SyncPendingCollection.GROUP -> Res.string.sync_diag_collection_group
            SyncPendingCollection.MEMBER -> Res.string.sync_diag_collection_member
            SyncPendingCollection.EPISODE -> Res.string.sync_diag_collection_episode
            SyncPendingCollection.UPNEXT -> Res.string.sync_diag_collection_upnext
            SyncPendingCollection.SESSION -> Res.string.sync_diag_collection_session
            SyncPendingCollection.SETTING -> Res.string.sync_diag_collection_setting
            SyncPendingCollection.OTHER -> Res.string.sync_diag_collection_other
        },
    )
