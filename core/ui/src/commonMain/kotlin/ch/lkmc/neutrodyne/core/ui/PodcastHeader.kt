// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.podcast_episode_count
import ch.lkmc.neutrodyne.core.ui.resources.podcast_less
import ch.lkmc.neutrodyne.core.ui.resources.podcast_more
import ch.lkmc.neutrodyne.core.ui.resources.podcast_private
import ch.lkmc.neutrodyne.core.ui.resources.podcast_subscribed
import ch.lkmc.neutrodyne.core.ui.resources.podcast_updated
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 08 "Podcast header" — the first item of the podcast screen's `LazyColumn` (it scrolls away; the
 * pinned transparent top bar is the screen's). At M1a the header has no artwork-scheme tint (M10)
 * and no group chips (M5): the deviation is recorded in 08. Cover 160 dp at 24 dp corners, the
 * title `headlineSmall` (2 lines), author, the "Subscribed" chip, three lines of plain description
 * with a "More"/"Less" toggle, and the meta line "{n} episodes · Updated {relative} · Private".
 */
@Composable
public fun PodcastHeader(
    detail: PodcastDetail,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = HEADER_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(HEADER_TOP_GAP))
        CoverArt(
            ref = detail.artwork,
            monogram = rememberMonogram(detail.displayTitle),
            title = detail.displayTitle,
            tier = CoverTier.HERO,
            shape = NeutrodyneShapes.PlayerArt,
            avgArgb = null,
            modifier = Modifier.size(HERO_SIZE),
        )
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
        detail.description?.let { Description(it) }
        Spacer(Modifier.height(META_GAP))
        MetaLine(detail, nowMs)
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
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
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

/** The description as plain text — paragraphs and list items separated by newlines. */
private fun ShowNotes.plainText(): String = blocks.joinToString("\n") { it.plainText() }.trim()

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
private const val DESCRIPTION_LINES = 3
