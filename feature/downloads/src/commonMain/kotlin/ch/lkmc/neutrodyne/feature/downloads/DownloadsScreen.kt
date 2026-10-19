// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.navigation.LocalNavTab
import ch.lkmc.neutrodyne.core.ui.DownloadCommand
import ch.lkmc.neutrodyne.core.ui.DownloadStateButton
import ch.lkmc.neutrodyne.core.ui.DownloadStatusText
import ch.lkmc.neutrodyne.core.ui.EmptyState
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.ReportVisibleEpisodeIds
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.downloadCommandText
import ch.lkmc.neutrodyne.core.ui.downloadSize
import ch.lkmc.neutrodyne.core.ui.downloadWaitCommand
import ch.lkmc.neutrodyne.core.ui.navigation.SharedKeys
import ch.lkmc.neutrodyne.core.ui.navigation.sharedArt
import ch.lkmc.neutrodyne.core.ui.onEpisodePointerGesture
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel_download
import ch.lkmc.neutrodyne.core.ui.resources.action_delete_download
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.action_pause_download
import ch.lkmc.neutrodyne.core.ui.resources.action_play
import ch.lkmc.neutrodyne.core.ui.resources.action_resume_download
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_retry_download
import ch.lkmc.neutrodyne.core.ui.resources.download_show_folder
import ch.lkmc.neutrodyne.core.ui.resources.downloads_about
import ch.lkmc.neutrodyne.core.ui.resources.downloads_bytes
import ch.lkmc.neutrodyne.core.ui.resources.downloads_cancel_selection
import ch.lkmc.neutrodyne.core.ui.resources.downloads_completed
import ch.lkmc.neutrodyne.core.ui.resources.downloads_dismiss
import ch.lkmc.neutrodyne.core.ui.resources.downloads_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.downloads_empty_manual
import ch.lkmc.neutrodyne.core.ui.resources.downloads_failed
import ch.lkmc.neutrodyne.core.ui.resources.downloads_in_progress
import ch.lkmc.neutrodyne.core.ui.resources.downloads_loading
import ch.lkmc.neutrodyne.core.ui.resources.downloads_partial
import ch.lkmc.neutrodyne.core.ui.resources.downloads_pause_all
import ch.lkmc.neutrodyne.core.ui.resources.downloads_play_all
import ch.lkmc.neutrodyne.core.ui.resources.downloads_resume_all
import ch.lkmc.neutrodyne.core.ui.resources.downloads_retry_all
import ch.lkmc.neutrodyne.core.ui.resources.downloads_select
import ch.lkmc.neutrodyne.core.ui.resources.downloads_select_all
import ch.lkmc.neutrodyne.core.ui.resources.downloads_select_played
import ch.lkmc.neutrodyne.core.ui.resources.downloads_selected
import ch.lkmc.neutrodyne.core.ui.resources.downloads_server_error
import ch.lkmc.neutrodyne.core.ui.resources.downloads_settings
import ch.lkmc.neutrodyne.core.ui.resources.downloads_share
import ch.lkmc.neutrodyne.core.ui.resources.downloads_speed
import ch.lkmc.neutrodyne.core.ui.resources.downloads_storage
import ch.lkmc.neutrodyne.core.ui.resources.downloads_unknown
import ch.lkmc.neutrodyne.core.ui.resources.downloads_working
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.root.LocalMiniPlayerInset
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import ch.lkmc.neutrodyne.download.api.DownloadEntry
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.DownloadStatus
import ch.lkmc.neutrodyne.download.api.LiveProgress
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadsScreen(
    state: DownloadsUiState,
    live: Map<Long, LiveProgress>,
    onVisibleIds: (Set<Long>) -> Unit,
    onOpen: (Long) -> Unit,
    onToggle: (Long) -> Unit,
    onCommand: (Long, DownloadCommand) -> Unit,
    onCancel: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onPlay: (Long?) -> Unit,
    onShare: ((Long, String) -> Unit)?,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onRetryAll: () -> Unit,
    onSelect: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onSelectPlayedEpisodes: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onSettings: () -> Unit,
    onReload: () -> Unit,
    onNotice: (DownloadNotice) -> Unit,
    onReveal: ((Long) -> Unit)? = null,
) {
    val list = rememberLazyListState()
    val rows = state.overview
    ReportVisibleEpisodeIds(
        listState = list,
        itemCount = { list.layoutInfo.totalItemsCount },
        idAt = { index ->
            list.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == index }
                ?.key as? Long
        },
        onIds = onVisibleIds,
        margin = 0,
    )
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(title = stringResource(Res.string.nav_downloads), actions = { SettingsGearButton() })
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f),
            contentPadding =
                PaddingValues(
                    bottom =
                        LocalMiniPlayerInset.current,
                ),
        ) {
            item("storage") {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        state.storage?.let {
                            stringResource(
                                Res.string.downloads_storage,
                                downloadSize(it.usedBytes),
                                it.freeBytes?.let(::downloadSize) ?: "—",
                            )
                        } ?: "—",
                    )
                    state.storage?.let { usage ->
                        val free = usage.freeBytes
                        if (free != null && usage.usedBytes + free > 0) {
                            LinearProgressIndicator(
                                progress =
                                    { usage.usedBytes.toFloat() / (usage.usedBytes + free) },
                                modifier =
                                    Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            stringResource(
                                Res.string.downloads_partial,
                                downloadSize(usage.partialBytes),
                                downloadSize(usage.orphanBytes),
                            ),
                        )
                    }
                    NdTextButton(stringResource(Res.string.downloads_settings), onClick = onSettings)
                }
            }
            if (rows == null) {
                item("loading") {
                    if (state.failed) {
                        NdTextButton(stringResource(Res.string.action_retry), onClick = onReload)
                    } else {
                        Column {
                            Text(stringResource(Res.string.downloads_loading))
                            repeat(3) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                        }
                    }
                }
                return@LazyColumn
            }
            item("notices") {
                ch.lkmc.neutrodyne.core.ui
                    .DownloadNotices(rows, LocalPlatformKind.current, onNotice)
            }
            if (rows.inProgress.isEmpty() && rows.failed.isEmpty() && rows.completed.isEmpty()) {
                item("empty") {
                    EmptyState(
                        NdIcons.Download,
                        stringResource(Res.string.downloads_empty_manual),
                        stringResource(Res.string.downloads_empty_body),
                    )
                }
            }
            if (rows.inProgress.isNotEmpty()) {
                item("progress-header") {
                    SectionHeader(stringResource(Res.string.downloads_in_progress, rows.inProgress.size)) {
                        NdTextButton(stringResource(Res.string.downloads_pause_all), onClick = onPauseAll)
                        NdTextButton(stringResource(Res.string.downloads_resume_all), onClick = onResumeAll)
                    }
                }
                items(rows.inProgress, key = { it.status.episodeId }) { entry ->
                    // A constrained runner may not have started to persist NETWORK yet.
                    val displayEntry =
                        if (state.offline && entry.status.state == DownloadState.QUEUED &&
                            entry.status.waitReason in setOf(WaitReason.NONE, WaitReason.SLOT)
                        ) {
                            entry.copy(status = entry.status.copy(waitReason = WaitReason.NETWORK))
                        } else {
                            entry
                        }
                    DownloadEntryRow(
                        displayEntry,
                        live[entry.status.episodeId],
                        false,
                        onOpen,
                        onToggle,
                        onCommand,
                        onCancel,
                        onDelete,
                        onPlay,
                        onShare,
                        onSelect,
                    )
                }
            }
            if (rows.failed.isNotEmpty()) {
                item("failed-header") {
                    SectionHeader(
                        stringResource(
                            Res.string.downloads_failed,
                            rows.failed.size,
                        ),
                    ) {
                        NdTextButton(
                            stringResource(Res.string.downloads_retry_all),
                            onClick = onRetryAll,
                        )
                    }
                }
                items(rows.failed, key = { it.status.episodeId }) { entry ->
                    DownloadEntryRow(
                        entry,
                        null,
                        false,
                        onOpen,
                        onToggle,
                        onCommand,
                        onCancel,
                        onDelete,
                        onPlay,
                        onShare,
                        onSelect,
                    )
                }
            }
            if (rows.completed.isNotEmpty()) {
                item("completed-header") {
                    SectionHeader(
                        stringResource(
                            Res.string.downloads_completed,
                            rows.completed.size,
                            downloadSize(rows.completed.sumOf { it.status.totalBytes ?: it.status.downloadedBytes }),
                        ),
                    ) {
                        NdTextButton(stringResource(Res.string.downloads_play_all), onClick = { onPlay(null) })
                    }
                    // Selection is limited to completed files; queued transfers cannot be deleted accidentally.
                    FlowRow {
                        NdTextButton(stringResource(Res.string.downloads_select_all), onClick = onSelectAll)
                        NdTextButton(
                            stringResource(Res.string.downloads_select_played),
                            onClick =
                            onSelectPlayedEpisodes,
                        )
                        if (state.selected.isNotEmpty()) {
                            Text(stringResource(Res.string.downloads_selected, state.selected.size))
                            NdTextButton(stringResource(Res.string.action_delete_download), onClick = onDeleteSelection)
                            NdTextButton(
                                stringResource(Res.string.downloads_cancel_selection),
                                onClick =
                                onClearSelection,
                            )
                        }
                    }
                }
                items(rows.completed, key = { it.status.episodeId }) { entry ->
                    DownloadEntryRow(
                        entry,
                        null,
                        entry.status.episodeId in state.selected,
                        onOpen,
                        onToggle,
                        onCommand,
                        onCancel,
                        onDelete,
                        onPlay,
                        onShare,
                        onSelect,
                        onReveal = onReveal,
                        selectionMode =
                            if (state.selected.isEmpty()) {
                                DownloadSelectionMode.BROWSE
                            } else {
                                DownloadSelectionMode.SELECT
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow { actions() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadEntryRow(
    entry: DownloadEntry,
    progress: LiveProgress?,
    selected: Boolean,
    onOpen: (Long) -> Unit,
    onToggle: (Long) -> Unit,
    onCommand: (Long, DownloadCommand) -> Unit,
    onCancel: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onPlay: (Long?) -> Unit,
    onShare: ((Long, String) -> Unit)?,
    onSelect: (Long) -> Unit,
    onReveal: ((Long) -> Unit)? = null,
    selectionMode: DownloadSelectionMode = DownloadSelectionMode.BROWSE,
) {
    val status =
        if (progress != null && entry.status.state in
            setOf(
                DownloadState.RESOLVING,
                DownloadState.DOWNLOADING,
            )
        ) {
            entry.status.copy(state = progress.state)
        } else {
            entry.status
        }
    val id = status.episodeId
    val text = downloadEntryText(status, progress).asString()
    val selectedText = if (selected) stringResource(Res.string.downloads_selected, 1) else null
    val completed = status.state == DownloadState.COMPLETED
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    if (completed) {
        actions += stringResource(Res.string.action_play) to { onPlay(id) }
        actions += stringResource(Res.string.action_delete_download) to { onDelete(id) }
        actions += stringResource(Res.string.downloads_select) to { onSelect(id) }
        if (onReveal != null) {
            actions += stringResource(Res.string.download_show_folder) to { onReveal(id) }
        }
        if (onShare != null) {
            actions += stringResource(Res.string.downloads_share) to {
                onShare(
                    id,
                    entry.episodeTitle,
                )
            }
        }
    } else {
        val command =
            if (status.state == DownloadState.QUEUED) {
                downloadWaitCommand(
                    status.waitReason,
                    status.lane,
                )
            } else {
                null
            }
        if (command != null) {
            actions += downloadCommandText(command, LocalPlatformKind.current).asString() to { onCommand(id, command) }
        }
        if (status.state != DownloadState.QUEUED && status.state != DownloadState.VERIFYING) {
            val label =
                when (status.state) {
                    DownloadState.DOWNLOADING -> Res.string.action_pause_download
                    DownloadState.PAUSED -> Res.string.action_resume_download
                    DownloadState.FAILED, DownloadState.MISSING -> Res.string.action_retry_download
                    else -> Res.string.action_cancel_download
                }
            actions += stringResource(label) to { onToggle(id) }
        }
        actions += stringResource(
            if (status.state ==
                DownloadState.FAILED
            ) {
                Res.string.downloads_dismiss
            } else {
                Res.string.action_cancel_download
            },
        ) to
            { onCancel(id) }
    }
    var rowFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    val summary = "${entry.episodeTitle}. ${entry.podcastTitle}. $text"
    Row(
        Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            if (rowFocused) {
                                onOpen(id)
                                true
                            } else {
                                false
                            }
                        }

                        Key.Delete -> {
                            if (completed) {
                                onDelete(id)
                                true
                            } else {
                                false
                            }
                        }

                        Key.Menu -> {
                            menu = true
                            true
                        }

                        Key.F10 -> {
                            if (event.isShiftPressed) {
                                menu = true
                                true
                            } else {
                                false
                            }
                        }

                        else -> {
                            false
                        }
                    }
                }
            }.onFocusChanged { rowFocused = it.isFocused }
            .focusRequester(focus)
            .onEpisodePointerGesture(
                onFocus = { focus.requestFocus() },
                onMenu = { menu = true },
                onSelect = { if (completed) onSelect(id) },
                onExtend = { if (completed) onSelect(id) },
            ).combinedClickable(
                onClick = { if (selectionMode == DownloadSelectionMode.SELECT) onSelect(id) else onOpen(id) },
                onLongClick = { if (completed) onSelect(id) else menu = true },
            ).semantics(mergeDescendants = true) {
                contentDescription = summary
                stateDescription = listOfNotNull(text, selectedText).joinToString(". ")
                if (selectionMode == DownloadSelectionMode.SELECT) this.selected = selected
                customActions =
                    actions.map { (label, action) ->
                        CustomAccessibilityAction(label) {
                            action()
                            true
                        }
                    }
            }.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode == DownloadSelectionMode.SELECT) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onSelect(id) },
                modifier =
                    Modifier.clearAndSetSemantics {},
            )
        }
        CoverArt(
            entry.artwork,
            rememberMonogram(
                entry.podcastTitle,
            ),
            Modifier.size(56.dp).sharedArt(SharedKeys.episodeArt(LocalNavTab.current, id)).clearAndSetSemantics {
            },
            avgArgb = entry.artworkAvgArgb,
        )
        Column(Modifier.weight(1f)) {
            Text(entry.episodeTitle, style = MaterialTheme.typography.titleMedium)
            Text(entry.podcastTitle, style = MaterialTheme.typography.bodySmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
            if (status.state == DownloadState.DOWNLOADING) {
                val total = progress?.totalBytes ?: status.totalBytes ?: status.estimatedBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = {
                        ((progress?.downloadedBytes ?: status.downloadedBytes).toFloat() / total).coerceIn(
                            0f,
                            1f,
                        )
                    }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (completed) {
            NdTooltipIconButton(
                onClick = { onPlay(id) },
                icon = NdIcons.PlayArrow,
                tooltip =
                    stringResource(Res.string.action_play),
                modifier = Modifier.clearAndSetSemantics {},
            )
        } else {
            DownloadStateButton(
                status.state,
                status.waitReason,
                progress?.let { tick ->
                    tick.totalBytes?.takeIf { it > 0 }?.let { tick.downloadedBytes.toFloat() / it }
                },
                {
                    if (status.state == DownloadState.QUEUED && status.waitReason != WaitReason.NEEDS_FOREGROUND) {
                        menu = true
                    } else {
                        onToggle(id)
                    }
                },
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Box {
            NdTooltipIconButton(
                onClick = { menu = true },
                icon = NdIcons.MoreVert,
                tooltip =
                    stringResource(Res.string.action_more),
                modifier = Modifier.clearAndSetSemantics {},
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                actions.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick =
                            {
                                menu = false
                                action()
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun downloadEntryText(
    status: DownloadStatus,
    progress: LiveProgress?,
): UiText {
    val platform = LocalPlatformKind.current
    val now = LocalUiClock.current
    if (status.state == DownloadState.COMPLETED) {
        return UiText.Raw(
            downloadSize(status.totalBytes ?: status.downloadedBytes),
        )
    }
    if (status.state == DownloadState.DOWNLOADING || status.state == DownloadState.VERIFYING) {
        val total = progress?.totalBytes ?: status.totalBytes ?: status.estimatedBytes
        val size = total?.let(::downloadSize) ?: stringResource(Res.string.downloads_unknown)
        val estimate =
            if (status.totalBytes == null && progress?.totalBytes == null &&
                status.estimatedBytes != null
            ) {
                stringResource(Res.string.downloads_about, size)
            } else {
                size
            }
        val bytes =
            stringResource(
                Res.string.downloads_bytes,
                downloadSize(progress?.downloadedBytes ?: status.downloadedBytes),
                estimate,
            )
        return UiText.Raw(
            progress?.bytesPerSecond?.let {
                stringResource(
                    Res.string.downloads_speed,
                    bytes,
                    downloadSize(it),
                )
            } ?: bytes,
        )
    }
    if (status.state == DownloadState.FAILED && status.lastError in
        setOf(
            DownloadError.HTTP_CLIENT,
            DownloadError.HTTP_SERVER,
        ) && status.lastHttpStatus != null
    ) {
        return UiText.Res(Res.string.downloads_server_error, listOfNotNull(status.lastHttpStatus))
    }
    return DownloadStatusText.describe(
        status.state,
        status.waitReason,
        null,
        status.lastError,
        status.nextAttemptAt,
        now.now(),
        platform,
    ) ?: UiText.Res(Res.string.downloads_working)
}

private enum class DownloadSelectionMode { BROWSE, SELECT }
