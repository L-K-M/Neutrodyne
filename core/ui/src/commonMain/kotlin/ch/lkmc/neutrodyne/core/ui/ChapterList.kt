// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.chapter_number
import ch.lkmc.neutrodyne.core.ui.resources.chapter_seek
import ch.lkmc.neutrodyne.core.ui.resources.chapter_time
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource

/**
 * The winning source's visible rows (06 Chapters); callers own episode identity and seek policy.
 * A row announces itself as a list item plus a "Seek to {title}" action and highlights the row
 * whose `startMs` matches [current]'s chapter.
 */
@Composable
public fun ChapterList(
    chapters: List<EpisodeChapter>,
    current: CurrentChapter?,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        itemsIndexed(chapters, key = { _, chapter -> chapter.startMs }) { index, chapter ->
            val title = chapter.title?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.chapter_number, index + 1)
            val selected = current?.chapter?.startMs == chapter.startMs
            val seekLabel = stringResource(Res.string.chapter_seek, title)
            val color =
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            Surface(color = color) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = seekLabel) { onSeek(chapter.startMs) }
                        .semantics { this.selected = selected }
                        .padding(CHAPTER_ROW_PADDING),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CHAPTER_ROW_PADDING),
                ) {
                    chapter.imageUrl?.let { AsyncImage(it, null, Modifier.size(CHAPTER_IMAGE)) }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        val seconds = chapter.startMs / MILLIS_PER_SECOND
                        val minutes = seconds / SECONDS_PER_MINUTE
                        val remainder = (seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')
                        Text(
                            stringResource(Res.string.chapter_time, minutes, remainder),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private val CHAPTER_ROW_PADDING = 12.dp
private val CHAPTER_IMAGE = 40.dp
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60
