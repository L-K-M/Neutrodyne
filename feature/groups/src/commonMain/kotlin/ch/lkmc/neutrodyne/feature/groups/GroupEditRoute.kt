// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_deleted
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_discard
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_discard_prompt
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_gone
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_undo
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_undo_expired
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_undo_name_taken
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

@Composable
@Suppress("FunctionNaming") // Compose functions use PascalCase.
internal fun GroupEditRoute(key: GroupEditKey) {
    val viewModel =
        assistedMetroViewModel<GroupEditViewModel, GroupEditViewModel.Factory> { extras ->
            create(key.groupId, extras.createSavedStateHandle())
        }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current
    var discardPrompt by rememberSaveable { mutableStateOf(false) }
    val close = {
        if (!state.busy) {
            if (state.dirty) discardPrompt = true else navigator.pop()
        }
    }
    val events = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(events, isBackEnabled = true, onBackCompleted = { close() })

    LaunchedEffect(state.done) {
        val done = viewModel.consumeDone() ?: return@LaunchedEffect
        // The root host outlives this popped entry. Keep Undo callable after its ViewModel clears.
        withContext(NonCancellable) {
            navigator.pop()
            when (done) {
                is GroupEditDone.Deleted -> showDeleteUndo(snackbar, done, viewModel::undoDelete)
                GroupEditDone.Gone -> snackbar.showSnackbar(getString(Res.string.group_edit_gone))
                GroupEditDone.Saved -> Unit
            }
        }
    }

    GroupEditScreen(
        state,
        key.groupId,
        viewModel::edit,
        viewModel::search,
        viewModel::toggleMember,
        viewModel::save,
        viewModel::delete,
        onClose = { close() },
        onRetry = viewModel::retry,
    )
    if (discardPrompt) {
        NdDialog(
            onDismissRequest = {
                discardPrompt = false
            },
            title = stringResource(Res.string.group_edit_discard_prompt),
            confirm =
                NdDialogAction(stringResource(Res.string.group_edit_discard), role = NdActionRole.DESTRUCTIVE) {
                    discardPrompt = false
                    navigator.pop()
                },
            dismiss =
                NdDialogAction(stringResource(Res.string.action_cancel)) {
                    discardPrompt = false
                },
        )
    }
    ShowUserMessages(state.messages, viewModel::onMessageShown)
}

/** One deadline covers host queueing, name collisions and retries, even after the route pops. */
internal suspend fun showDeleteUndo(
    snackbar: SnackbarHostState,
    done: GroupEditDone.Deleted,
    undo: suspend (DeletedGroupToken) -> Outcome<Long, GroupError>,
) {
    var text = getString(Res.string.group_edit_deleted)
    var duration = SnackbarDuration.Long
    val remaining = GroupRepository.UNDO_WINDOW_MS.milliseconds - done.started.elapsedNow()
    withTimeoutOrNull(remaining) {
        while (snackbar.showSnackbar(text, getString(Res.string.group_edit_undo), true, duration) ==
            SnackbarResult.ActionPerformed
        ) {
            val result = suspendRunCatching { undo(done.token) }.getOrNull()
            if (result is Outcome.Success) return@withTimeoutOrNull
            when ((result as? Outcome.Failure)?.error) {
                is GroupError.NameTaken -> {
                    text = getString(Res.string.group_edit_undo_name_taken, done.name)
                    duration = SnackbarDuration.Indefinite
                }

                GroupError.UndoExpired -> {
                    snackbar.showSnackbar(getString(Res.string.group_edit_undo_expired))
                    return@withTimeoutOrNull
                }

                else -> {
                    snackbar.showSnackbar(getString(Res.string.write_failed))
                    return@withTimeoutOrNull
                }
            }
        }
    }
}
