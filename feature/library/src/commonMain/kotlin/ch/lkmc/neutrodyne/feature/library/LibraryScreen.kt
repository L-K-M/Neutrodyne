// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.settings.LibrarySegment
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
import ch.lkmc.neutrodyne.core.ui.resources.export_subscriptions
import ch.lkmc.neutrodyne.core.ui.resources.feeds_add_url
import ch.lkmc.neutrodyne.core.ui.resources.feeds_import
import ch.lkmc.neutrodyne.core.ui.resources.feeds_mark_all_played
import ch.lkmc.neutrodyne.core.ui.resources.feeds_onboarding_title
import ch.lkmc.neutrodyne.core.ui.resources.feeds_refresh
import ch.lkmc.neutrodyne.core.ui.resources.feeds_search
import ch.lkmc.neutrodyne.core.ui.resources.library_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.library_show_titles
import ch.lkmc.neutrodyne.core.ui.resources.library_sort
import ch.lkmc.neutrodyne.core.ui.resources.library_sort_added
import ch.lkmc.neutrodyne.core.ui.resources.library_sort_recent
import ch.lkmc.neutrodyne.core.ui.resources.library_sort_title
import ch.lkmc.neutrodyne.core.ui.resources.library_sort_unplayed
import ch.lkmc.neutrodyne.core.ui.resources.membership_add_to
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.podcast_mark_all_title
import ch.lkmc.neutrodyne.core.ui.resources.podcast_mark_played
import ch.lkmc.neutrodyne.core.ui.resources.podcast_settings
import ch.lkmc.neutrodyne.core.ui.resources.podcast_unsubscribe
import ch.lkmc.neutrodyne.core.ui.resources.podcast_unsubscribe_downloads
import ch.lkmc.neutrodyne.core.ui.resources.podcast_unsubscribe_title
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import ch.lkmc.neutrodyne.feature.library.resources.add_podcasts
import ch.lkmc.neutrodyne.feature.library.resources.empty_group
import ch.lkmc.neutrodyne.feature.library.resources.manage_groups
import ch.lkmc.neutrodyne.feature.library.resources.mark_many
import ch.lkmc.neutrodyne.feature.library.resources.select
import ch.lkmc.neutrodyne.feature.library.resources.ungrouped
import ch.lkmc.neutrodyne.feature.library.resources.unsubscribe_many
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.feature.library.resources.Res as LibraryRes

private const val SKELETON_TILES = 12

internal data class PendingSelectionAction(
    val action: SelectionAction,
    val ids: List<Long>,
    val count: Int,
)

/** A pending unsubscribe confirmation: the tile plus its downloaded-episode count. */
internal data class PendingUnsubscribe(
    val tile: LibraryTile,
    val downloads: Int,
)

/**
 * The Library cover grid (08 Library, M1a): `GridCells.Adaptive(100.dp)` — the density setting
 * arrives with M10 — 16 dp edge padding, 12 dp spacing, sort and titles from `appearance.*`.
 * M2 adds group filters, the mosaic segment and selection actions.
 */
