// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.EnamelSelector
import ch.lkmc.neutrodyne.core.designsystem.radio.LocalRadioFaceplate
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKeys
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.settings.LibrarySegment
import ch.lkmc.neutrodyne.core.ui.GroupDrawer
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.NewGroupDrawer
import ch.lkmc.neutrodyne.core.ui.resources.group_counts_join
import ch.lkmc.neutrodyne.core.ui.resources.group_export_opml
import ch.lkmc.neutrodyne.core.ui.resources.group_share_opml
import ch.lkmc.neutrodyne.core.ui.resources.group_unplayed_fmt
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import ch.lkmc.neutrodyne.feature.library.resources.Res
import ch.lkmc.neutrodyne.feature.library.resources.add_to_group
import ch.lkmc.neutrodyne.feature.library.resources.all_podcasts
import ch.lkmc.neutrodyne.feature.library.resources.edit_group
import ch.lkmc.neutrodyne.feature.library.resources.exit_selection
import ch.lkmc.neutrodyne.feature.library.resources.group_actions
import ch.lkmc.neutrodyne.feature.library.resources.groups
import ch.lkmc.neutrodyne.feature.library.resources.manage_groups
import ch.lkmc.neutrodyne.feature.library.resources.mark_played
import ch.lkmc.neutrodyne.feature.library.resources.new_group
import ch.lkmc.neutrodyne.feature.library.resources.podcasts
import ch.lkmc.neutrodyne.feature.library.resources.refresh
import ch.lkmc.neutrodyne.feature.library.resources.select_all
import ch.lkmc.neutrodyne.feature.library.resources.selected_count
import ch.lkmc.neutrodyne.feature.library.resources.selection_actions
import ch.lkmc.neutrodyne.feature.library.resources.ungrouped
import ch.lkmc.neutrodyne.feature.library.resources.unsubscribe
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

internal enum class SelectionGesture { OPEN, TOGGLE, RANGE }

internal enum class SelectionAction { ADD_TO_GROUP, UNSUBSCRIBE, REFRESH, MARK_PLAYED, SELECT_ALL }

@Composable
internal fun LibrarySegments(
    segment: LibrarySegment,
    onSegment: (LibrarySegment) -> Unit,
) {
    PianoKeys(
        options = LibrarySegment.entries,
        selected = segment,
        onSelect = onSegment,
        label = { stringResource(if (it == LibrarySegment.PODCASTS) Res.string.podcasts else Res.string.groups) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = GRID_PADDING, vertical = 4.dp),
        tag = { "library_segment_${it.name.lowercase()}" },
    )
}

/**
 * The podcasts filter as one enamel selector (PO-55), replacing the chip row: All podcasts,
 * Ungrouped, each group and New group in its menu, with the filter's podcast and unplayed counts
 * beside it.
 */
