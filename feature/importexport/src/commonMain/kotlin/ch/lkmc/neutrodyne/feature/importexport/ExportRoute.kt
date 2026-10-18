// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_share
import ch.lkmc.neutrodyne.core.ui.resources.export_saved
import ch.lkmc.neutrodyne.core.ui.resources.export_show_in_folder
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * The `ExportKey` entry (05 OPML export): the shared dialog over the library or one group. The
 * platform seams stay here — the ViewModel sees only [ExportDestinations], so common UI never
 * touches intents, AWT or the file system.
 */
@Composable
internal fun ExportRoute(key: ExportKey) {
    val viewModel = assistedMetroViewModel<ExportViewModel, ExportViewModel.Factory> { create(key.groupId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current
    val actions = LocalPlatformActions.current
    ShowUserMessages(messages, viewModel::acknowledgeMessage)
    val destinations =
        remember(actions) {
            ExportDestinations(
                canShare = actions.share != null,
                save = { export -> actions.saver.create(export.fileName, export.mimeType) },
                share = { export ->
                    export.shareUri?.let { actions.share?.shareFile(it, export.mimeType, export.fileName) }
                },
            )
        }
    LaunchedEffect(state.finished) {
        val finished = viewModel.consumeFinished() ?: return@LaunchedEffect
        // Popping disposes this entry; the root snackbar must survive that disposal.
        withContext(NonCancellable) {
            navigator.pop(key)
            if (!finished.shared) showSavedSnackbar(snackbar, actions, finished)
        }
    }
    ExportDialogContent(
        state = state,
        canShare = actions.share != null,
        onLayout = viewModel::setLayout,
        onYouTube = viewModel::setYouTube,
        onPasswords = viewModel::setPasswords,
        onShare = { viewModel.export(ExportAction.SHARE, destinations) },
        onSave = { viewModel.export(ExportAction.SAVE, destinations) },
        onConfirmWarning = viewModel::confirmWarning,
        onCancelWarning = viewModel::cancelWarning,
    )
}

/**
 * "Saved" with the destination's follow-up (05 Destinations): Android offers the sheet again on
 * the same prepared artifact's share URI, the desktop "Show in folder" on the saved `file:` URI.
 */
private suspend fun showSavedSnackbar(
    snackbar: SnackbarHostState,
    actions: PlatformActions,
    finished: FinishedExport,
) {
    val export = finished.export
    val shareUri = export.shareUri
    val destination = finished.destinationUri
    val label =
        when {
            actions.share != null && shareUri != null -> getString(Res.string.action_share)
            actions.reveal != null && destination != null -> getString(Res.string.export_show_in_folder)
            else -> null
        }
    val result = snackbar.showSnackbar(getString(Res.string.export_saved), label)
    if (result != SnackbarResult.ActionPerformed) return
    when {
        actions.share != null && shareUri != null -> {
            actions.share?.shareFile(shareUri, export.mimeType, export.fileName)
        }

        actions.reveal != null && destination != null -> {
            actions.reveal?.reveal(destination)
        }
    }
}
