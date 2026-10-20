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

/** Winning visible rows from ChapterRepository; callers own episode identity and seek policy. */
@Composable
public fun ChapterList(
    chapters: List<EpisodeChapter>,
    current: CurrentChapter?,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        itemsIndexed(chapters, key = { _, chapter -> chapter.startMs }) { index, chapter ->
            val fallback = stringResource(Res.string.chapter_number, index + 1)
            val title = chapter.title?.takeIf { it.isNotBlank() } ?: fallback
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
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    chapter.imageUrl?.let { AsyncImage(it, null, Modifier.size(40.dp)) }
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

/** Chapter URLs are plain images, outside the cover cache and its tiny-image rejection policy. */
@Composable
public fun ChapterArtwork(
    url: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.animation.Crossfade(url) { image ->
        AsyncImage(image, description, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60
