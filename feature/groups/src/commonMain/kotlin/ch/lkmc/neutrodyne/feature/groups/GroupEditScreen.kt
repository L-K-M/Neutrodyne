// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdModalBottomSheet
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.icons.groupIcon
import ch.lkmc.neutrodyne.core.designsystem.theme.groupTones
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.GroupIcons
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.ui.CoverTile
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_close
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_color
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_custom
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_delete
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_icon
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_icon_value
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_members
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name_count
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name_empty
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name_long
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_name_taken
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_new
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_no_members
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_none
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_oldest_hint
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_order
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_search
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_tab
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_title
import ch.lkmc.neutrodyne.core.ui.resources.group_settings_title
import ch.lkmc.neutrodyne.core.ui.resources.order_newest
import ch.lkmc.neutrodyne.core.ui.resources.order_oldest
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("FunctionNaming", "CyclomaticComplexMethod") // One form composes the independent editor controls.
internal fun GroupEditScreen(
    state: GroupEditUiState,
    groupId: Long?,
    onEdit: (GroupEditDraft) -> Unit,
    onSearch: (String) -> Unit,
    onToggleMember: (Long) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val draft = state.draft
    val enabled = state.loaded && !state.busy
    var iconsOpen by remember { mutableStateOf(false) }
    val error = nameErrorText(state.nameError, draft.name)
    Column(
        modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.key == Key.Escape) {
                if (it.type == KeyEventType.KeyDown) onClose()
                true
            } else {
                false
            }
        },
    ) {
        NdTopAppBar(
            title = stringResource(if (groupId == null) Res.string.group_edit_new else Res.string.group_edit_title),
            navigation = {
                NdIconButton(
                    onClick = onClose,
                    icon = NdIcons.Close,
                    contentDescription = stringResource(Res.string.group_edit_close),
                )
            },
            actions = {
                NdTextButton(label = stringResource(Res.string.action_save), onClick = onSave, enabled = enabled)
            },
        )
        if (!state.loaded) {
            if (state.loadFailed) {
                NdTextButton(label = stringResource(Res.string.action_retry), onClick = onRetry)
            } else {
                CircularProgressIndicator(Modifier.padding(16.dp))
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(72.dp),
            modifier = Modifier.weight(1f).testTag("groupEditorGrid"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onEdit(draft.copy(name = it)) },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(Res.string.group_edit_name)) },
                    isError = error != null,
                    supportingText = {
                        Column {
                            if (error != null) Text(error)
                            Text(
                                stringResource(
                                    Res.string.group_edit_name_count,
                                    codePointCount(draft.name),
                                    GroupNames.MAX_CODE_POINTS,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("groupName"),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(stringResource(Res.string.group_edit_color))
                    FlowRow {
                        GroupPalette.COLORS.forEachIndexed { index, color ->
                            ColorChoice(color, GroupPalette.KEYS[index], draft.colorArgb == color, enabled) {
                                onEdit(draft.copy(colorArgb = color))
                            }
                        }
                        if (draft.colorArgb != null && draft.colorArgb !in GroupPalette.COLORS) {
                            ColorChoice(draft.colorArgb, stringResource(Res.string.group_edit_custom), true, enabled) {}
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(
                    onClick = { iconsOpen = true },
                    enabled = enabled,
                    modifier = Modifier.testTag("groupIconPicker"),
                ) {
                    groupIcon(draft.iconKey)?.let { Icon(it, contentDescription = null) }
                    Text(
                        stringResource(
                            Res.string.group_edit_icon_value,
                            draft.iconKey ?: stringResource(Res.string.group_edit_none),
                        ),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(stringResource(Res.string.group_edit_order))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedOrder.entries.forEach { order ->
                            NdFilterChip(
                                selected = draft.playOrder == order,
                                onClick = { onEdit(draft.copy(playOrder = order)) },
                                enabled = enabled,
                                label =
                                    stringResource(
                                        if (order ==
                                            FeedOrder.OLDEST_FIRST
                                        ) {
                                            Res.string.order_oldest
                                        } else {
                                            Res.string.order_newest
                                        },
                                    ),
                            )
                        }
                    }
                    if (draft.playOrder != FeedOrder.OLDEST_FIRST && GroupNames.suggestsOldestFirst(draft.name)) {
                        NdTextButton(
                            label = stringResource(Res.string.group_edit_oldest_hint),
                            onClick = { onEdit(draft.copy(playOrder = FeedOrder.OLDEST_FIRST)) },
                            enabled = enabled,
                        )
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tabLabel = stringResource(Res.string.group_edit_tab)
                    Text(tabLabel, Modifier.weight(1f))
                    Switch(
                        checked = draft.showAsTab,
                        onCheckedChange = { onEdit(draft.copy(showAsTab = it)) },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = tabLabel }.testTag("groupShowAsTab"),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(stringResource(Res.string.group_edit_members, draft.memberIds.size))
                    if (state.membersFailed) {
                        NdTextButton(label = stringResource(Res.string.action_retry), onClick = onRetry)
                    }
                    OutlinedTextField(
                        value = state.search,
                        onValueChange = onSearch,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.group_edit_search)) },
                        modifier = Modifier.fillMaxWidth().testTag("groupMemberSearch"),
                    )
                }
            }
            val visible = state.tiles.filter { it.displayTitle.contains(state.search, ignoreCase = true) }
            if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(Res.string.group_edit_no_members)) }
            }
            items(visible, key = { it.podcastId }) { tile ->
                val checked = tile.podcastId in draft.memberIds
                CoverTile(
                    tile,
                    showTitle = true,
                    selected = checked,
                    menuActions = emptyList(),
                    onClick = { if (enabled) onToggleMember(tile.podcastId) },
                    onLongClick = { if (enabled) onToggleMember(tile.podcastId) },
                    modifier =
                        Modifier.widthIn(max = 72.dp).testTag("groupMember${tile.podcastId}").semantics {
                            role = Role.Checkbox
                            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                        },
                )
            }
            if (groupId != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        NdTextButton(
                            label = stringResource(Res.string.group_settings_title),
                            onClick = onSettings,
                            enabled = enabled,
                        )
                        NdTextButton(
                            label = stringResource(Res.string.group_edit_delete),
                            onClick = onDelete,
                            enabled = enabled,
                            role = NdActionRole.DESTRUCTIVE,
                        )
                    }
                }
            }
        }
    }
    if (iconsOpen) {
        NdModalBottomSheet(onDismissRequest = { iconsOpen = false }) {
            Text(stringResource(Res.string.group_edit_icon), Modifier.padding(16.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(80.dp), modifier = Modifier.heightIn(max = 400.dp)) {
                items(listOf<String?>(null) + GroupIcons.KEYS) { key ->
                    val label = key ?: stringResource(Res.string.group_edit_none)
                    Column(
                        Modifier
                            .heightIn(min = 48.dp)
                            .background(
                                if (draft.iconKey == key) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ).selectable(selected = draft.iconKey == key, enabled = enabled, role = Role.RadioButton) {
                                onEdit(draft.copy(iconKey = key))
                                iconsOpen = false
                            }.padding(8.dp)
                            .semantics { contentDescription = label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        groupIcon(key)?.let { Icon(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose functions use PascalCase.
private fun ColorChoice(
    color: Int,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tones = groupTones(color)
    Box(
        Modifier
            .size(48.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).background(tones.dot, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    NdIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun nameErrorText(
    error: GroupError?,
    name: String,
): String? =
    when (error) {
        GroupError.NameEmpty -> stringResource(Res.string.group_edit_name_empty)
        is GroupError.NameTooLong -> stringResource(Res.string.group_edit_name_long, error.max)
        is GroupError.NameTaken -> stringResource(Res.string.group_edit_name_taken, name)
        else -> null
    }

private fun codePointCount(value: String): Int {
    var count = 0
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isHighSurrogate() && index < value.length && value[index].isLowSurrogate()) index++
        count++
    }
    return count
}
