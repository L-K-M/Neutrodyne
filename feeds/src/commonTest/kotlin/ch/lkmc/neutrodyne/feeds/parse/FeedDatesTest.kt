// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every date variant of 03 Dates, table-driven with fixed vectors (locale-invariant lowercase). */
class FeedDatesTest {
    @Test
    fun parsesStrictRfc822() {
        assertEquals(1792663200000L, FeedDates.parse("Sun, 22 Oct 2026 12:00:00 +0200"))
    }

    @Test
    fun rfc822Vectors() {
        // Sat, 03 Oct 2026 12:34:56 GMT (+0000), with .789 fraction.
        assertEquals(1791030896789L, FeedDates.parse("Sat, 03 Oct 2026 12:34:56.789 GMT"))
        assertEquals(1791030896000L, FeedDates.parse("Sat, 3 Oct 2026 12:34:56 UT"))
        assertEquals(1791030896000L, FeedDates.parse("Sat, 03 Oct 2026 12:34:56 UTC"))
        assertEquals(1791030896000L, FeedDates.parse("Sat, 03 Oct 2026 12:34:56 Z"))
        // Wrong weekday is dropped, not validated.
        assertEquals(1791030896000L, FeedDates.parse("Mié, 03 Oct 2026 12:34:56 +0000"))
        // Zone names map to offsets: PDT -0700, EST -0500.
        assertEquals(1791056096000L, FeedDates.parse("Sat, 03 Oct 2026 12:34:56 PDT"))
        assertEquals(1791048896000L, FeedDates.parse("Sat, 03 Oct 2026 12:34:56 EST"))
        // Offset with colon.
        assertEquals(1791023696000L, FeedDates.parse("Sat, 03 Oct 2026 12:34:56 +02:00"))
        // No seconds.
        assertEquals(1791030840000L, FeedDates.parse("Sat, 03 Oct 2026 12:34 +0000"))
        // Default offset is UTC when absent.
        assertEquals(1791030896000L, FeedDates.parse("Sat, 03 Oct 2026 12:34:56"))
        // Case-insensitive month.
        assertEquals(1791030896000L, FeedDates.parse("Sat, 03 oct 2026 12:34:56 +0000"))
    }

    @Test
    fun localisedMonths() {
        assertEquals(1791030896000L, FeedDates.parse("Sa., 03 Okt 2026 12:34:56 +0000"))
        assertEquals(1791030896000L, FeedDates.parse("sam., 03 oct. 2026 12:34:56 +0000"))
        assertEquals(1767443696000L, FeedDates.parse("mer., 03 janv. 2026 12:34:56 +0000"))
        assertEquals(1777811696000L, FeedDates.parse("03 Mai 2026 12:34:56 +0000"))
    }

    @Test
    fun twoDigitYearsFollowRfc5322() {
        // 00–49 → 20xx, 50–99 → 19xx (RFC 5322 §4.3).
        assertEquals(981201600000L, FeedDates.parse("Sat, 03 Feb 01 12:00:00 +0000"))
        assertEquals(-567864000000L, FeedDates.parse("Sat, 03 Jan 52 12:00:00 +0000"))
    }

    @Test
    fun iso8601Vectors() {
        assertEquals(1791030896789L, FeedDates.parse("2026-10-03T12:34:56.789Z"))
        assertEquals(1791023696789L, FeedDates.parse("2026-10-03T12:34:56.789+02:00"))
        assertEquals(1791023696000L, FeedDates.parse("2026-10-03T12:34:56+02:00"))
        assertEquals(1791030896000L, FeedDates.parse("2026-10-03t12:34:56z"))
    }

    @Test
    fun isoWithoutOffsetIsUtc() {
        assertEquals(1791030896000L, FeedDates.parse("2026-10-03T12:34:56"))
    }

    @Test
    fun isoDateOnlyIsUtcMidnight() {
        assertEquals(1790985600000L, FeedDates.parse("2026-10-03"))
    }

    @Test
    fun isoSpaceSeparator() {
        assertEquals(1791030896000L, FeedDates.parse("2026-10-03 12:34:56"))
        assertEquals(1791023696000L, FeedDates.parse("2026-10-03 12:34:56 +02:00"))
    }

    @Test
    fun isoSpaceBeforeColonlessOffset() {
        // "YYYY-MM-DD HH:MM:SS ±HHMM" needs the space-strip and the colon insertion to compose.
        assertEquals(
            FeedDates.parse("2024-06-15T12:00:00+02:00"),
            FeedDates.parse("2024-06-15 12:00:00 +0200"),
        )
        assertEquals(
            FeedDates.parse("2026-10-03T12:34:56-07:00"),
            FeedDates.parse("2026-10-03 12:34:56 -0700"),
        )
        assertEquals(
            FeedDates.parse("2026-10-03T12:34:56Z"),
            FeedDates.parse("2026-10-03 12:34:56 Z"),
        )
    }

    @Test
    fun whitespaceCollapses() {
        assertEquals(1791030896000L, FeedDates.parse("  Sat,   03   Oct   2026  12:34:56  +0000  "))
    }

    @Test
    fun dayIsValidatedAgainstTheMonth() {
        assertNull(FeedDates.parse("Mon, 31 Sep 2026 12:00:00 +0000")) // September has 30 days
        assertNull(FeedDates.parse("Mon, 30 Feb 2026 12:00:00 +0000"))
        assertEquals(1835438400000L, FeedDates.parse("Tue, 29 Feb 2028 12:00:00 +0000")) // leap year
    }

    @Test
    fun hourAndMinuteBounds() {
        assertNull(FeedDates.parse("Sat, 03 Oct 2026 24:00:00 +0000"))
        assertNull(FeedDates.parse("Sat, 03 Oct 2026 12:60:00 +0000"))
        assertNull(FeedDates.parse("Sat, 03 Oct 2026 12:00:60 +0000"))
    }

    @Test
    fun garbageYieldsNull() {
        assertNull(FeedDates.parse(""))
        assertNull(FeedDates.parse("next tuesday"))
        assertNull(FeedDates.parse("Sat, 03 Oct 2026"))
        assertNull(FeedDates.parse("16:9"))
    }

    @Test
    fun outOfRangeOffsetsYieldNull() {
        // An offset past ±18 h must not throw; the date is simply unknown (UNKNOWN_DATE upstream).
        assertNull(FeedDates.parse("Sat, 03 Oct 2026 12:34:56 +1900"))
        assertNull(FeedDates.parse("Sat, 03 Oct 2026 12:34:56 +9900"))
        assertNull(FeedDates.parse("Sat, 03 Oct 2026 12:34:56 -1900"))
        assertNull(FeedDates.parse("Sat, 03 Oct 2026 12:34:56 +19:00"))
        assertNull(FeedDates.parse("2026-10-03T12:34:56+25:00"))
    }

    @Test
    fun dateOnlyFallbackRejectsTrailingGarbage() {
        // The date-only fallback consumes the whole input: trailing garbage is not a date.
        assertNull(FeedDates.parse("2026-10-03 garbage"))
        assertNull(FeedDates.parse("2026-10-03 24:00:00"))
        assertNull(FeedDates.parse("2026-10-03junk"))
        assertEquals(1790985600000L, FeedDates.parse("2026-10-03"))
    }
}
