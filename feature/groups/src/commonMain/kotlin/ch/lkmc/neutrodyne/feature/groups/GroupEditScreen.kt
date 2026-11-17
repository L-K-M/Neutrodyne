// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdModalBottomSheet
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.icons.groupIcon
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Cream
import ch.lkmc.neutrodyne.core.designsystem.radio.EnamelSelector
import ch.lkmc.neutrodyne.core.designsystem.radio.FaceplateKey
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.designsystem.radio.KeyBank
import ch.lkmc.neutrodyne.core.designsystem.radio.MomentaryKey
import ch.lkmc.neutrodyne.core.designsystem.radio.PaperTextField
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKeys
import ch.lkmc.neutrodyne.core.designsystem.radio.barlow
import ch.lkmc.neutrodyne.core.designsystem.theme.groupTones
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.GroupIcons
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.ui.CoverTile
import ch.lkmc.neutrodyne.core.ui.radio.RadioSwitch
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_close
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_color
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_custom
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_delete
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_icon
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
import ch.lkmc.neutrodyne.core.ui.root.ndTextFieldFocus
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
                FaceplateKey(NdIcons.Check, stringResource(Res.string.action_save), onSave, enabled = enabled)
            },
        )
        if (!state.loaded) {
            if (state.loadFailed) {
                KeyBank(Modifier.padding(16.dp)) { MomentaryKey(stringResource(Res.string.action_retry), onRetry) }
            } else {
                CircularProgressIndicator(Modifier.padding(16.dp))
            }
            return@Column
        }
        // 08 Scrollbars: the desktop draws an overlay thumb at the grid's end edge.
        val gridState = rememberLazyGridState()
        val scrollbars = LocalScrollbars.current
        val scrollbarColor = ndScrollbarColor(scrollbars)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(TILE_WIDTH),
            state = gridState,
            modifier =
                Modifier
                    .weight(1f)
                    .testTag("groupEditorGrid")
                    .ndScrollbar(gridState, scrollbars, scrollbarColor),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel(stringResource(Res.string.group_edit_name))
                    PaperTextField(
                        value = draft.name,
                        onValueChange = { onEdit(draft.copy(name = it)) },
                        placeholder = stringResource(Res.string.group_edit_name),
                        modifier = Modifier.testTag("groupName").ndTextFieldFocus(),
                        enabled = enabled,
                        errorText = error,
                        trailingText =
                            stringResource(
                                Res.string.group_edit_name_count,
                                codePointCount(draft.name),
                                GroupNames.MAX_CODE_POINTS,
                            ),
                    )
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel(stringResource(Res.string.group_edit_color))
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
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel(stringResource(Res.string.group_edit_icon))
                    EnamelSelector(
                        draft.iconKey ?: stringResource(Res.string.group_edit_none),
                        onClick = { iconsOpen = true },
                        modifier = Modifier.testTag("groupIconPicker"),
                        enabled = enabled,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel(stringResource(Res.string.group_edit_order))
                    PianoKeys(
                        options = FeedOrder.entries,
                        selected = draft.playOrder,
                        onSelect = { onEdit(draft.copy(playOrder = it)) },
                        enabled = enabled,
                        label = {
                            stringResource(
                                if (it ==
                                    FeedOrder.OLDEST_FIRST
                                ) {
                                    Res.string.order_oldest
                                } else {
                                    Res.string.order_newest
                                },
                            )
                        },
                        modifier = Modifier.widthIn(max = ORDER_KEYS_MAX_WIDTH),
                    )
                    if (draft.playOrder != FeedOrder.OLDEST_FIRST && GroupNames.suggestsOldestFirst(draft.name)) {
                        KeyBank {
                            MomentaryKey(
                                stringResource(Res.string.group_edit_oldest_hint),
                                { onEdit(draft.copy(playOrder = FeedOrder.OLDEST_FIRST)) },
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("groupShowAsTab")
                        .toggleable(draft.showAsTab, enabled = enabled, role = Role.Switch) {
                            onEdit(draft.copy(showAsTab = it))
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(Res.string.group_edit_tab), Modifier.weight(1f))
                    RadioSwitch(draft.showAsTab, onCheckedChange = null, enabled = enabled)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel(stringResource(Res.string.group_edit_members, draft.memberIds.size))
                    if (state.membersFailed) {
                        KeyBank { MomentaryKey(stringResource(Res.string.action_retry), onRetry) }
                    }
                    PaperTextField(
                        value = state.search,
                        onValueChange = onSearch,
                        placeholder = stringResource(Res.string.group_edit_search),
                        modifier = Modifier.testTag("groupMemberSearch").ndTextFieldFocus(),
                        leadingIcon = NdIcons.Search,
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
                        Modifier.widthIn(max = TILE_WIDTH).testTag("groupMember${tile.podcastId}").semantics {
                            role = Role.Checkbox
                            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                        },
                )
            }
            if (groupId != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    KeyBank(Modifier.padding(top = 8.dp)) {
                        MomentaryKey(stringResource(Res.string.group_settings_title), onSettings, enabled = enabled)
                        MomentaryKey(stringResource(Res.string.group_edit_delete), onDelete, enabled = enabled)
                    }
                }
            }
        }
    }
    if (iconsOpen) {
        NdModalBottomSheet(onDismissRequest = { iconsOpen = false }) {
            Text(stringResource(Res.string.group_edit_icon), Modifier.padding(16.dp))
            val iconGridState = rememberLazyGridState()
            val scrollbars = LocalScrollbars.current
            val scrollbarColor = ndScrollbarColor(scrollbars)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(80.dp),
                state = iconGridState,
                modifier =
                    Modifier
                        .heightIn(max = 400.dp)
                        .ndScrollbar(iconGridState, scrollbars, scrollbarColor),
            ) {
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

/**
 * One palette colour as a round enamel button in a brass rim; the chosen one sits pressed in
 * with a check engraved on it.
 */
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
        Box(
            Modifier
                .size(ENAMEL_SIZE)
                .shadow(if (selected) 0.5.dp else 3.dp, CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            lerp(tones.dot, Color.White, ENAMEL_GLOSS),
                            tones.dot,
                            lerp(tones.dot, Color.Black, ENAMEL_SHADE),
                        ),
                        center = Offset.Unspecified,
                    ),
                    CircleShape,
                ).border(2.dp, Brass, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(NdIcons.Check, contentDescription = null, tint = Cream, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A form field's name engraved above it in spaced capitals, read as written. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        Modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
        color = Ink,
        fontFamily = barlow(),
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
    )
}

private val TILE_WIDTH = 88.dp
private val ENAMEL_SIZE = 36.dp
private val ORDER_KEYS_MAX_WIDTH = 360.dp
private const val ENAMEL_GLOSS = 0.35f
private const val ENAMEL_SHADE = 0.3f

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
