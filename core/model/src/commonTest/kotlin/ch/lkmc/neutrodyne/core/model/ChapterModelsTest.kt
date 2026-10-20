// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterModelsTest {
    @Test fun firstVisibleSourceWinsWithoutMerging() {
        val rows = ChapterSource.entries.map { chapter(0, it) }
        for (source in ChapterSource.entries) {
            val remaining = rows.filter { it.source.ordinal >= source.ordinal }
            assertEquals(listOf(chapter(0, source)), ChapterSource.winning(remaining.reversed()))
        }
        assertEquals(
            listOf(chapter(0, ChapterSource.PSC)),
            ChapterSource.winning(
                listOf(
                    chapter(0).copy(hidden = true),
                    chapter(0, ChapterSource.PSC),
                ),
            ),
        )
        assertTrue(ChapterSource.winning(listOf(chapter(-1), chapter(0).copy(hidden = true))).isEmpty())
    }

    @Test fun normalizationKeepsFirstDuplicateAndDefinesEndsGapsAndHiddenBoundaries() {
        val rows =
            listOf(
                chapter(30),
                chapter(10).copy(endMs = 15),
                chapter(10).copy(title = "duplicate"),
                chapter(-1),
                chapter(20).copy(endMs = 19, hidden = true),
                chapter(40).copy(endMs = 99),
                chapter(50),
            )
        val normalized = ChapterSource.normalize(rows)
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), normalized.map { it.startMs })
        assertEquals(listOf(15L, 30L, 40L, 50L, null), normalized.map { it.endMs })
        assertEquals(listOf(10L, 30L, 40L, 50L), ChapterSource.winning(rows).map { it.startMs })
        assertEquals(normalized, ChapterSource.normalize(normalized))
    }

    @Test fun minutesValidationRejectsBoundariesAndOverflowInputs() {
        assertEquals(
            listOf(false, false, true, true, false, false),
            listOf(Int.MIN_VALUE, 0, 1, 240, 241, Int.MAX_VALUE).map(SleepTimerMode::isValidMinutes),
        )
    }

    private fun chapter(
        start: Long,
        source: ChapterSource = ChapterSource.PODCASTING20_JSON,
    ) = EpisodeChapter(start, null, null, null, null, source, false)
}
