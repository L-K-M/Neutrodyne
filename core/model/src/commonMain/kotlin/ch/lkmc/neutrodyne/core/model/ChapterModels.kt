// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Local enrichment; input order breaks duplicate-start ties within one source. */
data class EpisodeChapter(
    val startMs: Long,
    val endMs: Long?,
    val title: String?,
    val imageUrl: String?,
    val linkUrl: String?,
    val source: ChapterSource,
    val hidden: Boolean,
)

/** Zero-based index into the winning visible list; null state means no containing chapter. */
data class CurrentChapter(
    val episodeId: Long,
    val index: Int,
    val count: Int,
    val chapter: EpisodeChapter,
)
