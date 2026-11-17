// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Cream
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.designsystem.radio.courier
import ch.lkmc.neutrodyne.core.designsystem.radio.recess
import ch.lkmc.neutrodyne.core.designsystem.theme.groupTones
import ch.lkmc.neutrodyne.core.model.ArtworkColors
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.navigation.LocalNavTab
import ch.lkmc.neutrodyne.core.ui.navigation.SharedKeys
import ch.lkmc.neutrodyne.core.ui.navigation.sharedArt
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.podcast_add_group
import ch.lkmc.neutrodyne.core.ui.resources.podcast_episode_count
import ch.lkmc.neutrodyne.core.ui.resources.podcast_group_remove
import ch.lkmc.neutrodyne.core.ui.resources.podcast_less
import ch.lkmc.neutrodyne.core.ui.resources.podcast_more
import ch.lkmc.neutrodyne.core.ui.resources.podcast_private
import ch.lkmc.neutrodyne.core.ui.resources.podcast_subscribed
import ch.lkmc.neutrodyne.core.ui.resources.podcast_updated
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 08 "Podcast header" as the radio's programme card (PO-55) — the first item of the podcast
 * screen's `LazyColumn` (it scrolls away under the screen's faceplate). The 120 dp cover sits in a
 * brass edge beside a paper card typed with the title (3 lines) and author, with the lit
 * "Subscribed" lamp. Below them: the memberships as enamel plates (M2): one per [groups] entry
 * with the group colour and a remove key, plus "+ Group" ([onAddToGroups]), which stays when
 * [groups] is empty; then three lines of plain description with a "More"/"Less" toggle and the
 * meta line "{n} episodes · Updated {relative} · Private". YouTube channels show their banner in
 * a recess above.
 */
@Composable
public fun PodcastHeader(
    detail: PodcastDetail,
    nowMs: Long,
    groups: List<Group>,
    onRemoveGroup: (Group) -> Unit,
    onAddToGroups: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ArtworkColors? = null,
    topInset: Dp = 0.dp,
    onArtworkBottomInWindow: (Float) -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("podcast_header")
                .padding(horizontal = HEADER_PADDING),
    ) {
        Spacer(Modifier.height(topInset + HEADER_TOP_GAP))
        if (detail.sourceType == SourceType.YOUTUBE_CHANNEL && detail.bannerUrl != null) {
            AsyncImage(
                detail.bannerUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(BANNER_ASPECT)
                        .recess(CoverShape),
            )
            Spacer(Modifier.height(CHIP_GAP))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            CoverArt(
                ref = detail.artwork,
                monogram = rememberMonogram(detail.displayTitle),
                title = detail.displayTitle,
                tier = CoverTier.HERO,
                shape = CoverShape,
                avgArgb = colors?.avgArgb,
                modifier =
                    Modifier
                        .size(HERO_SIZE)
                        .onGloballyPositioned {
                            onArtworkBottomInWindow(it.positionInWindow().y + it.size.height)
                        }.sharedArt(SharedKeys.cover(LocalNavTab.current, detail.id))
                        .shadow(6.dp, CoverShape)
                        .border(2.dp, Brass, CoverShape),
            )
            Spacer(Modifier.width(CARD_GAP))
            MaterialPlate(
                RadioMaterial.PAPER,
                Modifier
                    .weight(1f)
                    .heightIn(min = HERO_SIZE)
                    .shadow(2.dp, CardShape)
                    .clip(CardShape)
                    .border(1.dp, Brass, CardShape),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        detail.displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = Ink,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    detail.author?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SubscribedLamp()
                }
            }
        }
        Spacer(Modifier.height(CHIP_GAP))
        MembershipPlates(groups, onRemoveGroup, onAddToGroups)
        detail.description?.let { Description(it) }
        Spacer(Modifier.height(META_GAP))
        MetaLine(detail, nowMs)
    }
}

/** A lit amber pilot lamp beside "Subscribed": this screen only shows subscribed podcasts. */
@Composable
private fun SubscribedLamp() {
    Row(
        Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(LAMP_SIZE)
                .shadow(4.dp, CircleShape, ambientColor = LampAmber, spotColor = LampAmber)
                .background(Brush.radialGradient(listOf(Color(0xFFFFF4C8), LampAmber, Color(0xFFB8661A))), CircleShape)
                .border(1.dp, Color(0xFF6E5530), CircleShape),
        )
        Text(
            stringResource(Res.string.podcast_subscribed),
            style = MaterialTheme.typography.labelLarge,
            color = Ink,
        )
    }
}

