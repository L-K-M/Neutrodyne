// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch

/**
 * The Library destination (08 Library): the [LibraryViewModel] holds tiles/sort/titles; the tile
 * menu's "Podcast settings" navigates at the route, "Unsubscribe…" opens the confirmation with
 * the downloaded-episode count the dialog names.
 */
@Composable
internal fun LibraryRoute() {
    val viewModel = metroViewModel<LibraryViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingUnsubscribe?>(null) }

    LibraryScreen(
        state = state,
        pendingUnsubscribe = pending,
        onSort = viewModel::setSort,
        onToggleTitles = viewModel::setShowTitles,
        onOpenPodcast = { navigator.pushDetail(PodcastKey(it)) },
        onTileAction = { podcastId, action ->
            when (action) {
                TileAction.SETTINGS -> navigator.pushDetail(PodcastSettingsKey(podcastId))
                TileAction.REFRESH -> viewModel.refreshPodcast(podcastId)
                TileAction.MARK_PLAYED -> viewModel.markAllPlayed(podcastId)
                TileAction.UNSUBSCRIBE ->
                    scope.launch {
                        val tile = state.tiles.firstOrNull { it.podcastId == podcastId } ?: return@launch
                        pending = PendingUnsubscribe(tile, viewModel.downloadedCount(podcastId))
                    }
            }
        },
        onConfirmUnsubscribe = {
            pending = null
            viewModel.unsubscribe(it.podcastId)
        },
        onDismissUnsubscribe = { pending = null },
        onAddPodcast = { navigator.push(AddPodcastKey(null)) },
    )
}
