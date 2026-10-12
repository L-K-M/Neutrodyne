// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.dispatchEpisodeRoute
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * The Feeds destination (08 Feeds): Metro resolves the [FeedsViewModel] per nav entry;
 * [dispatchEpisodeRoute] handles the navigation and external-URL actions, the rest go to the
 * ViewModel's repository calls.
 */
@Composable
internal fun FeedsRoute(viewModel: FeedsViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val items = viewModel.feed.collectAsLazyPagingItems()
    val navigator = LocalAppNavigator.current
    val urls = LocalPlatformActions.current.urls

    FeedsScreen(
        state = state,
        items = items,
        onRefresh = viewModel::onRefresh,
        onFiltersChange = viewModel::onFiltersChange,
        onAction = { action ->
            if (!dispatchEpisodeRoute(action, navigator, urls)) viewModel.onRowAction(action)
        },
        onMarkAllPlayedClick = viewModel::markAllPlayed,
        onAddPodcast = { navigator.push(AddPodcastKey(null)) },
        onSearch = { navigator.selectTab(DiscoverKey) },
    )

    ShowUserMessages(state.messages, viewModel::onMessageShown)
}
