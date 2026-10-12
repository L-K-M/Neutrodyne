// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The timestamp grammar of 03 Timestamp linkifier: what matches and what must not. */
class TimestampLinkifierTest {
    private fun timestamps(text: String): List<Pair<String, Long>> =
        TimestampLinkifier.linkify(text).filterIsInstance<NoteSpan.Timestamp>().map { it.text to it.positionMs }

    @Test
    fun hourMinuteSecond() {
        assertEquals(listOf("1:02:03" to 3_723_000L), timestamps("skip to 1:02:03 now"))
    }

    @Test
    fun minuteSecondWithLargeMinutes() {
        assertEquals(listOf("75:12" to 4_512_000L), timestamps("75:12 in"))
        assertEquals(listOf("4:02" to 242_000L), timestamps("4:02"))
        assertEquals(listOf("999:59" to 59_999_000L), timestamps("999:59"))
    }

    @Test
    fun secondsMustBeTwoDigitsSoRatiosNeverMatch() {
        assertEquals(emptyList(), timestamps("aspect ratio 16:9"))
        assertEquals(emptyList(), timestamps("v1.2:3"))
        // Out-of-range seconds never match — "10:60" is not a timestamp.
        assertEquals(emptyList(), timestamps("at 10:60"))
        // Position zero is a valid seek target, unlike "0:00"-as-duration placeholders.
        assertEquals(listOf("0:00" to 0L), timestamps("intro 0:00"))
    }

    @Test
    fun timesOfDayAreExcluded() {
        assertEquals(emptyList(), timestamps("listen at 10:30 am"))
        assertEquals(emptyList(), timestamps("opens 10:30 a.m."))
        // The trailing period is often dropped in real show notes.
        assertEquals(emptyList(), timestamps("opens 10:30 a.m"))
        assertEquals(emptyList(), timestamps("closes 3:15 p.m"))
        assertEquals(emptyList(), timestamps("meeting 2:45 pm"))
        assertEquals(emptyList(), timestamps("sendezeit 20:15 Uhr"))
        assertEquals(emptyList(), timestamps("runtime 10:30h"))
        // Case-insensitive markers ("AM"/"uhr") suppress identically.
        assertEquals(emptyList(), timestamps("listen at 10:30 AM"))
        assertEquals(emptyList(), timestamps("sendezeit 20:15 uhr"))
        assertEquals(listOf("10:30" to 630_000L), timestamps("plain 10:30 stays"))
    }

    @Test
    fun timesOfDayExcludedAcrossRepeatedAndNonBreakingWhitespace() {
        // The exclusion tolerates any whitespace run — including NBSP and the narrow no-break
        // space common in typeset German ("20:15 Uhr").
        assertEquals(emptyList(), timestamps("listen at 10:30  am"))
        assertEquals(emptyList(), timestamps("listen at 10:30\u00A0am"))
        assertEquals(emptyList(), timestamps("sendezeit 20:15\u00A0Uhr"))
        assertEquals(emptyList(), timestamps("runtime 10:30\u202Fh"))
        // Whitespace before a word that merely starts with h must still linkify.
        assertEquals(listOf("12:34" to 754_000L), timestamps("at 12:34  hello"))
    }

    @Test
    fun boundaryLookbehind() {
        // A digit, colon or dot before the timestamp blocks the match.
        assertEquals(emptyList(), timestamps("2026:10:03"))
        assertEquals(emptyList(), timestamps("1.10:30"))
        // A digit or colon after it blocks too.
        assertEquals(emptyList(), timestamps("10:301"))
        assertEquals(emptyList(), timestamps("10:30:"))
    }

    @Test
    fun isoDatesAndAdjacentLettersDoNotMatch() {
        // The `T` between date and time is a letter — word-char boundaries exclude the whole
        // ISO timestamp instead of linkifying its time part.
        assertEquals(emptyList(), timestamps("Premiere: 2026-10-06T10:30:15Z"))
        assertEquals(emptyList(), timestamps("Live am 2026-10-06T20:15"))
        // Letters glued directly to the digits are not word boundaries either.
        assertEquals(emptyList(), timestamps("x10:30"))
        assertEquals(emptyList(), timestamps("10:30x"))
        // Punctuation and real text still leave the timestamp a candidate.
        assertEquals(listOf("10:30" to 630_000L), timestamps("(10:30)"))
        assertEquals(listOf("75:12" to 4_512_000L), timestamps("at 75:12!"))
        assertEquals(listOf("1:23:45" to 5_025_000L), timestamps("seek 1:23:45."))
    }