@Composable
internal fun LibraryFilterStrip(
    state: LibraryUiState,
    onFilter: (String) -> Unit,
    onNewGroup: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val allLabel = stringResource(Res.string.all_podcasts)
    val ungroupedLabel = stringResource(Res.string.ungrouped)
    val current =
        when (state.groupFilter) {
            "" -> allLabel
            UNGROUPED_FILTER -> ungroupedLabel
            else -> state.groups.firstOrNull { it.uuid == state.groupFilter }?.name ?: allLabel
        }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GRID_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The selector is measured first and the counts take what is left, so at large text sizes
        // the counts end in an ellipsis instead of squeezing the filter out.
        Box {
            EnamelSelector(current, { open = true }, Modifier.testTag("library_filter"))
            DropdownMenu(open, { open = false }) {
                FilterItem(allLabel, state.groupFilter.isEmpty(), "library_filter_all") {
                    open = false
                    onFilter("")
                }
                FilterItem(ungroupedLabel, state.groupFilter == UNGROUPED_FILTER, "library_filter_ungrouped") {
                    open = false
                    onFilter(UNGROUPED_FILTER)
                }
                state.groups.forEach { group ->
                    FilterItem(group.name, state.groupFilter == group.uuid, "library_filter_${group.uuid}") {
                        open = false
                        onFilter(group.uuid)
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.new_group)) },
                    leadingIcon = { Icon(NdIcons.Add, contentDescription = null) },
                    onClick = {
                        open = false
                        onNewGroup()
                    },
                )
            }
        }
        if (state.loaded && state.tiles.isNotEmpty()) {
            val podcasts = pluralStringResource(UiRes.plurals.podcast_count, state.tiles.size, state.tiles.size)
            val unplayed = state.tiles.sumOf { it.unplayedCount }
            Text(
                if (unplayed > 0) {
                    stringResource(
                        UiRes.string.group_counts_join,
                        podcasts,
                        stringResource(UiRes.string.group_unplayed_fmt, unplayed),
                    )
                } else {
                    podcasts
                },
                Modifier.weight(1f, fill = false),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One filter choice; the radio role and `selected` tell screen readers which one is active. */
@Composable
private fun FilterItem(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { if (selected) Icon(NdIcons.Check, contentDescription = null) },
        onClick = onClick,
        modifier =
            Modifier.testTag(tag).semantics {
                this.selected = selected
                role = Role.RadioButton
            },
    )
}

@Composable
internal fun LibraryGroupsGrid(
    state: LibraryUiState,
    onOpenGroup: (Long) -> Unit,
    onEditGroup: (Long) -> Unit,
    onManageGroups: () -> Unit,
    onNewGroup: () -> Unit,
    onExportGroup: (Long) -> Unit,
) {
    val grid = rememberLazyGridState()
    val scrollbars = LocalScrollbars.current
    val scrollbarColor = ndScrollbarColor(scrollbars)
    val editLabel = stringResource(Res.string.edit_group)
    val manageLabel = stringResource(Res.string.manage_groups)
    // 05 Group actions: "Share as OPML" on Android, "Export group as OPML…" on the desktop.
    val exportLabel =
        stringResource(
            if (LocalPlatformKind.current == PlatformKind.ANDROID) {
                UiRes.string.group_share_opml
            } else {
                UiRes.string.group_export_opml
            },
        )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(DRAWER_MIN_WIDTH),
        state = grid,
        contentPadding = PaddingValues(GRID_PADDING),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
        modifier = Modifier.fillMaxSize().ndScrollbar(grid, scrollbars, scrollbarColor),
    ) {
        items(state.groups, key = { it.id }) { group ->
            var menuOpen by remember(group.id) { mutableStateOf(false) }
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            var focused by remember { mutableStateOf(false) }
            Box {
                GroupDrawer(
                    group = group,
                    members = state.mosaics[group.id].orEmpty(),
                    unplayedCount = state.groupCounts[group.id]?.unplayed,
                    newCount = state.groupCounts[group.id]?.newSinceVisit,
                    modifier =
                        Modifier
                            .hoverable(interaction)
                            .onFocusChanged { focused = it.isFocused }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed))
                                ) {
                                    menuOpen = true
                                    true
                                } else {
                                    false
                                }
                            }.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                            menuOpen = true
                                        }
                                    }
                                }
                            }.semantics {
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction(editLabel) {
                                            onEditGroup(group.id)
                                            true
                                        },
                                        CustomAccessibilityAction(manageLabel) {
                                            onManageGroups()
                                            true
                                        },
                                        CustomAccessibilityAction(exportLabel) {
                                            onExportGroup(group.id)
                                            true
                                        },
                                    )
                            }.combinedClickable(onClick = { onOpenGroup(group.id) }, onLongClick = { menuOpen = true }),
                )
                if (hovered || focused || menuOpen) {
                    // An ivory key on the drawer front, like the faceplate keys.
                    CompositionLocalProvider(LocalRadioFaceplate provides true) {
                        NdIconButton(
                            { menuOpen = true },
                            NdIcons.MoreVert,
                            stringResource(Res.string.group_actions),
                            Modifier.align(Alignment.TopEnd).padding(4.dp),
                        )
                    }
                }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(editLabel) }, onClick = {
                        menuOpen = false
                        onEditGroup(group.id)
                    })
                    DropdownMenuItem(text = { Text(manageLabel) }, onClick = {
                        menuOpen = false
                        onManageGroups()
                    })
                    DropdownMenuItem(text = { Text(exportLabel) }, onClick = {
                        menuOpen = false
                        onExportGroup(group.id)
                    })
                }
            }
        }
        item(key = "new") {
            NewGroupDrawer(
                stringResource(Res.string.new_group),
                Modifier.combinedClickable(role = Role.Button, onClick = onNewGroup),
            )
        }
    }
}

@Composable
internal fun SelectionTopBar(
    count: Int,
    onExit: () -> Unit,
    onAction: (SelectionAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    NdTopAppBar(
        title = stringResource(Res.string.selected_count, count),
        navigation = {
            NdIconButton(onExit, NdIcons.Close, stringResource(Res.string.exit_selection))
        },
        actions = {
            NdIconButton(
                { onAction(SelectionAction.ADD_TO_GROUP) },
                NdIcons.Add,
                stringResource(Res.string.add_to_group),
            )
            NdIconButton(
                { onAction(SelectionAction.UNSUBSCRIBE) },
                NdIcons.Error,
                stringResource(Res.string.unsubscribe),
            )
            Box {
                NdIconButton({ menuOpen = true }, NdIcons.MoreVert, stringResource(Res.string.selection_actions))
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    listOf(
                        SelectionAction.REFRESH to Res.string.refresh,
                        SelectionAction.MARK_PLAYED to Res.string.mark_played,
                        SelectionAction.SELECT_ALL to Res.string.select_all,
                    ).forEach { (action, label) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                            menuOpen = false
                            onAction(action)
                        })
                    }
                }
            }
        },
    )
}

/** Read modifiers on press without stealing the tile's click, drag or long-press gesture. */
@Composable
internal fun Modifier.libraryClickModifiers(onGesture: (SelectionGesture) -> Unit): Modifier {
    val latest by rememberUpdatedState(onGesture)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) {
                    latest(
                        when {
                            event.keyboardModifiers.isShiftPressed -> {
                                SelectionGesture.RANGE
                            }

                            event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed -> {
                                SelectionGesture.TOGGLE
                            }

                            else -> {
                                SelectionGesture.OPEN
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Shift extends from the last toggled item, following the visible, sorted tile order. */
internal fun extendSelection(
    selected: List<Long>,
    anchor: Long?,
    id: Long,
    visible: List<Long>,
): List<Long> {
    val start = visible.indexOf(anchor)
    val end = visible.indexOf(id)
    if (start < 0 || end < 0) return (selected + id).distinct()

    return (selected + visible.subList(minOf(start, end), maxOf(start, end) + 1)).distinct()
}

private val DRAWER_MIN_WIDTH = 300.dp
