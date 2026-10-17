// SPDX-License-Identifier: Unlicense

@file:Suppress("FunctionNaming") // Compose functions use PascalCase.

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.GroupSettingsKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel

@Composable
internal fun GroupSettingsRoute(
    key: GroupSettingsKey,
    viewModel: GroupSettingsViewModel =
        assistedMetroViewModel<GroupSettingsViewModel, GroupSettingsViewModel.Factory> { create(key.groupId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val requester = LocalPlatformActions.current.notifications
    LaunchedEffect(state.gone) {
        if (state.gone) navigator.pop()
    }
    GroupSettingsScreen(
        state,
        viewModel::setRefreshIntervalMinutes,
        { viewModel.setNotifyNewEpisodes(it, requester) },
        onClose = { navigator.pop() },
    )
    ShowUserMessages(state.messages, viewModel::onMessageShown)
}
