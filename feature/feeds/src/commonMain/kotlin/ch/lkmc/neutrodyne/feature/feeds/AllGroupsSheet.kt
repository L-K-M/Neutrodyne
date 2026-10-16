// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.ui.GroupMosaic
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_all_groups
import ch.lkmc.neutrodyne.core.ui.resources.groups_empty
import ch.lkmc.neutrodyne.core.ui.resources.groups_manage
import ch.lkmc.neutrodyne.core.ui.resources.groups_new
import ch.lkmc.neutrodyne.core.ui.resources.groups_recent
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

/** Three columns at every width, including groups hidden from the tab row (08). */
@Composable
internal fun AllGroupsSheet(
    state: AllGroupsUiState,
    onSelectGroup: (Long) -> Unit,
    onNewGroup: () -> Unit,
    onManageGroups: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }

    Column(
        modifier.fillMaxWidth().testTag("all-groups-sheet").onPreviewKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        Text(
            stringResource(Res.string.feeds_all_groups),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            NdTextButton(
                label = stringResource(Res.string.groups_new),
                onClick = onNewGroup,
                modifier = Modifier.focusRequester(initialFocus),
                enabled = !state.selecting,
            )
            NdTextButton(
                label = stringResource(Res.string.groups_manage),
                onClick = onManageGroups,
                enabled = !state.selecting,
            )
        }
        state.error?.let { error ->
            Text(
                text = error.asString(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when {
            !state.loaded -> {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            }

            state.groups.isEmpty() -> {
                Text(
                    stringResource(Res.string.groups_empty),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                )
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("all-groups-grid"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    if (state.recent.isNotEmpty()) {
                        item(key = "recent-heading", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeading(stringResource(Res.string.groups_recent), "recent-heading")
                        }
                        groupTiles("recent", state.recent, state.selecting, onSelectGroup)
                    }
                    item(key = "all-heading", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeading(stringResource(Res.string.feeds_all_groups), "all-heading")
                    }
                    groupTiles("all", state.groups, state.selecting, onSelectGroup)
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    tag: String,
) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(tag).semantics { heading() })
}

private fun LazyGridScope.groupTiles(
    section: String,
    groups: ImmutableList<AllGroupsTile>,
    selecting: Boolean,
    onSelectGroup: (Long) -> Unit,
) {
    items(groups, key = { "$section:${it.group.uuid}" }) { tile ->
        GroupMosaic(
            group = tile.group,
            members = tile.members,
            unplayedCount = tile.counts.unplayed,
            newCount = tile.counts.newSinceVisit,
            modifier =
                Modifier.testTag("$section:${tile.group.uuid}").clickable(enabled = !selecting, role = Role.Button) {
                    onSelectGroup(tile.group.id)
                },
        )
    }
}

private const val GRID_COLUMNS = 3
