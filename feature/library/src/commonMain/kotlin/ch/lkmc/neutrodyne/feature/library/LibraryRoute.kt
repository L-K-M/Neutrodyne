// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.library.resources.read_failed
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import ch.lkmc.neutrodyne.feature.library.resources.Res as LibraryRes

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
    // 08: dialogs keep their pending state across recreation. Only the id and count are
    // saveable primitives; the dialog's PendingUnsubscribe re-derives from the emitted tiles,
    // so a restored dialog can never show a stale display title (94-2).
    var pendingUnsubscribeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingUnsubscribeDownloads by rememberSaveable { mutableIntStateOf(0) }
    var pendingMarkAll by rememberSaveable { mutableStateOf<Long?>(null) }
    val pending =
        state.tiles
            .firstOrNull { it.podcastId == pendingUnsubscribeId }
            ?.let { PendingUnsubscribe(it, pendingUnsubscribeDownloads) }

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
                                snackbar.showSnackbar(getString(LibraryRes.string.read_failed))
                                return@launch
                            }
                        pendingUnsubscribeId = tile.podcastId
                        pendingUnsubscribeDownloads = count
                    }
                }
            }
        },
        onConfirmUnsubscribe = {
            pendingUnsubscribeId = null
            viewModel.unsubscribe(it.podcastId)
        },
        onDismissUnsubscribe = { pendingUnsubscribeId = null },
        onConfirmMarkAll = {
            pendingMarkAll = null
            viewModel.markAllPlayed(it)
        },
        onDismissMarkAll = { pendingMarkAll = null },
        onAddPodcast = { navigator.push(AddPodcastKey(null)) },
        onSearchPodcasts = { navigator.selectTab(DiscoverKey) },
    )

    ShowUserMessages(state.messages, viewModel::onMessageShown)
}
