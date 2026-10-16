// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdBadge
import ch.lkmc.neutrodyne.core.designsystem.icons.groupIcon
import ch.lkmc.neutrodyne.core.designsystem.theme.groupTones
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.group_new_fmt
import ch.lkmc.neutrodyne.core.ui.resources.group_tab_desc
import ch.lkmc.neutrodyne.core.ui.resources.group_unplayed_fmt
import org.jetbrains.compose.resources.stringResource

/**
 * One group tab's label in the Feeds tab row (08 GroupMosaic and group tab label): the group icon
 * (or an 8 dp dot when none is mapped) in the dot tone, the one-line ellipsized name capped at
 * 200 dp, the unplayed count when positive, and an `NdBadge` with the new count. The node reports
 * `Role.Tab` and its selected state so the row announces "{name}, {u} unplayed, {k} new".
 */
@Composable
public fun GroupTabLabel(
    group: Group,
    unplayedCount: Int,
    newCount: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val tones = groupTones(group.colorArgb)
    val icon = groupIcon(group.iconKey)
    val description =
        stringResource(
            Res.string.group_tab_desc,
            group.name,
            stringResource(Res.string.group_unplayed_fmt, unplayedCount),
            stringResource(Res.string.group_new_fmt, newCount),
        )

    Row(
        modifier =
            modifier
                .widthIn(max = TAB_MAX_WIDTH)
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    this.selected = selected
                    contentDescription = description
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = tones.dot,
                modifier = Modifier.size(TAB_ICON_SIZE),
            )
        } else {
            Box(
                Modifier
                    .size(TAB_DOT_SIZE)
                    .clip(CircleShape)
                    .background(tones.dot),
            )
        }

        Text(
            group.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (unplayedCount > 0) {
            Text(
                unplayedCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NdBadge(newCount)
    }
}

private val TAB_MAX_WIDTH = 200.dp
private val TAB_ICON_SIZE = 18.dp
private val TAB_DOT_SIZE = 8.dp
private val TAB_GAP = 8.dp
