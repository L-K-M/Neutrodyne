// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeChaptersTest {
    private val valid = "0:00 Intro\n0:10 Next\n0:20 End"

    @Test fun titlesSeparatorsAndNextStartBounds() {
        assertEquals(3, YouTubeChapters.parse("$valid\n🔗https://example.test\n🎧: Listen", null).size)
        val text = "Heading\r\n 0:00 - Intro\r0:10–Next\n\t0:20 — End\nLinks below"
        assertEquals(
            listOf(ChapterSpec(0, "Intro"), ChapterSpec(10_000, "Next"), ChapterSpec(20_000, "End")),
            YouTubeChapters.parse(text, 30_000),
        )
        for (separator in listOf("", "-", "–", "—", "|", ":")) {
            assertEquals(
                listOf("Intro", "Next", "End"),
                YouTubeChapters
                    .parse(
                        "0:00 $separator Intro\n0:10 $separator Next\n0:20 $separator End",
                        null,
                    ).map { it.title },
            )
        }
    }

    @Test fun exactAsciiClockGrammarIncludesLongMinutesAndHours() {
        assertEquals(
            listOf(0L, 75 * 60_000L + 12_000, 99 * 3_600_000L + 59 * 60_000L + 59_000),
            YouTubeChapters.parse("00:00 Start\n75:12 Long\n99:59:59 Last", null).map { it.startMs },
        )
        assertEquals(999 * 60_000L + 59_000, YouTubeChapters.parse("0:00 A\n1:00:00 B\n999:59 C", null).last().startMs)
        assertEquals(3, YouTubeChapters.parse("0:00 A\n0:10 ample\n0:20 hört", null).size)
    }

    @Test fun insufficientOrUnorderedOrShortListsFailEntirely() {
        for (text in listOf(
            "",
            "0:00 A\n0:10 B",
            "0:01 A\n0:11 B\n0:21 C",
            "0:00 A\n0:09 B\n0:20 C",
            "$valid\n0:20 Duplicate",
            "$valid\n0:15 Descending",
            "0:00 A\n0:10 B\n0:10 Duplicate\n0:30 C",
        )) {
            assertTrue(YouTubeChapters.parse(text, null).isEmpty(), text)
        }
    }

    @Test fun malformedTailCannotLeavePlausiblePrefix() {
        for (token in listOf(
            "0:60",
            "1:60:00",
            "100:00:00",
            "1000:00",
            "99999999999999999999999:00",
            "1:2",
            "1:02:03:04",
            "0:30.5",
            "0:30s",
            "０:３０",
            "𝟙:30",
            "0::30",
            "-0:30",
            "+0:30",
        )) {
            assertTrue(YouTubeChapters.parse("$valid\n$token Invalid", null).isEmpty(), token)
        }
        assertTrue(YouTubeChapters.parse("$valid\n0:30", null).isEmpty())
        for (emptyTitle in listOf("0:30 -", "0:30 |", "0:30:")) {
            assertTrue(YouTubeChapters.parse("$valid\n$emptyTitle", null).isEmpty())
        }
    }

    @Test fun clockMarkersUseLinkifierHorizontalWhitespaceAndWordRules() {
        for (suffix in listOf("am", "PM", "Uhr", "h", "a.m.", "p.m")) {
            for (space in listOf(" ", "\t", "\u00a0", "\u180e", "\u202f")) {
                assertTrue(YouTubeChapters.parse("0:00 Start\n0:10${space}$suffix\n0:20 End", null).isEmpty())
            }
        }
        assertTrue(YouTubeChapters.parse("prefix 0:00 Start\nprefix 0:10 Next\nprefix 0:20 End", null).isEmpty())
        assertEquals(3, YouTubeChapters.parse("0:00\u180eIntro\n0:10\u00a0Next\n0:20\u202fEnd", null).size)
    }

    @Test fun knownDurationBoundsIncludeFinalMinimumInterval() {
        for (duration in listOf(Long.MIN_VALUE, -1, 0, 20_000, 29_999)) {
            assertTrue(YouTubeChapters.parse(valid, duration).isEmpty(), "$duration")
        }
        assertEquals(3, YouTubeChapters.parse(valid, 30_000).size)
        assertEquals(3, YouTubeChapters.parse(valid, Long.MAX_VALUE).size)
        assertEquals(3, YouTubeChapters.parse(valid, null).size)
    }

    @Test fun boundedInputFailsRatherThanTruncatingAList() {
        val thousand = (0 until 1_000).joinToString("\n") { "${it / 6}:${it % 6}0 Chapter $it" }
        assertEquals(1_000, YouTubeChapters.parse(thousand, null).size)
        assertTrue(YouTubeChapters.parse("$thousand\n166:40 Overflow", null).isEmpty())
        assertTrue(YouTubeChapters.parse(valid + "\n" + "x".repeat(2 * 1024 * 1024), null).isEmpty())
        assertTrue(YouTubeChapters.parse(valid + "\n" + "é".repeat(1024 * 1024), null).isEmpty())
        val atCap = valid + "\n" + "x".repeat(2 * 1024 * 1024 - valid.length - 1)
        assertEquals(3, YouTubeChapters.parse(atCap, null).size)
    }
}
