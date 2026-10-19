// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_share
import ch.lkmc.neutrodyne.core.ui.resources.export_saved
import ch.lkmc.neutrodyne.core.ui.resources.export_show_in_folder
import ch.lkmc.neutrodyne.core.ui.root.LocalRootScope
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * The `ExportKey` entry (05 OPML export): the shared dialog over the library or one group. The
 * platform seams stay here — the ViewModel sees only [ExportDestinations], so common UI never
 * touches intents, AWT or the file system.
 *
 * The interactive export launches on this entry's composition scope, but the ViewModel runs its
 * data work — prepare and the accepted destination copy — on the injected `@ApplicationScope`
 * job this scope only awaits: disposing the dialog (pop, or an Activity recreation) cancels the
 * caller, never the copy (05: leaving does not cancel an accepted save). The platform calls
 * stay bound to this scope's captured context, so a disposed route's dead seams are never
 * invoked. The post-save snackbar is different work: it launches on the root-owned
 * `LocalRootScope`, so it survives the pop that disposes this entry but still ends with the
 * root, and it re-reads the live `LocalPlatformActions` rather than a stale callback.
 */
@Composable
internal fun ExportRoute(
    key: ExportKey,
    viewModel: ExportViewModel =
        assistedMetroViewModel<ExportViewModel, ExportViewModel.Factory> { create(key.groupId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current
    val rootScope = LocalRootScope.current
    val actions = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
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
        navigator.pop(key)
        if (!finished.shared) rootScope.launch { showSavedSnackbar(snackbar, actions, finished) }
    }
    ExportDialogContent(
        state = state,
        canShare = actions.share != null,
        onLayout = viewModel::setLayout,
        onYouTube = viewModel::setYouTube,
        onPasswords = viewModel::setPasswords,
        onShare = { scope.launch { viewModel.export(ExportAction.SHARE, destinations) } },
        onSave = { scope.launch { viewModel.export(ExportAction.SAVE, destinations) } },
        onConfirmWarning = { scope.launch { viewModel.confirmWarning(destinations) } },
        onCancelWarning = viewModel::cancelWarning,
    )
}

/**
 * "Saved" with the destination's follow-up (05 Destinations): Android offers the sheet again on
 * the same prepared artifact's share URI, the desktop "Show in folder" on the saved `file:` URI.
 * An action snackbar on the serial root host must stay bounded (08's rule: `Long` with an
 * action, `Short` without) — the M3 default of `Indefinite` would starve every later message.
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
    val result =
        snackbar.showSnackbar(
            message = getString(Res.string.export_saved),
            actionLabel = label,
            withDismissAction = label != null,
            duration = if (label != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
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