    @Test
    fun markerTailSeesUnicodeLetters() {
        // "h" glued to an umlaut word is prose — the ASCII \w class would miss the tail.
        assertEquals(listOf("10:30" to 630_000L), timestamps("10:30 hört"))
        assertEquals(listOf("20:15" to 1_215_000L), timestamps("20:15 Uhrzeiten"))
        // Real whole-word markers still suppress.
        assertEquals(emptyList(), timestamps("10:30 h"))
        assertEquals(emptyList(), timestamps("10:30h"))
        assertEquals(emptyList(), timestamps("10:30 Uhr"))
        assertEquals(emptyList(), timestamps("10:30 p.m."))
    }

    @Test
    fun multipleTimestampsWithTextSpans() {
        val spans = TimestampLinkifier.linkify("Intro 0:30, then 1:15:00 end")
        val stamps = spans.filterIsInstance<NoteSpan.Timestamp>()
        assertEquals(2, stamps.size)
        assertEquals(30_000L, stamps[0].positionMs)
        assertEquals(4_500_000L, stamps[1].positionMs)
        val texts = spans.filterIsInstance<NoteSpan.Text>()
        assertTrue(texts.any { "Intro" in it.text })
        assertTrue(texts.any { ", then " in it.text })
        assertTrue(texts.any { " end" in it.text })
    }

    @Test
    fun styleCarriesOntoTextSpans() {
        val spans = TimestampLinkifier.linkify("at 2:30", ShowNotesStyles.BOLD)
        val text = spans.filterIsInstance<NoteSpan.Text>().first()
        assertEquals(ShowNotesStyles.BOLD, text.style)
    }

    @Test
    fun emptyInput() {
        assertEquals(emptyList(), TimestampLinkifier.linkify(""))
        // No stamps: the whole text stays one Text span.
        assertEquals(
            listOf(NoteSpan.Text("no stamps here")),
            TimestampLinkifier.linkify("no stamps here"),
        )
    }

    @Test
    fun timeOfDayLookaheadStopsAtLineBreaks() {
        // "Am" starting the next paragraph is prose, not a suffix: only horizontal whitespace may
        // stand between the timestamp and the excluded word.
        assertEquals(listOf("12:34" to 754_000L), timestamps("12:34\n\nAm Anfang sprechen wir darüber"))
        // Same-line suffixes still suppress — a space run and NBSP are horizontal whitespace.
        assertEquals(emptyList(), timestamps("10:30\u00A0am"))
        assertEquals(emptyList(), timestamps("20:15  Uhr"))
    }

    @Test
    fun timeOfDayMarkersRequireWholeWords() {
        // A word merely starting with a marker is prose, not a suffix.
        assertEquals(listOf("10:30" to 630_000L), timestamps("10:30 ample"))
        assertEquals(listOf("10:30" to 630_000L), timestamps("10:30 American"))
        assertEquals(listOf("10:30" to 630_000L), timestamps("10:30 Uhrwerk"))
        assertEquals(listOf("2:45" to 165_000L), timestamps("2:45 pmm"))
        // Whole-word markers still suppress.
        assertEquals(emptyList(), timestamps("10:30 am"))
        assertEquals(emptyList(), timestamps("10:30 a.m."))
        assertEquals(emptyList(), timestamps("20:15 Uhr"))
    }

    @Test
    fun timestampCarriesTheEnclosingStyle() {
        // A timestamp inside bold/italic keeps the emphasis (the seek affordance is a style
        // on top, not a reason to drop the run's bits).
        val spans = TimestampLinkifier.linkify("bold 12:34 end", style = ShowNotesStyles.BOLD)
        val stamp = spans.filterIsInstance<NoteSpan.Timestamp>().single()
        assertEquals(ShowNotesStyles.BOLD, stamp.style)
        assertEquals(
            ShowNotesStyles.NONE,
            TimestampLinkifier
                .linkify("plain 12:34")
                .filterIsInstance<NoteSpan.Timestamp>()
                .single()
                .style,
        )
    }

    @Test
    fun nonAsciiDigitsNeverLinkify() {
        // Android's ICU regex reads \d as any Unicode decimal digit; the pattern must stay
        // ASCII so a supplementary digit (mathematical bold 1 and 0) never reaches toLong().
        for (text in listOf("\uD835\uDFCF\uD835\uDFCE:30", "10:3\uD835\uDFCE", "\u0661\u0662:34:56")) {
            val spans = TimestampLinkifier.linkify(text)
            assertEquals(listOf<NoteSpan>(NoteSpan.Text(text, ShowNotesStyles.NONE)), spans, text)
        }
    }
}