@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    pendingUnsubscribe: PendingUnsubscribe?,
    pendingMarkAll: LibraryTile?,
    onSort: (LibrarySort) -> Unit,
    onToggleTitles: (Boolean) -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onTileAction: (podcastId: Long, action: TileAction) -> Unit,
    onConfirmUnsubscribe: (LibraryTile) -> Unit,
    onDismissUnsubscribe: () -> Unit,
    onConfirmMarkAll: (Long) -> Unit,
    onDismissMarkAll: () -> Unit,
    onAddPodcast: () -> Unit,
    onSearchPodcasts: () -> Unit,
    modifier: Modifier = Modifier,
    outageBanner: @Composable () -> Unit = {},
    segment: LibrarySegment = state.segment,
    selectedIds: List<Long> = emptyList(),
    pendingSelection: PendingSelectionAction? = null,
    onSegment: (LibrarySegment) -> Unit = {},
    onGroupFilter: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onOpenGroup: (Long) -> Unit = {},
    onEditGroup: (Long) -> Unit = {},
    onManageGroups: () -> Unit = {},
    onExportGroup: (Long) -> Unit = {},
    onExportSubscriptions: () -> Unit = {},
    onImportSubscriptions: () -> Unit = {},
    onSelect: (Long, SelectionGesture) -> Unit = { _, _ -> },
    onExitSelection: () -> Unit = {},
    onSelectionAction: (SelectionAction) -> Unit = {},
    onConfirmSelection: () -> Unit = {},
    onDismissSelection: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(selectedIds.isNotEmpty()) {
        if (selectedIds.isNotEmpty()) focus.requestFocus()
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (selectedIds.isEmpty() || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    when {
                        event.key == Key.Delete -> {
                            onSelectionAction(SelectionAction.UNSUBSCRIBE)
                            true
                        }

                        event.key == Key.Escape -> {
                            onExitSelection()
                            true
                        }

                        event.key == Key.A && (event.isCtrlPressed || event.isMetaPressed) -> {
                            onSelectionAction(SelectionAction.SELECT_ALL)
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }.focusRequester(focus)
                .focusable(enabled = selectedIds.isNotEmpty()),
    ) {
        outageBanner()
        if (selectedIds.isNotEmpty()) {
            SelectionTopBar(selectedIds.size, onExitSelection, onSelectionAction)
        } else {
            NdTopAppBar(
                title = stringResource(Res.string.nav_library),
                actions = {
                    SortMenu(state.sort, onSort)
                    OverflowMenu(
                        state.showTitles,
                        onToggleTitles,
                        onManageGroups,
                        onExportSubscriptions,
                        onImportSubscriptions,
                    )
                    SettingsGearButton()
                },
            )
        }
        OfflineBanner(state.offline)
        LibrarySegments(segment, onSegment)
        if (segment == LibrarySegment.PODCASTS) LibraryGroupChips(state, onGroupFilter, onNewGroup)
        when {
            segment == LibrarySegment.GROUPS && state.loaded -> {
                LibraryGroupsGrid(state, onOpenGroup, onEditGroup, onManageGroups, onNewGroup, onExportGroup)
            }

            !state.loaded -> {
                SkeletonGrid()
            }

            state.tiles.isEmpty() && state.groupFilter.isNotEmpty() -> {
                val name =
                    state.groups.firstOrNull { it.uuid == state.groupFilter }?.name
                        ?: stringResource(LibraryRes.string.ungrouped)
                EmptyState(
                    icon = NdIcons.GridView,
                    title = stringResource(LibraryRes.string.empty_group, name),
                    body = "",
                    actionLabel = stringResource(LibraryRes.string.add_podcasts),
                    onAction = onAddPodcast,
                )
            }

            state.tiles.isEmpty() -> {
                EmptyState(
                    icon = NdIcons.GridView,
                    title = stringResource(Res.string.feeds_onboarding_title),
                    body = stringResource(Res.string.library_empty_body),
                    actionLabel = stringResource(Res.string.feeds_search),
                    onAction = onSearchPodcasts,
                    secondaryLabel = stringResource(Res.string.feeds_import),
                    onSecondary = onImportSubscriptions,
                    tertiaryLabel = stringResource(Res.string.feeds_add_url),
                    onTertiary = onAddPodcast,
                )
            }

            else -> {
                TileGrid(state, selectedIds, onOpenPodcast, onTileAction, onSelect)
            }
        }
    }

    pendingSelection?.let { pending ->
        val unsubscribe = pending.action == SelectionAction.UNSUBSCRIBE
        NdDialog(
            onDismissRequest = onDismissSelection,
            icon = if (unsubscribe) NdIcons.Error else NdIcons.DoneAll,
            title =
                pluralStringResource(
                    if (unsubscribe) LibraryRes.plurals.unsubscribe_many else LibraryRes.plurals.mark_many,
                    pending.ids.size,
                    pending.ids.size,
                ),
            text =
                if (unsubscribe) {
                    pluralStringResource(
                        Res.plurals.podcast_unsubscribe_downloads,
                        pending.count,
                        pending.count,
                    )
                } else {
                    null
                },
            confirm =
                NdDialogAction(
                    stringResource(if (unsubscribe) Res.string.podcast_unsubscribe else Res.string.podcast_mark_played),
                    role = if (unsubscribe) NdActionRole.DESTRUCTIVE else NdActionRole.NORMAL,
                    onClick = onConfirmSelection,
                ),
            dismiss =
                NdDialogAction(
                    stringResource(Res.string.action_cancel),
                    onClick = onDismissSelection,
                ),
        )
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
                NdDialogAction(
                    stringResource(Res.string.podcast_unsubscribe),
                    role = NdActionRole.DESTRUCTIVE,
                ) {
                    onConfirmUnsubscribe(pending.tile)
                },
            dismiss =
                NdDialogAction(
                    stringResource(Res.string.action_cancel),
                    onClick = onDismissUnsubscribe,
                ),
        )
    }

    // 08's required confirmation before the write (UI review round 2); the "Older than"
    // options of the dialog table wait on M2's `countUnplayed` (deviation recorded).
    pendingMarkAll?.let { pending ->
        NdDialog(
            onDismissRequest = onDismissMarkAll,
            icon = NdIcons.DoneAll,
            title = stringResource(Res.string.podcast_mark_all_title, pending.displayTitle),
            confirm =
                NdDialogAction(stringResource(Res.string.podcast_mark_played)) {
                    onConfirmMarkAll(pending.podcastId)
                },
            dismiss =
                NdDialogAction(
                    stringResource(Res.string.action_cancel),
                    onClick = onDismissMarkAll,
                ),
        )
    }
}

