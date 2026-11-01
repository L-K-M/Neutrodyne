// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterIndexTest {
    @Test fun priorityNeverMergesAndHiddenOnlyFallsBack() {
        val rows = listOf(row(0, ChapterSource.MP4), row(10, ChapterSource.ID3), row(20, ChapterSource.PSC), row(30))
        assertEquals(listOf(30L), ChapterIndex(rows).chapters.map { it.startMs })
        assertEquals(
            listOf(20L),
            ChapterIndex(
                rows.map {
                    if (it.source ==
                        ChapterSource.PODCASTING20_JSON
                    ) {
                        it.copy(hidden = true)
                    } else {
                        it
                    }
                },
            ).chapters.map { it.startMs },
        )
    }

    @Test fun emptyAndBeforeFirstHaveNoCurrent() {
        assertNull(ChapterIndex(emptyList()).current(0))
        assertNull(ChapterIndex(listOf(row(100))).current(99))
        assertNull(ChapterIndex(emptyList()).previous(10))
    }

    @Test fun sortedFirstDuplicateWinsAndInvalidStartDrops() {
        val index = ChapterIndex(listOf(row(20), row(-1), row(0), row(20).copy(title = "duplicate")))
        assertEquals(listOf(0L, 20L), index.chapters.map { it.startMs })
        assertNull(index.chapters.last().title)
        assertEquals(20, index.chapters.first().endMs)
    }

    @Test fun endExclusiveGapsAndUnknownFinalEnd() {
        val index = ChapterIndex(listOf(row(0).copy(endMs = 5), row(10)))
        assertEquals(0, index.current(4))
        assertNull(index.current(5))
        assertNull(index.current(9))
        assertEquals(1, index.current(Long.MAX_VALUE))
        assertEquals(5, index.nextBoundary(4))
        assertEquals(10, index.nextBoundary(5))
        assertNull(index.nextBoundary(10))
    }

    @Test fun hiddenIntervalRemainsAGap() {
        val index = ChapterIndex(listOf(row(0), row(10).copy(hidden = true), row(20)))
        assertNull(index.current(10))
        assertEquals(20, index.next(10))
        assertEquals(0, index.previous(10))
    }

    @Test fun invalidEndsUseNextAndLongEndsCap() {
        val index = ChapterIndex(listOf(row(0).copy(endMs = 99), row(10).copy(endMs = -1), row(20).copy(endMs = 20)))
        assertEquals(listOf(10L, 20L, null), index.chapters.map { it.endMs })
    }

    @Test fun previousStrictlyAfterThreeSecondsAndNextNeverWraps() {
        val index = ChapterIndex(listOf(row(10_000), row(20_000)))
        assertEquals(10_000, index.previous(23_000))
        assertEquals(20_000, index.previous(23_001))
        assertNull(index.previous(10_000))
        assertEquals(10_000, index.previous(13_001))
        assertNull(index.next(20_000))
        assertEquals(10_000, index.next(0))
    }

    private fun row(
        start: Long,
        source: ChapterSource = ChapterSource.PODCASTING20_JSON,
    ) = EpisodeChapter(start, null, null, null, null, source, false)
}
