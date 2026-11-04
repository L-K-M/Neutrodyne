// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.GroupSettingsKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.SuggestedGroupsCard
import ch.lkmc.neutrodyne.core.ui.SuggestedGroupsFeedback
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionNaming")
internal fun GroupsManageRoute(viewModel: GroupsManageViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestedGroups.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current
    LaunchedEffect(state.navigation) {
        viewModel.consumeNavigation()?.let(navigator::pushDetail)
    }
    // Keep the pending deletion latched until Undo ends; another token must not expire in the host queue.
    LaunchedEffect(viewModel, snackbar) {
        viewModel.uiState.map { it.deleted }.filterNotNull().collect { deleted ->
            try {
                withContext(NonCancellable) { showDeleteUndo(snackbar, deleted, viewModel::undoDelete) }
            } finally {
                viewModel.consumeDeleted()
            }
        }
    }
    GroupsManageScreen(
        state,
        viewModel::startDrag,
        viewModel::dragOver,
        viewModel::drop,
        viewModel::cancelDrag,
        viewModel::move,
        viewModel::showAsTab,
        viewModel::openEditor,
        { viewModel.openEditor() },
        viewModel::delete,
        { navigator.pop() },
        viewModel::retry,
        suggestedGroups = {
            SuggestedGroupsCard(
                suggestions.suggestions,
                suggestions.selected,
                suggestions.busy,
                viewModel.suggestedGroups::toggle,
                viewModel.suggestedGroups::create,
                viewModel.suggestedGroups::dismiss,
            )
        },
        topBarActions = { SettingsGearButton() },
        onSettings = { navigator.pushDetail(GroupSettingsKey(it)) },
    )
    suggestions.result?.let { result ->
        SuggestedGroupsFeedback(
            result.created,
            result.failed,
            result.firstGroupUuid?.let { uuid ->
                { scope.launch { if (viewModel.openSuggested(uuid)) navigator.selectTab(FeedsKey) } }
            },
            viewModel.suggestedGroups::consumeResult,
        )
    }
    ShowUserMessages(state.messages, viewModel::onMessageShown)
}