/** 08's tile actions at M1a: settings, refresh, mark-played, unsubscribe (with the dialog). */
internal enum class TileAction { SETTINGS, REFRESH, MARK_PLAYED, UNSUBSCRIBE, SELECT, ADD_TO_GROUP }

// 08's context-menu catalogue order with the destructive item last. A separator between
// "Mark all as played…" and "Unsubscribe" needs a menu-model marker `TileMenuAction` does
// not carry; recorded in 08's M1a deviations until `core:ui` grows one.
@Composable
private fun tileActions(): List<Pair<TileAction, String>> =
    listOf(
        TileAction.SELECT to stringResource(LibraryRes.string.select),
        TileAction.ADD_TO_GROUP to stringResource(Res.string.membership_add_to),
        TileAction.REFRESH to stringResource(Res.string.feeds_refresh),
        TileAction.SETTINGS to stringResource(Res.string.podcast_settings),
        TileAction.MARK_PLAYED to stringResource(Res.string.feeds_mark_all_played),
        TileAction.UNSUBSCRIBE to stringResource(Res.string.podcast_unsubscribe),
    )

@Composable
private fun TileGrid(
    state: LibraryUiState,
    selectedIds: List<Long>,
    onOpenPodcast: (Long) -> Unit,
    onTileAction: (podcastId: Long, action: TileAction) -> Unit,
    onSelect: (Long, SelectionGesture) -> Unit,
) {
    val actions = tileActions()
    val grid = rememberLazyGridState()
    val scrollbars = LocalScrollbars.current
    val scrollbarColor = ndScrollbarColor(scrollbars)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = CELL_MIN),
        state = grid,
        contentPadding = PaddingValues(GRID_PADDING),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
        modifier = Modifier.fillMaxSize().ndScrollbar(grid, scrollbars, scrollbarColor),
    ) {
        items(state.tiles, key = { it.podcastId }, contentType = { "tile" }) { tile ->
            var gesture by remember { mutableStateOf(SelectionGesture.OPEN) }
            CoverTile(
                tile = tile,
                showTitle = state.showTitles,
                selected = if (selectedIds.isEmpty()) null else tile.podcastId in selectedIds,
                modifier =
                    Modifier.libraryClickModifiers { gesture = it }.semantics {
                        if (selectedIds.isNotEmpty()) selected = tile.podcastId in selectedIds
                    },
                menuActions =
                    actions.map { (action, label) ->
                        TileMenuAction(label) { onTileAction(tile.podcastId, action) }
                    },
                onClick = {
                    if (selectedIds.isEmpty() && gesture == SelectionGesture.OPEN) {
                        onOpenPodcast(tile.podcastId)
                    } else {
                        onSelect(tile.podcastId, gesture)
                    }
                    gesture = SelectionGesture.OPEN
                },
                onLongClick = { onSelect(tile.podcastId, SelectionGesture.TOGGLE) },
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
                    // The check icon is decorative; the radio role plus `selected` is what
                    // screen readers announce (94 review).
                    modifier =
                        Modifier.semantics {
                            selected = option == sort
                            role = Role.RadioButton
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
    onManageGroups: () -> Unit,
    onExportSubscriptions: () -> Unit,
    onImportSubscriptions: () -> Unit,
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
                // Same gap as the sort menu: the check icon alone is decorative, so the
                // item reports a switch role with its toggleable state (94 review).
                modifier =
                    Modifier.semantics {
                        toggleableState = ToggleableState(showTitles)
                        role = Role.Switch
                    },
            )
            DropdownMenuItem(text = { Text(stringResource(LibraryRes.string.manage_groups)) }, onClick = {
                open = false
                onManageGroups()
            })
            DropdownMenuItem(text = { Text(stringResource(Res.string.feeds_import)) }, onClick = {
                open = false
                onImportSubscriptions()
            })
            DropdownMenuItem(text = { Text(stringResource(Res.string.export_subscriptions)) }, onClick = {
                open = false
                onExportSubscriptions()
            })
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
internal val CELL_MIN = 100.dp
internal val GRID_PADDING = 16.dp
internal val GRID_SPACING = 12.dp
