// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.dispatchEpisodeRoute
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel

/**
 * The `EpisodeKey` route (08 Episode detail): navigation and external URLs go through
 * [dispatchEpisodeRoute], repository writes land in the ViewModel.
 */
@Composable
internal fun EpisodeRoute(key: EpisodeKey) {
    val viewModel =
        assistedMetroViewModel<EpisodeViewModel, EpisodeViewModel.Factory> {
            create(key.episodeId)
        }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val urls = LocalPlatformActions.current.urls

    EpisodeScreen(
        state = state,
        onAction = { action ->
            if (!dispatchEpisodeRoute(action, navigator, urls)) viewModel.onAction(action)
        },
        onFavorite = viewModel::setFavorite,
    )
}