/**
 * 08's header memberships (M2, D6) as small enamel plates: one per group with the group's colour
 * and name, opening the membership sheet, and a remove key that removes just that membership
 * ([onRemoveGroup]; the ViewModel's snackbar carries the Undo). "+ Group" is a paper tag that
 * opens the same sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembershipPlates(
    groups: List<Group>,
    onRemoveGroup: (Group) -> Unit,
    onAddToGroups: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { group -> GroupPlate(group, onAddToGroups, onRemoveGroup) }
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onAddToGroups),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(Res.string.podcast_add_group),
                Modifier
                    .shadow(1.dp, TagShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, TagShape)
                    .border(1.dp, Brass, TagShape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Ink,
            )
        }
    }
}

@Composable
private fun GroupPlate(
    group: Group,
    onOpen: () -> Unit,
    onRemove: (Group) -> Unit,
) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .shadow(2.dp, TagShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF2B3A4A), Color(0xFF16212D))), TagShape)
                .border(1.5.dp, Brass, TagShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(DOT_SIZE)
                        .background(groupTones(group.colorArgb).dot, CircleShape)
                        .border(1.dp, Cream, CircleShape),
                )
                Text(
                    group.name,
                    color = Cream,
                    fontFamily = courier(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .size(REMOVE_KEY_SIZE)
                    .clickable(role = Role.Button) { onRemove(group) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NdIcons.Close,
                    contentDescription = stringResource(Res.string.podcast_group_remove, group.name),
                    tint = Cream,
                    modifier = Modifier.size(CLOSE_ICON_SIZE),
                )
            }
        }
    }
}

/**
 * The description's first three lines of plain text with a "More"/"Less" disclosure (08). "More"
 * follows the text layout's `hasVisualOverflow`, not the newline count: a single paragraph that
 * wraps past [DESCRIPTION_LINES] gets the toggle, while three short lines never do.
 */
@Composable
private fun Description(notes: ShowNotes) {
    val plain = remember(notes) { notes.plainText() }
    // Keyed on notes like `plain`: a recycled call site must not inherit a previous
    // description's expanded/overflow state.
    var expanded by remember(notes) { mutableStateOf(false) }
    var overflows by remember(notes) { mutableStateOf(false) }
    Text(
        plain,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (result.hasVisualOverflow != overflows) overflows = result.hasVisualOverflow
        },
    )
    if (expanded || overflows) {
        NdTextButton(
            label =
                stringResource(
                    if (expanded) Res.string.podcast_less else Res.string.podcast_more,
                ),
            onClick = { expanded = !expanded },
        )
    }
}

/** The preview reuses description expansion while owning its surrounding header layout. */
@Composable
public fun PodcastDescription(
    notes: ShowNotes,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Description(notes) }
}

/** "{n} episodes · Updated {relative} · Private". */
@Composable
private fun MetaLine(
    detail: PodcastDetail,
    nowMs: Long,
) {
    val parts = mutableListOf<UiText>()
    parts +=
        UiText.Plural(
            Res.plurals.podcast_episode_count,
            detail.episodeCount,
            listOf(detail.episodeCount),
        )
    detail.latestEpisodeAt?.let {
        parts += UiText.Res(Res.string.podcast_updated, listOf(FeedDates.relative(it, nowMs)))
    }
    if (detail.isPrivate) parts += UiText.Res(Res.string.podcast_private)
    Text(
        UiText.Joined(parts, separator = " · ", suffix = "").asString(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The description as plain text — paragraphs and list items separated by newlines. Blank blocks
 * (alt-less images, rules) are dropped before joining so they don't eat the 3-line preview
 * budget as empty lines.
 */
private fun ShowNotes.plainText(): String =
    blocks
        .map { it.plainText() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()

private fun ShowNoteBlock.plainText(): String =
    when (this) {
        is ShowNoteBlock.Paragraph -> {
            spans.plainText()
        }

        is ShowNoteBlock.Heading -> {
            spans.plainText()
        }

        is ShowNoteBlock.ListBlock -> {
            items.joinToString("\n") { item -> item.joinToString("\n") { it.plainText() } }
        }

        is ShowNoteBlock.Quote -> {
            blocks.joinToString("\n") { it.plainText() }
        }

        is ShowNoteBlock.Image -> {
            alt ?: ""
        }

        ShowNoteBlock.Rule -> {
            ""
        }
    }

private fun List<ShowNoteSpan>.plainText(): String =
    joinToString("") {
        when (it) {
            is ShowNoteSpan.Text -> it.text
            is ShowNoteSpan.Link -> it.text
            is ShowNoteSpan.Timestamp -> it.text
            ShowNoteSpan.LineBreak -> "\n"
        }
    }

private val HEADER_PADDING = 16.dp
private val HEADER_TOP_GAP = 12.dp
private val HERO_SIZE = 120.dp
private val CARD_GAP = 12.dp
private val LAMP_SIZE = 9.dp
private val REMOVE_KEY_SIZE = 32.dp
private const val BANNER_ASPECT = 6f
private val CoverShape = RoundedCornerShape(4.dp)
private val CardShape = RoundedCornerShape(4.dp)
private val TagShape = RoundedCornerShape(3.dp)
private val LampAmber = Color(0xFFF3B35E)
private val CHIP_GAP = 8.dp
private val META_GAP = 12.dp
private val DOT_SIZE = 10.dp
private val CLOSE_ICON_SIZE = 18.dp
private const val DESCRIPTION_LINES = 3
