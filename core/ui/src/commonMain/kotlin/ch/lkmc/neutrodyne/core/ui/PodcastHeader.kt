// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.components.NdAssistChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdInputChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalSystemUiState
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.art.ArtColors
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
 * 08 "Podcast header" — the first item of the podcast screen's `LazyColumn` (it scrolls away; the
 * pinned transparent top bar is the screen's). The header has no artwork-scheme tint (M10). Cover
 * 160 dp at 24 dp corners, the title `headlineSmall` (2 lines), author, the "Subscribed" chip,
 * three lines of plain description with a "More"/"Less" toggle, and the meta line
 * "{n} episodes · Updated {relative} · Private". M2 adds the membership chips below the
 * "Subscribed" row: one chip per [groups] entry with the group dot and a remove affordance,
 * plus "+ Group" ([onAddToGroups]) which stays when [groups] is empty.
 */
@Composable
public fun PodcastHeader(
    detail: PodcastDetail,
    nowMs: Long,
    groups: List<Group>,
    onRemoveGroup: (Group) -> Unit,
    onAddToGroups: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = HEADER_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = HEADER_TOP_GAP),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (detail.sourceType == SourceType.YOUTUBE_CHANNEL && detail.bannerUrl != null) {
                Box(Modifier.fillMaxWidth().aspectRatio(BANNER_ASPECT).align(Alignment.TopCenter)) {
                    AsyncImage(
                        model = detail.bannerUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = BANNER_SCRIM))),
                        ),
                    )
                }
            }
            CoverArt(
                ref = detail.artwork,
                monogram = rememberMonogram(detail.displayTitle),
                title = detail.displayTitle,
                tier = CoverTier.HERO,
                shape = NeutrodyneShapes.PlayerArt,
                avgArgb = null,
                modifier = Modifier.size(HERO_SIZE),
            )
        }
        Spacer(Modifier.height(COVER_TITLE_GAP))
        Text(
            detail.displayTitle,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        detail.author?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(CHIP_GAP))
        Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
            NdFilterChip(
                selected = true,
                onClick = {},
                label = stringResource(Res.string.podcast_subscribed),
                leadingIcon = NdIcons.Check,
                enabled = false,
            )
        }
        Spacer(Modifier.height(CHIP_GAP))
        MembershipChips(groups, onRemoveGroup, onAddToGroups)
        detail.description?.let { Description(it) }
        Spacer(Modifier.height(META_GAP))
        MetaLine(detail, nowMs)
    }
}

/**
 * 08's header membership chips (M2, D6): one [NdInputChip] per group — the group dot, the name
 * and a trailing remove affordance — plus the "+ Group" action chip that opens the
 * membership sheet. A chip's body tap opens the same sheet; its trailing "x" removes just
 * that membership ([onRemoveGroup]), and the ViewModel's snackbar carries the per-membership
 * Undo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembershipChips(
    groups: List<Group>,
    onRemoveGroup: (Group) -> Unit,
    onAddToGroups: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
        groups.forEach { group ->
            NdInputChip(
                onClick = onAddToGroups,
                label = group.name,
                leadingIcon = { GroupDot(group.colorArgb) },
                trailingIcon = {
                    Icon(
                        imageVector = NdIcons.Close,
                        contentDescription =
                            stringResource(Res.string.podcast_group_remove, group.name),
                        modifier =
                            Modifier
                                .size(CLOSE_ICON_SIZE)
                                .clickable(role = Role.Button) { onRemoveGroup(group) },
                    )
                },
            )
        }
        NdAssistChip(
            onClick = onAddToGroups,
            label = stringResource(Res.string.podcast_add_group),
        )
    }
}

/**
 * The group's colour dot (08 ArtColors: the palette seed is re-toned, never raw — L* 40 on light,
 * 80 on dark; a `null` colour renders the theme's `primary`).
 */
@Composable
private fun GroupDot(colorArgb: Int?) {
    val dark = LocalSystemUiState.current.dark
    val color =
        colorArgb?.let { Color(ArtColors.tone(it, if (dark) DOT_TONE_DARK else DOT_TONE_LIGHT)) }
            ?: MaterialTheme.colorScheme.primary
    // The chip's leading slot is an uncentered icon-size box: center the smaller dot inside it.
    Box(Modifier.size(ICON_SLOT_SIZE), contentAlignment = Alignment.Center) {
        Box(Modifier.size(DOT_SIZE).clip(CircleShape).background(color))
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
private val HEADER_TOP_GAP = 48.dp
private val HERO_SIZE = 160.dp
private val COVER_TITLE_GAP = 16.dp
private val CHIP_GAP = 8.dp
private val META_GAP = 12.dp
private val DOT_SIZE = 12.dp
private val ICON_SLOT_SIZE = 18.dp
private val CLOSE_ICON_SIZE = 18.dp
private const val DOT_TONE_LIGHT = 40.0
private const val DOT_TONE_DARK = 80.0
private const val DESCRIPTION_LINES = 3

private const val BANNER_ASPECT = 6f
private const val BANNER_SCRIM = 0.4f
