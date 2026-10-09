// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * The Library destination (08 Library): the [LibraryViewModel] holds tiles/sort/titles; the tile
 * menu's "Podcast settings" navigates at the route, "Unsubscribe…" opens the confirmation with
 * the downloaded-episode count the dialog names.
 */
@Composable
internal fun LibraryRoute(viewModel: LibraryViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingUnsubscribe?>(null) }
    var pendingMarkAll by remember { mutableStateOf<Long?>(null) }

    LibraryScreen(
        state = state,
        pendingUnsubscribe = pending,
        pendingMarkAll = pendingMarkAll,
        onSort = viewModel::setSort,
        onToggleTitles = viewModel::setShowTitles,
        onOpenPodcast = { navigator.pushDetail(PodcastKey(it)) },
        onTileAction = { podcastId, action ->
            when (action) {
                TileAction.SETTINGS -> {
                    navigator.pushDetail(PodcastSettingsKey(podcastId))
                }

                TileAction.REFRESH -> {
                    viewModel.refreshPodcast(podcastId)
                }

                TileAction.MARK_PLAYED -> {
                    pendingMarkAll = podcastId
                }

                TileAction.UNSUBSCRIBE -> {
                    scope.launch {
                        val tile = state.tiles.firstOrNull { it.podcastId == podcastId } ?: return@launch
                        // A failed count read must not crash — and the dialog can't show without it.
                        val count =
                            suspendRunCatching { viewModel.downloadedCount(podcastId) }.getOrNull() ?: run {
                                snackbar.showSnackbar(getString(Res.string.write_failed))
                                return@launch
                            }
                        pending = PendingUnsubscribe(tile, count)
                    }
                }
            }
        },
        onConfirmUnsubscribe = {
            pending = null
            viewModel.unsubscribe(it.podcastId)
        },
        onDismissUnsubscribe = { pending = null },
        onConfirmMarkAll = {
            pendingMarkAll = null
            viewModel.markAllPlayed(it)
        },
        onDismissMarkAll = { pendingMarkAll = null },
        onAddPodcast = { navigator.push(AddPodcastKey(null)) },
    )

    ShowUserMessages(state.messages, viewModel::onMessageShown)
}
