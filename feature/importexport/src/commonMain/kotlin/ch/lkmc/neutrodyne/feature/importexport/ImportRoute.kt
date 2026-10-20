// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch

/**
 * The `ImportKey` route (05 Import preview): a notification or picker lands here with a session
 * already persisted. The key's `sessionId` is a `String` (the notification extras contract), so
 * this is the one place it is validated to the `Long` the ViewModel is assisted with — an
 * invalid or non-numeric id renders the unavailable body instead of crashing or querying a
 * bogus session.
 *
 * Pop ownership stays here like D3's export: the explicit preview "Cancel" runs the ViewModel's
 * `cancelSession` on the application scope and only then pops this key, so the pop can never
 * outrun the delete. Normal Back just pops — an accepted confirm keeps running on the
 * `@ApplicationScope` job and the committed session's report stays open to reopen.
 */
@Composable
internal fun ImportRoute(key: ImportKey) {
    val sessionId = key.sessionId.toLongOrNull()
    if (sessionId == null) {
        // A malformed extras value is the caller's bug, not a crash: explicit dead end + Back.
        val navigator = LocalAppNavigator.current
        ImportUnavailableBody(onBack = { navigator.pop(key) })
    } else {
        ImportSessionRoute(key, sessionId)
    }
}

@Composable
internal fun ImportSessionRoute(
    key: ImportKey,
    sessionId: Long,
    viewModel: ImportViewModel =
        assistedMetroViewModel<ImportViewModel, ImportViewModel.Factory> { create(sessionId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val requester = LocalPlatformActions.current.notifications
    val scope = rememberCoroutineScope()
    ShowUserMessages(messages, viewModel::acknowledgeMessage)

    ImportScreen(
        state = state,
        items = viewModel.items,
        onQuery = viewModel::setQuery,
        onFilter = viewModel::setFilter,
        onToggle = viewModel::toggleItem,
        onSelectAll = viewModel::selectAll,
        onSelectNone = viewModel::selectNone,
        onImportGroups = viewModel::setImportGroups,
        onIncludeGroup = viewModel::setGroupIncluded,
        onRenameGroup = viewModel::renameGroup,
        onTargetGroup = viewModel::setTargetGroup,
        onTargetGroupName = viewModel::setTargetGroupName,
        onTreatExisting = viewModel::setTreatExistingAsPlayed,
        onNotify = { enabled -> viewModel.setNotifyNewEpisodes(enabled, requester) },
        onConfirm = { scope.launch { viewModel.confirm() } },
        onCancelPreview = {
            scope.launch {
                if (viewModel.cancelSession()) navigator.pop(key)
            }
        },
        onStopImport = { scope.launch { viewModel.cancelSession() } },
        onBack = { navigator.pop(key) },
    )
}

/** Preview-editable only while a real non-backup PREVIEW session sits behind the key. */
internal val ImportUiState.previewEditable: Boolean
    get() =
        session?.state == ImportState.PREVIEW && session.format != ImportFormat.NEUTRODYNE_BACKUP
