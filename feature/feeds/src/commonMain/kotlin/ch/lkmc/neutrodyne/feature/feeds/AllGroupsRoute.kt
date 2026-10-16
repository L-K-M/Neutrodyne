// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.AllGroupsKey
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.GroupsManageKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import dev.zacsweers.metrox.viewmodel.metroViewModel

/** Content of the navigation host's window-backed NdModalBottomSheet. */
@Composable
internal fun AllGroupsRoute(viewModel: AllGroupsViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    LaunchedEffect(state.selected) {
        if (viewModel.consumeSelection()) navigator.pop(AllGroupsKey)
    }

    AllGroupsSheet(
        state = state,
        onSelectGroup = viewModel::selectGroup,
        onNewGroup = {
            if (navigator.pop(AllGroupsKey)) navigator.push(GroupEditKey(null))
        },
        onManageGroups = {
            if (navigator.pop(AllGroupsKey)) navigator.push(GroupsManageKey)
        },
        onDismiss = { navigator.pop(AllGroupsKey) },
    )
}
