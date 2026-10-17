// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.AddToGroupsKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.membership_updated
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString

@Composable
internal fun AddToGroupsRoute(key: AddToGroupsKey) {
    val viewModel =
        assistedMetroViewModel<AddToGroupsViewModel, AddToGroupsViewModel.Factory> { create(key.podcastIds) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current
    ShowUserMessages(messages, viewModel::acknowledgeMessage)
    LaunchedEffect(state.doneCount) {
        val count = viewModel.consumeDone() ?: return@LaunchedEffect
        // Popping disposes this entry; the root snackbar must survive that disposal.
        withContext(NonCancellable) {
            navigator.pop(key)
            snackbar.showSnackbar(getPluralString(Res.plurals.membership_updated, count, count))
        }
    }
    AddToGroupsSheet(state, viewModel::toggle, viewModel::editName, viewModel::createGroup, viewModel::done, {
        // Entry-scoped (#152): completion and dismissal may both fire while the sheet animates out.
        navigator.pop(key)
    })
}
