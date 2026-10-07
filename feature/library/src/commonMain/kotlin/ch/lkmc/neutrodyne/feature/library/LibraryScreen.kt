// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import ch.lkmc.neutrodyne.core.ui.CoverTile
import ch.lkmc.neutrodyne.core.ui.EmptyState
import ch.lkmc.neutrodyne.core.ui.OfflineBanner
import ch.lkmc.neutrodyne.core.ui.TileMenuAction
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.add_title
import ch.lkmc.neutrodyne.core.ui.resources.feeds_mark_all_played
import ch.lkmc.neutrodyne.core.ui.resources.feeds_refresh
import ch.lkmc.neutrodyne.core.ui.resources.library_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.library_empty_title
import ch.lkmc.neutrodyne.core.ui.resources.library_show_titles
import ch.lkmc.neutrodyne.core.ui.resources.library_sort
import ch.lkmc.neutrodyne.core.ui.resources.library_sort_added
import ch.lkmc.neutrodyne.core.ui.resources.library_sort_recent
import ch.lkmc.neutrodyne.core.ui.resources.library_sort_title
import ch.lkmc.neutrodyne.core.ui.resources.library_sort_unplayed
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.podcast_settings
import ch.lkmc.neutrodyne.core.ui.resources.podcast_unsubscribe
import ch.lkmc.neutrodyne.core.ui.resources.podcast_unsubscribe_downloads
import ch.lkmc.neutrodyne.core.ui.resources.podcast_unsubscribe_title
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val SKELETON_TILES = 12

/** A pending unsubscribe confirmation: the tile plus its downloaded-episode count. */
internal data class PendingUnsubscribe(
    val tile: LibraryTile,
    val downloads: Int,
)

/**
 * The Library cover grid (08 Library, M1a): `GridCells.Adaptive(100.dp)` — the density setting
 * arrives with M10 — 16 dp edge padding, 12 dp spacing, sort and titles from `appearance.*`.
 * Group chips, the Podcasts|Groups segmented view and selection mode are M2.
 */
@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    pendingUnsubscribe: PendingUnsubscribe?,
    onSort: (LibrarySort) -> Unit,
    onToggleTitles: (Boolean) -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onTileAction: (podcastId: Long, action: TileAction) -> Unit,
    onConfirmUnsubscribe: (LibraryTile) -> Unit,
    onDismissUnsubscribe: () -> Unit,
    onAddPodcast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_library),
            actions = {
                SortMenu(state.sort, onSort)
                OverflowMenu(state.showTitles, onToggleTitles)
                SettingsGearButton()
            },
        )
        OfflineBanner(state.offline)
        when {
            !state.loaded -> {
                SkeletonGrid()
            }

            state.tiles.isEmpty() -> {
                EmptyState(
                    icon = NdIcons.GridView,
                    title = stringResource(Res.string.library_empty_title),
                    body = stringResource(Res.string.library_empty_body),
                    actionLabel = stringResource(Res.string.add_title),
                    onAction = onAddPodcast,
                )
            }

            else -> {
                TileGrid(state, onOpenPodcast, onTileAction)
            }
        }
    }

    pendingUnsubscribe?.let { pending ->
        NdDialog(
            onDismissRequest = onDismissUnsubscribe,
            icon = NdIcons.Error,
            title = stringResource(Res.string.podcast_unsubscribe_title, pending.tile.displayTitle),
            text =
                pluralStringResource(
                    Res.plurals.podcast_unsubscribe_downloads,
                    pending.downloads,
                    pending.downloads,
                ),
            confirm =
                NdDialogAction(stringResource(Res.string.podcast_unsubscribe)) {
                    onConfirmUnsubscribe(pending.tile)
                },
            dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onDismissUnsubscribe),
        )
    }
}

/** 08's tile actions at M1a: settings, refresh, mark-played, unsubscribe (with the dialog). */
internal enum class TileAction { SETTINGS, REFRESH, MARK_PLAYED, UNSUBSCRIBE }

@Composable
private fun tileActions(): List<Pair<TileAction, String>> =
    listOf(
        TileAction.SETTINGS to stringResource(Res.string.podcast_settings),
        TileAction.REFRESH to stringResource(Res.string.feeds_refresh),
        TileAction.MARK_PLAYED to stringResource(Res.string.feeds_mark_all_played),
        TileAction.UNSUBSCRIBE to stringResource(Res.string.podcast_unsubscribe),
    )

@Composable
private fun TileGrid(
    state: LibraryUiState,
    onOpenPodcast: (Long) -> Unit,
    onTileAction: (podcastId: Long, action: TileAction) -> Unit,
) {
    val actions = tileActions()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = CELL_MIN),
        contentPadding = PaddingValues(GRID_PADDING),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.tiles, key = { it.podcastId }, contentType = { "tile" }) { tile ->
            CoverTile(
                tile = tile,
                showTitle = state.showTitles,
                selected = null,
                menuActions =
                    actions.map { (action, label) ->
                        TileMenuAction(label) { onTileAction(tile.podcastId, action) }
                    },
                onClick = { onOpenPodcast(tile.podcastId) },
                onLongClick = {},
            )
        }
    }
}

@Composable
private fun SortMenu(
    sort: LibrarySort,
    onSort: (LibrarySort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        NdTooltipIconButton(
            onClick = { open = true },
            icon = NdIcons.Sort,
            tooltip = stringResource(Res.string.library_sort),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in SORT_ORDER) {
                DropdownMenuItem(
                    text = { Text(sortLabel(option)) },
                    leadingIcon = {
                        if (option == sort) {
                            androidx.compose.material3.Icon(NdIcons.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun sortLabel(sort: LibrarySort): String =
    stringResource(
        when (sort) {
            LibrarySort.TITLE -> Res.string.library_sort_title
            LibrarySort.RECENTLY_UPDATED -> Res.string.library_sort_recent
            LibrarySort.MOST_UNPLAYED -> Res.string.library_sort_unplayed
            LibrarySort.RECENTLY_ADDED -> Res.string.library_sort_added
        },
    )

@Composable
private fun OverflowMenu(
    showTitles: Boolean,
    onToggleTitles: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        NdIconButton(
            onClick = { open = true },
            icon = NdIcons.MoreVert,
            contentDescription = stringResource(Res.string.action_more),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_show_titles)) },
                leadingIcon = {
                    if (showTitles) {
                        androidx.compose.material3.Icon(NdIcons.Check, contentDescription = null)
                    }
                },
                onClick = {
                    open = false
                    onToggleTitles(!showTitles)
                },
            )
        }
    }
}

/** 12 `surfaceContainer` placeholders of the loading state (08 Library states). */
@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = CELL_MIN),
        contentPadding = PaddingValues(GRID_PADDING),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(SKELETON_TILES) {
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(NeutrodyneShapes.Tile)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )
        }
    }
}

private val SORT_ORDER =
    listOf(
        LibrarySort.TITLE,
        LibrarySort.RECENTLY_UPDATED,
        LibrarySort.MOST_UNPLAYED,
        LibrarySort.RECENTLY_ADDED,
    )
private val CELL_MIN = 100.dp
private val GRID_PADDING = 16.dp
private val GRID_SPACING = 12.dp
