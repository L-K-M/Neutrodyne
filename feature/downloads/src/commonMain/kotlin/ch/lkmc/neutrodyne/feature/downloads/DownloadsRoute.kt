// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.DownloadDialogs
import ch.lkmc.neutrodyne.core.ui.MeteredConsentDialog
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.downloadSize
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.SystemSettingsTarget
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_delete_download
import ch.lkmc.neutrodyne.core.ui.resources.downloads_delete_orphans
import ch.lkmc.neutrodyne.core.ui.resources.downloads_delete_orphans_body
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadsRoute(viewModel: DownloadsViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val orphans by viewModel.orphanConfirmation.collectAsStateWithLifecycle()
    val orphanWorking by viewModel.orphanWorking.collectAsStateWithLifecycle()
    val orphanError by viewModel.orphanError.collectAsStateWithLifecycle()
    val consent by viewModel.playback.meteredConsent.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val platform = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val settings = {
        navigator.pushDetail(SettingsKey(SettingsPage.DOWNLOADS))
        Unit
    }
    val manageStorage =
        ch.lkmc.neutrodyne.core.ui
            .rememberDownloadStorageAction(viewModel.downloads)
    DownloadsScreen(
        state = state,
        live = live,
        onVisibleIds = viewModel::onVisibleIds,
        onOpen = { navigator.pushDetail(EpisodeKey(it)) },
        onToggle = viewModel.downloads::toggle,
        onCommand = { id, command ->
            if (command ==
                ch.lkmc.neutrodyne.core.ui.DownloadCommand.STORAGE
            ) {
                manageStorage()
            } else {
                viewModel.downloads.command(id, command)
            }
        },
        onCancel = viewModel.downloads::cancel,
        onDelete = viewModel::delete,
        onPlay = viewModel::play,
        onShare =
            platform.share?.let { share ->
                {
                    id,
                    title,
                    ->
                    scope.launch { viewModel.shareFile(id)?.let { share.shareFile(it.contentUri, it.mimeType, title) } }
                    Unit
                }
            },
        onReveal = platform.reveal?.let { reveal -> { id -> viewModel.revealFile(id, reveal) } },
        onPauseAll = viewModel::pauseAll,
        onResumeAll = viewModel::resumeAll,
        onRetryAll = viewModel::retryAll,
        onSelect = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAllCompleted,
        onSelectPlayedEpisodes = viewModel::selectPlayed,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelection = viewModel::deleteSelection,
        onSettings = settings,
        onReload = viewModel::reload,
        onNotice = { notice ->
            when (notice) {
                DownloadNotice.DATA_SAVER -> platform.settings?.open(SystemSettingsTarget.BACKGROUND_DATA)
                DownloadNotice.BACKGROUND_RESTRICTED -> platform.settings?.open(SystemSettingsTarget.APP_DETAILS)
                DownloadNotice.NOTIFICATIONS_OFF -> viewModel.downloads.askNotifications()
                DownloadNotice.STORAGE_LOW -> manageStorage()
                DownloadNotice.ORPHAN_FILES -> viewModel.askDeleteOrphans()
                DownloadNotice.ROOT_UNAVAILABLE -> navigator.pushDetail(SettingsKey(SettingsPage.DESKTOP))
                DownloadNotice.CAP_REACHED -> settings()
                DownloadNotice.MOVE_IN_PROGRESS, DownloadNotice.YOUTUBE_PAUSED -> Unit
            }
        },
    )
    DownloadDialogs(viewModel.downloads)
    if (consent !=
        null
    ) {
        MeteredConsentDialog(
            viewModel.playback::consentOnce,
            viewModel.playback::consentAlways,
            viewModel.playback::consentDismiss,
        )
    }
    orphans?.let { bytes ->
        ch.lkmc.neutrodyne.core.ui.OrphanFilesDialog(
            bytes,
            orphanWorking,
            viewModel::deleteOrphans,
            viewModel::dismissOrphans,
            error = orphanError,
        )
    }
    ShowUserMessages(state.messages, viewModel::onMessageShown, onMessageAction = { id ->
        if (viewModel.playback.onMessageAction(id) ==
            PlaybackMessageAction.OpenPlaybackSettings
        ) {
            navigator.pushDetail(SettingsKey(SettingsPage.PLAYBACK))
        }
    })
}
