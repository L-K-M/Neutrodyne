// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.art.Graphemes
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.icons.groupIcon
import ch.lkmc.neutrodyne.core.designsystem.theme.GroupTones
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.designsystem.theme.groupTones
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.group_counts_join
import ch.lkmc.neutrodyne.core.ui.resources.group_hidden
import ch.lkmc.neutrodyne.core.ui.resources.group_mosaic_desc
import ch.lkmc.neutrodyne.core.ui.resources.group_new_fmt
import ch.lkmc.neutrodyne.core.ui.resources.group_unplayed_fmt
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** THUMBNAIL draws decorative artwork for a row that supplies its own name and counts. */
public enum class GroupMosaicLayout { TILE, THUMBNAIL }

/**
 * A group's library-tile mosaic (08 GroupMosaic): a 12 dp-cornered square holding the four
 * newest-active members' `CoverArt` (THUMB) in a 2 × 2 grid with 2 dp gaps. Empty cells take the
 * group's container tone, the first one carrying the group icon (or the name's first grapheme
 * when no icon is mapped); zero members fill the whole square. A 4 dp dot-tone stripe runs along
 * the top edge, and groups hidden from the tab row carry a `visibility_off` marker. In grids the
 * name and "unplayed · new" counts sit below the square. Null counts omit both their label
 * and their announcement while a caller has no counts API.
 */
@Composable
public fun GroupMosaic(
    group: Group,
    members: List<GroupMosaicMember>,
    unplayedCount: Int?,
    newCount: Int?,
    modifier: Modifier = Modifier,
    layout: GroupMosaicLayout = GroupMosaicLayout.TILE,
) {
    val tones = groupTones(group.colorArgb)
    val icon = groupIcon(group.iconKey)
    val cells = members.take(MOSAIC_CELLS)
    val description = if (layout == GroupMosaicLayout.TILE) mosaicDescription(group, unplayedCount, newCount) else null

    Column(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                if (description !=
                    null
                ) {
                    contentDescription = description
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(NeutrodyneShapes.Tile),
        ) {
            if (cells.isEmpty()) {
                EmptyCell(icon, group.name, tones, Modifier.fillMaxSize())
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MOSAIC_GAP)) {
                    repeat(MOSAIC_ROWS) { row ->
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MOSAIC_GAP),
                        ) {
                            repeat(MOSAIC_COLS) { col ->
                                val slot = row * MOSAIC_COLS + col
                                val member = cells.getOrNull(slot)
                                Box(Modifier.weight(1f).aspectRatio(1f)) {
                                    if (member != null) {
                                        CoverArt(
                                            ref = member.artwork,
                                            monogram = rememberMonogram(member.displayTitle),
                                            tier = CoverTier.THUMB,
                                            shape = RectangleShape,
                                            avgArgb = member.artworkAvgArgb,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        // Only the first empty slot carries the icon/grapheme (08).
                                        EmptyCell(
                                            icon.takeIf { slot == cells.size },
                                            group.name.takeIf { slot == cells.size }.orEmpty(),
                                            tones,
                                            Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Dot-tone stripe along the top edge (08).
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(STRIPE_HEIGHT)
                    .align(Alignment.TopCenter)
                    .background(tones.dot),
            )

            if (!group.showAsTab) {
                Icon(
                    NdIcons.VisibilityOff,
                    contentDescription = stringResource(Res.string.group_hidden),
                    tint = tones.onContainer,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(HIDDEN_BADGE_PAD)
                            .clip(NeutrodyneShapes.Thumbnail)
                            .background(tones.container)
                            .padding(HIDDEN_BADGE_INNER)
                            .size(HIDDEN_ICON_SIZE),
                )
            }
        }

        if (layout == GroupMosaicLayout.THUMBNAIL) return@Column

        Text(
            group.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = NAME_GAP),
        )
        if (unplayedCount != null && newCount != null && (unplayedCount > 0 || newCount > 0)) {
            Text(
                groupCountsText(unplayedCount, newCount),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One container-toned cell (or the whole zero-member square): icon, else the name grapheme. */
@Composable
private fun EmptyCell(
    icon: ImageVector?,
    name: String,
    tones: GroupTones,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(tones.container), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = tones.onContainer,
                modifier = Modifier.size(EMPTY_ICON_SIZE),
            )
        } else if (name.isNotEmpty()) {
            Text(
                Graphemes.first(name),
                color = tones.onContainer,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }
    }
}

/** "{name}, {n} podcasts, {u} unplayed, {k} new" — the merged node reads it whole (08). */
@Composable
private fun mosaicDescription(
    group: Group,
    unplayedCount: Int?,
    newCount: Int?,
): String =
    if (unplayedCount == null || newCount == null) {
        group.name
    } else {
        stringResource(
            Res.string.group_mosaic_desc,
            group.name,
            pluralStringResource(Res.plurals.podcast_count, group.memberCount, group.memberCount),
            stringResource(Res.string.group_unplayed_fmt, unplayedCount),
            stringResource(Res.string.group_new_fmt, newCount),
        )
    }

/** "{u} unplayed · {k} new" with zero parts dropped (08's grid label). */
@Composable
internal fun groupCountsText(
    unplayedCount: Int,
    newCount: Int,
): String {
    val unplayed = stringResource(Res.string.group_unplayed_fmt, unplayedCount).takeIf { unplayedCount > 0 }
    val new = stringResource(Res.string.group_new_fmt, newCount).takeIf { newCount > 0 }
    return when {
        unplayed != null && new != null -> stringResource(Res.string.group_counts_join, unplayed, new)
        else -> unplayed ?: new.orEmpty()
    }
}

private const val MOSAIC_CELLS = 4
private const val MOSAIC_ROWS = 2
private const val MOSAIC_COLS = 2
private val MOSAIC_GAP = 2.dp
private val STRIPE_HEIGHT = 4.dp
private val EMPTY_ICON_SIZE = 28.dp
private val HIDDEN_ICON_SIZE = 14.dp
private val HIDDEN_BADGE_PAD = 4.dp
private val HIDDEN_BADGE_INNER = 2.dp
private val NAME_GAP = 4.dp
