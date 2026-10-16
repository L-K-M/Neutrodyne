// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.ui.GroupMosaic
import ch.lkmc.neutrodyne.core.ui.GroupMosaicLayout
import ch.lkmc.neutrodyne.core.ui.onSecondaryClick
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_delete
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_new
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_tab
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_title
import ch.lkmc.neutrodyne.core.ui.resources.group_hidden
import ch.lkmc.neutrodyne.core.ui.resources.group_settings_title
import ch.lkmc.neutrodyne.core.ui.resources.groups_manage_down
import ch.lkmc.neutrodyne.core.ui.resources.groups_manage_empty
import ch.lkmc.neutrodyne.core.ui.resources.groups_manage_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.groups_manage_title
import ch.lkmc.neutrodyne.core.ui.resources.groups_manage_top
import ch.lkmc.neutrodyne.core.ui.resources.groups_manage_up
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun GroupsManageScreen(
    state: GroupsManageUiState,
    onStartDrag: (Long) -> Unit,
    onDragOver: (Long) -> Unit,
    onDrop: () -> Unit,
    onCancelDrag: () -> Unit,
    onMove: (Long, GroupMove) -> Unit,
    onShowAsTab: (Long, Boolean) -> Unit,
    onEdit: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (Long) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable () -> Unit = {},
    onSettings: (Long) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { _, to -> onDragOver(to.key as Long) }
    val cancelDrag by rememberUpdatedState(onCancelDrag)
    val drop by rememberUpdatedState(onDrop)
    // The library calls onDragStopped for cancellation and disposal too. Only release commits.
    val detector =
        remember {
            DragGestureDetector { start, end, cancel, drag ->
                detectDragGestures(
                    onDragStart = start,
                    onDragEnd = {
                        end()
                        drop()
                    },
                    onDragCancel = {
                        cancelDrag()
                        cancel()
                    },
                    onDrag = drag,
                )
            }
        }
    DisposableEffect(Unit) { onDispose { cancelDrag() } }
    Column(
        modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.key == Key.Escape) {
                if (it.type == KeyEventType.KeyDown) {
                    if (state.draggingId != null) onCancelDrag() else onClose()
                }
                true
            } else {
                false
            }
        },
    ) {
        NdTopAppBar(
            title = stringResource(Res.string.groups_manage_title),
            navigation = {
                NdIconButton(onClose, NdIcons.ArrowBack, stringResource(Res.string.back))
            },
            actions = { topBarActions() },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loadFailed -> {
                    NdTextButton(
                        stringResource(Res.string.action_retry),
                        onRetry,
                        Modifier.align(Alignment.Center),
                    )
                }

                !state.loaded -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.groups.isEmpty() -> {
                    NdEmptyState(
                        icon = NdIcons.GridView,
                        title = stringResource(Res.string.groups_manage_empty),
                        body = stringResource(Res.string.groups_manage_empty_body),
                        actionLabel = stringResource(Res.string.group_edit_new),
                        onAction = onNew,
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("groupsManageList"),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        itemsIndexed(state.groups, key = { _, group -> group.id }) { index, group ->
                            ReorderableItem(reorderState, key = group.id) {
                                ManageRow(
                                    state,
                                    group,
                                    index,
                                    onMove,
                                    onShowAsTab,
                                    onEdit,
                                    onDelete,
                                    onSettings,
                                    handle = {
                                        Box(
                                            Modifier
                                                .size(48.dp)
                                                .testTag("groupDrag${group.id}")
                                                .clearAndSetSemantics {}
                                                .draggableHandle(
                                                    enabled = !state.busy && !state.loadFailed,
                                                    onDragStarted = { onStartDrag(group.id) },
                                                    dragGestureDetector = detector,
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                NdIcons.DragHandle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                    ExtendedFloatingActionButton(
                        onClick = onNew,
                        icon = { Icon(NdIcons.Add, contentDescription = null) },
                        text = { Text(stringResource(Res.string.group_edit_new)) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList", "CyclomaticComplexMethod") // Independent row affordances.
private fun ManageRow(
    state: GroupsManageUiState,
    group: Group,
    index: Int,
    onMove: (Long, GroupMove) -> Unit,
    onShowAsTab: (Long, Boolean) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: (Long) -> Unit,
    handle: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val enabled = !state.busy && state.draggingId == null
    val moveUp = stringResource(Res.string.groups_manage_up)
    val moveDown = stringResource(Res.string.groups_manage_down)
    val moveTop = stringResource(Res.string.groups_manage_top)
    val moves =
        buildList {
            if (index > 0) add(moveUp to GroupMove.UP)
            if (index < state.groups.lastIndex) add(moveDown to GroupMove.DOWN)
            if (index > 0) add(moveTop to GroupMove.TOP)
        }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("groupRow${group.id}")
            .onPreviewKeyEvent {
                val direction =
                    when (it.key) {
                        Key.DirectionUp -> GroupMove.UP
                        Key.DirectionDown -> GroupMove.DOWN
                        else -> null
                    }
                if (enabled && it.isAltPressed && direction != null) {
                    if (it.type == KeyEventType.KeyDown) onMove(group.id, direction)
                    true
                } else {
                    false
                }
            }.onSecondaryClick { if (enabled) menuOpen = true }
            .semantics {
                customActions =
                    if (enabled) {
                        moves.map { (label, move) ->
                            CustomAccessibilityAction(label) {
                                onMove(group.id, move)
                                true
                            }
                        }
                    } else {
                        emptyList()
                    }
            }.combinedClickable(
                onClick = { if (enabled) onEdit(group.id) },
                onLongClick = { if (enabled) menuOpen = true },
            ).heightIn(min = 72.dp)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        handle()
        GroupMosaic(
            group,
            state.mosaics[group.id].orEmpty(),
            0,
            0,
            modifier = Modifier.size(48.dp).clearAndSetSemantics {},
            layout = GroupMosaicLayout.THUMBNAIL,
        )
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Text(pluralStringResource(Res.plurals.podcast_count, group.memberCount, group.memberCount))
            if (!group.showAsTab) Text(stringResource(Res.string.group_hidden))
        }
        val tabLabel = stringResource(Res.string.group_edit_tab)
        Switch(
            checked = group.showAsTab,
            onCheckedChange = { onShowAsTab(group.id, it) },
            enabled = enabled,
            modifier =
                Modifier.testTag("groupTab${group.id}").semantics {
                    contentDescription = "${group.name}, $tabLabel"
                },
        )
        Box {
            NdIconButton(
                onClick = { menuOpen = true },
                icon = NdIcons.MoreVert,
                contentDescription = stringResource(Res.string.action_more),
                enabled = enabled,
                modifier = Modifier.testTag("groupMenu${group.id}"),
            )
            DropdownMenu(expanded = menuOpen && enabled, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.group_edit_title)) }, onClick = {
                    menuOpen = false
                    onEdit(group.id)
                })
                DropdownMenuItem(text = { Text(stringResource(Res.string.group_settings_title)) }, onClick = {
                    menuOpen = false
                    onSettings(group.id)
                })
                DropdownMenuItem(text = { Text(tabLabel) }, onClick = {
                    menuOpen = false
                    onShowAsTab(group.id, !group.showAsTab)
                })
                moves.forEach { (label, move) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        menuOpen = false
                        onMove(group.id, move)
                    })
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.group_edit_delete)) },
                    enabled = state.deleted == null,
                    onClick = {
                        menuOpen = false
                        onDelete(group.id)
                    },
                )
            }
        }
    }
}
