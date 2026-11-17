// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.common.art.Graphemes
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.icons.groupIcon
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Cream
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.designsystem.radio.barlow
import ch.lkmc.neutrodyne.core.designsystem.radio.courier
import ch.lkmc.neutrodyne.core.designsystem.radio.recess
import ch.lkmc.neutrodyne.core.designsystem.theme.groupTones
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.group_counts_join
import ch.lkmc.neutrodyne.core.ui.resources.group_hidden
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A group as a card-catalogue drawer (PO-55, 08 Library): a wooden drawer front with the group
 * name typed on a card in a brass label holder (its colour on an enamel tab), the member and
 * unplayed counts, up to four member covers and a brass cup pull. The merged node reads like
 * [GroupMosaic]'s tile: "{name}, {n} podcasts, {u} unplayed, {k} new". The caller owns the click
 * target and the group's actions.
 */
@Composable
public fun GroupDrawer(
    group: Group,
    members: List<GroupMosaicMember>,
    unplayedCount: Int?,
    newCount: Int?,
    modifier: Modifier = Modifier,
) {
    val description = mosaicDescription(group, unplayedCount, newCount)
    MaterialPlate(
        RadioMaterial.WOOD,
        modifier
            .shadow(4.dp, DrawerShape)
            .clip(DrawerShape)
            .border(1.dp, Color(0x99000000), DrawerShape)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = DRAWER_MIN_HEIGHT).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelHolder(group)
                Text(
                    drawerCountsText(group.memberCount, unplayedCount, newCount),
                    color = Cream,
                    fontFamily = barlow(),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MemberCovers(group, members)
            }
            CupPull(Modifier.padding(start = 12.dp))
        }
    }
}

/**
 * The empty drawer at the end of the cabinet: a blank card in its holder and [label] chalked
 * below. The caller owns the click target.
 */
@Composable
public fun NewGroupDrawer(
    label: String,
    modifier: Modifier = Modifier,
) {
    MaterialPlate(
        RadioMaterial.WOOD,
        modifier
            .shadow(4.dp, DrawerShape)
            .clip(DrawerShape)
            .border(1.dp, Color(0x99000000), DrawerShape),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = NEW_DRAWER_MIN_HEIGHT).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardHolder(Modifier.fillMaxWidth(LABEL_WIDTH_FRACTION)) { Box(Modifier.height(20.dp)) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(NdIcons.Add, null, Modifier.size(16.dp), tint = Cream)
                    Text(
                        label,
                        color = Cream,
                        fontFamily = courier(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
            CupPull(Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun LabelHolder(group: Group) {
    val tones = groupTones(group.colorArgb)
    CardHolder(Modifier.fillMaxWidth(LABEL_WIDTH_FRACTION)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // The group's colour, enamelled on a tab at the card's edge.
            Box(Modifier.width(5.dp).height(18.dp).background(tones.dot, RoundedCornerShape(1.dp)))
            Text(
                group.name,
                Modifier.weight(1f, fill = false),
                color = Ink,
                fontFamily = courier(),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!group.showAsTab) {
                Icon(
                    NdIcons.VisibilityOff,
                    contentDescription = stringResource(Res.string.group_hidden),
                    tint = Ink,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** A brass label holder with a paper card in it, screwed to the drawer front. */
@Composable
private fun CardHolder(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .shadow(2.dp, HolderShape)
            .background(Brush.verticalGradient(listOf(Color(0xFFE2C98F), Brass, Color(0xFF7A5F35))), HolderShape)
            .padding(3.dp),
    ) {
        MaterialPlate(RadioMaterial.PAPER, Modifier.fillMaxWidth().clip(CardShape)) {
            Box(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) { content() }
        }
    }
}

@Composable
private fun MemberCovers(
    group: Group,
    members: List<GroupMosaicMember>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val shown = members.take(MAX_COVERS)
        shown.forEach { member ->
            CoverArt(
                ref = member.artwork,
                monogram = rememberMonogram(member.displayTitle),
                tier = CoverTier.THUMB,
                shape = RectangleShape,
                avgArgb = member.artworkAvgArgb,
                modifier = Modifier.size(COVER_SIZE).recess(CoverShape),
            )
        }
        if (shown.isEmpty()) EmptyCover(group)
    }
}

/** A group without members shows its icon (or the name's first grapheme) in the cover slot. */
@Composable
private fun EmptyCover(group: Group) {
    val tones = groupTones(group.colorArgb)
    val icon = groupIcon(group.iconKey)
    Box(
        Modifier.size(COVER_SIZE).recess(CoverShape).background(tones.container),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp), tint = tones.onContainer)
        } else if (group.name.isNotEmpty()) {
            Text(
                Graphemes.first(group.name),
                color = tones.onContainer,
                fontFamily = courier(),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A brass cup pull: decoration that marks the whole front as the drawer's handle. */
@Composable
private fun CupPull(modifier: Modifier = Modifier) {
    Box(modifier.width(PULL_WIDTH).height(PULL_HEIGHT).clearAndSetSemantics {}) {
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .fillMaxWidth()
                .height(CUP_HEIGHT)
                .align(Alignment.BottomCenter)
                .shadow(3.dp, CupShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF6E5530), Brass, Color(0xFFF2DDA0))), CupShape),
        )
        // The hollow inside the cup, in the backplate's shadow.
        Box(
            Modifier
                .padding(horizontal = 10.dp)
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(CUP_HEIGHT - 8.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF140B04), Color(0xFF4A3218))), CupShape),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(BACKPLATE_HEIGHT)
                .shadow(2.dp, BackplateShape)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFFF2DDA0), Brass, Color(0xFF7A5F35))),
                    BackplateShape,
                ),
        )
    }
}

/** "{n} podcasts · {u} unplayed · {k} new", with zero or unknown counts dropped. */
@Composable
private fun drawerCountsText(
    memberCount: Int,
    unplayedCount: Int?,
    newCount: Int?,
): String {
    val podcasts = pluralStringResource(Res.plurals.podcast_count, memberCount, memberCount)
    val counts = if (unplayedCount != null && newCount != null) groupCountsText(unplayedCount, newCount) else ""
    return if (counts.isEmpty()) podcasts else stringResource(Res.string.group_counts_join, podcasts, counts)
}

private val DrawerShape = RoundedCornerShape(6.dp)
private val HolderShape = RoundedCornerShape(3.dp)
private val CardShape = RoundedCornerShape(2.dp)
private val CoverShape = RoundedCornerShape(3.dp)
private val COVER_SIZE = 40.dp
private val PULL_WIDTH = 64.dp
private val PULL_HEIGHT = 34.dp
private val CUP_HEIGHT = 26.dp
private val BACKPLATE_HEIGHT = 10.dp
private val BackplateShape = RoundedCornerShape(5.dp)

/** The lower half of an ellipse whose top edge spans the shape's width. */
private val CupShape =
    GenericShape { size, _ ->
        moveTo(0f, 0f)
        arcTo(Rect(0f, -size.height, size.width, size.height), 180f, -180f, false)
        close()
    }
private val DRAWER_MIN_HEIGHT = 132.dp
private val NEW_DRAWER_MIN_HEIGHT = 96.dp
private const val MAX_COVERS = 4
private const val LABEL_WIDTH_FRACTION = 0.92f
