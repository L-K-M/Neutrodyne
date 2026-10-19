// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.DownloadNotices
import ch.lkmc.neutrodyne.core.ui.DownloadStatusText
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.OrphanFilesDialog
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.platform.DownloadSystemSettingsTarget
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.download_cancel
import ch.lkmc.neutrodyne.core.ui.resources.download_completed_section
import ch.lkmc.neutrodyne.core.ui.resources.download_delete
import ch.lkmc.neutrodyne.core.ui.resources.download_failed_section
import ch.lkmc.neutrodyne.core.ui.resources.download_pause
import ch.lkmc.neutrodyne.core.ui.resources.download_pause_all
import ch.lkmc.neutrodyne.core.ui.resources.download_progress_section
import ch.lkmc.neutrodyne.core.ui.resources.download_resume
import ch.lkmc.neutrodyne.core.ui.resources.download_resume_all
import ch.lkmc.neutrodyne.core.ui.resources.download_retry
import ch.lkmc.neutrodyne.core.ui.resources.download_show_folder
import ch.lkmc.neutrodyne.core.ui.resources.downloads_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.downloads_empty_title
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.DownloadEntry
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadsRoute(viewModel: DownloadsViewModel = metroViewModel()) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val messages by viewModel.messages.flow.collectAsStateWithLifecycle()
    val orphans by viewModel.orphanConfirmation.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val orphanError by viewModel.orphanError.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val platform = LocalPlatformKind.current
    val actions = LocalPlatformActions.current
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_downloads),
            actions = { SettingsGearButton() },
        )
        overview?.let { current ->
            DownloadNotices(
                current,
                platform,
            ) { notice ->
                when (notice) {
                    DownloadNotice.CAP_REACHED -> {
                        navigator.pushDetail(SettingsKey(SettingsPage.DOWNLOADS))
                    }

                    DownloadNotice.ROOT_UNAVAILABLE -> {
                        navigator.pushDetail(SettingsKey(SettingsPage.DESKTOP))
                    }

                    DownloadNotice.ORPHAN_FILES -> {
                        viewModel.confirmOrphans()
                    }

                    DownloadNotice.NOTIFICATIONS_OFF -> {
                        actions.notifications?.request {}
                    }

                    DownloadNotice.DATA_SAVER,
                    DownloadNotice.BACKGROUND_RESTRICTED,
                    -> {
                        val target =
                            if (notice ==
                                DownloadNotice.DATA_SAVER
                            ) {
                                DownloadSystemSettingsTarget.DATA_SAVER
                            } else {
                                DownloadSystemSettingsTarget.BACKGROUND
                            }
                        if (actions.downloadSettings?.open(target) != true) viewModel.failed()
                    }

                    DownloadNotice.STORAGE_LOW -> {
                        if (platform == PlatformKind.ANDROID) {
                            if (actions.downloadSettings?.open(DownloadSystemSettingsTarget.STORAGE) !=
                                true
                            ) {
                                viewModel.failed()
                            }
                        } else {
                            viewModel.command {
                                val path = storageRoots().firstOrNull { it.rootId == current.storage.rootId }?.path
                                if (path == null || actions.reveal?.reveal(path) != true) viewModel.failed()
                            }
                        }
                    }

                    DownloadNotice.MOVE_IN_PROGRESS,
                    DownloadNotice.YOUTUBE_PAUSED,
                    -> {
                        Unit
                    }
                }
            }
            if (current.inProgress.isEmpty() && current.completed.isEmpty() && current.failed.isEmpty()) {
                NdEmptyState(
                    NdIcons.Download,
                    stringResource(Res.string.downloads_empty_title),
                    stringResource(Res.string.downloads_empty_body),
                )
            } else {
                Row {
                    TextButton(
                        onClick = { viewModel.command { pauseAll() } },
                    ) { Text(stringResource(Res.string.download_pause_all)) }
                    TextButton(
                        onClick = { viewModel.command { resumeAll() } },
                    ) { Text(stringResource(Res.string.download_resume_all)) }
                }
                LazyColumn {
                    listOf(
                        Res.string.download_progress_section to current.inProgress,
                        Res.string.download_completed_section to current.completed,
                        Res.string.download_failed_section to current.failed,
                    ).forEach {
                        (
                            label,
                            entries,
                        ),
                        ->
                        if (entries.isNotEmpty()) {
                            item { Text(stringResource(label)) }
                            items(
                                entries,
                                key = { it.status.episodeId },
                            ) { entry ->
                                DownloadEntryRow(
                                    entry,
                                    onPause = { viewModel.command { pause(listOf(it)) } },
                                    onResume = { viewModel.command { resume(listOf(it)) } },
                                    onRetry = { viewModel.command { retry(listOf(it)) } },
                                    onDelete = { viewModel.command { delete(listOf(it), DeleteReason.USER) } },
                                    onCancel = { viewModel.command { cancel(listOf(it)) } },
                                    onReveal =
                                        actions.reveal?.let { reveal ->
                                            { id ->
                                                viewModel.command {
                                                    val file = shareableFile(id)
                                                    if (file == null || !reveal.reveal(file.contentUri)) {
                                                        viewModel.failed()
                                                    }
                                                }
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    orphans?.let {
        OrphanFilesDialog(
            it,
            working,
            viewModel::deleteOrphans,
            viewModel::dismissOrphans,
            error = orphanError,
        )
    }
    ShowUserMessages(
        messages,
        viewModel.messages::shown,
    )
}

@Composable
private fun DownloadEntryRow(
    entry: DownloadEntry,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onReveal: ((Long) -> Unit)?,
) {
    val status = entry.status
    ListItem(
        headlineContent = { Text(entry.episodeTitle) },
        supportingContent = {
            Column {
                Text(entry.podcastTitle)
                DownloadStatusText
                    .describe(
                        status.state,
                        status.waitReason,
                        status.totalBytes?.takeIf { it > 0 }?.let { status.downloadedBytes.toFloat() / it },
                        status.lastError,
                        status.nextAttemptAt,
                        LocalUiClock.current.now(),
                        LocalPlatformKind.current,
                    )?.let { Text(it.asString()) }
            }
        },
        trailingContent = {
            Row {
                when (status.state) {
                    DownloadState.QUEUED,
                    DownloadState.RESOLVING,
                    DownloadState.DOWNLOADING,
                    DownloadState.VERIFYING,
                    -> {
                        TextButton(onClick = {
                            onPause(status.episodeId)
                        }) { Text(stringResource(Res.string.download_pause)) }
                    }

                    DownloadState.PAUSED -> {
                        TextButton(onClick = {
                            onResume(status.episodeId)
                        }) { Text(stringResource(Res.string.download_resume)) }
                    }

                    DownloadState.FAILED,
                    DownloadState.MISSING,
                    -> {
                        TextButton(onClick = {
                            onRetry(status.episodeId)
                        }) { Text(stringResource(Res.string.download_retry)) }
                    }

                    DownloadState.COMPLETED -> {
                        Unit
                    }
                }
                if (status.state == DownloadState.COMPLETED && onReveal != null) {
                    TextButton(onClick = { onReveal(status.episodeId) }) {
                        Text(stringResource(Res.string.download_show_folder))
                    }
                }
                TextButton(onClick = {
                    if (status.state ==
                        DownloadState.COMPLETED
                    ) {
                        onDelete(status.episodeId)
                    } else {
                        onCancel(status.episodeId)
                    }
                }) {
                    val label =
                        when (status.state) {
                            DownloadState.COMPLETED,
                            DownloadState.FAILED,
                            DownloadState.MISSING,
                            -> Res.string.download_delete

                            else -> Res.string.download_cancel
                        }
                    Text(stringResource(label))
                }
            }
        },
    )
}
