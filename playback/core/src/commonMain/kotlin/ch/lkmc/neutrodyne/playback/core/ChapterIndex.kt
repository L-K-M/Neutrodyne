// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter

/** Immutable winning list. Gaps and hidden intervals have no current chapter. */
class ChapterIndex(
    rows: List<EpisodeChapter>,
) {
    val chapters: List<EpisodeChapter> = ChapterSource.winning(rows)

    fun current(positionMs: Long): Int? {
        val candidate = insertionPoint(positionMs) - 1
        val chapter = chapters.getOrNull(candidate) ?: return null
        val end = chapter.endMs
        return candidate.takeIf { end == null || positionMs < end }
    }

    fun next(positionMs: Long): Long? = chapters.getOrNull(insertionPoint(positionMs))?.startMs

    fun previous(positionMs: Long): Long? {
        val current = current(positionMs)
        if (current == null) return chapters.getOrNull(insertionPoint(positionMs) - 1)?.startMs
        val chapter = chapters[current]
        return if (positionMs - chapter.startMs >
            RESTART_AFTER_MS
        ) {
            chapter.startMs
        } else {
            chapters.getOrNull(current - 1)?.startMs
        }
    }

    /** Include explicit ends: the state must clear in a gap without waiting for the next start. */
    fun nextBoundary(positionMs: Long): Long? {
        val end = current(positionMs)?.let { chapters[it].endMs }?.takeIf { it > positionMs }
        return listOfNotNull(end, next(positionMs)).minOrNull()
    }

    private fun insertionPoint(positionMs: Long): Int {
        var low = 0
        var high = chapters.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (chapters[middle].startMs <= positionMs) low = middle + 1 else high = middle
        }
        return low
    }

    private companion object {
        const val RESTART_AFTER_MS = 3_000L
    }
}
