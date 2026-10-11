// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every duration rule of 03 Durations. */
class DurationsTest {
    @Test
    fun hoursMinutesSeconds() {
        assertEquals(3_723_000L, Durations.parseMs("1:02:03"))
        assertEquals(52 * 60_000L + 57_000L, Durations.parseMs("00:52:57"))
    }

    @Test
    fun minutesSeconds() {
        assertEquals(75 * 60_000L + 12_000L, Durations.parseMs("75:12"))
        assertEquals(5 * 60_000L + 7_000L, Durations.parseMs("5:07"))
        assertEquals(4 * 60_000L + 2_000L, Durations.parseMs("4:2"))
    }

    @Test
    fun plainSeconds() {
        assertEquals(42_000L, Durations.parseMs("42"))
        assertEquals(960_000L, Durations.parseMs("960"))
    }

    @Test
    fun fractionalLastPart() {
        assertEquals(62_500L, Durations.parseMs("1:02.5"))
        assertEquals(3_723_500L, Durations.parseMs("1:02:03.5"))
        assertEquals(500L, Durations.parseMs("0.5"))
    }

    @Test
    fun fractionKeepsWholeMilliseconds() {
        // Integer arithmetic: "1.001" is 1,001 ms, not a truncated double.
        assertEquals(1_001L, Durations.parseMs("1.001"))
        assertEquals(1_001L, Durations.parseMs("0:00:01.001"))
        assertEquals(61_250L, Durations.parseMs("1:01.25"))
        assertEquals(1_234L, Durations.parseMs("1.234"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Durations.parseMs(""))
        assertNull(Durations.parseMs(" "))
        assertNull(Durations.parseMs("abc"))
        assertNull(Durations.parseMs("1:02:03:04"))
        assertNull(Durations.parseMs("-1:02"))
        assertNull(Durations.parseMs("1:2.5:3"))
        assertNull(Durations.parseMs("1e3"))
        assertNull(Durations.parseMs("+5"))
    }

    @Test
    fun allZeroPlaceholdersAreUnknown() {
        // Feeds emit "00:00"-style values as placeholders when no real duration exists:
        // they mean "unknown", and must not render as a zero-length episode.
        assertNull(Durations.parseMs("00:00"))
        assertNull(Durations.parseMs("0:00:00"))
        assertNull(Durations.parseMs("0"))
        assertNull(Durations.parseMs("00:00:00.000"))
        // A genuinely tiny duration is not a placeholder: the boundary is at zero, not below it.
        assertEquals(1L, Durations.parseMs("0.001"))
    }

    @Test
    fun shapeRejectionsAndLeniency() {
        // Empty components, stray separators and non-`digits.fraction` shapes reject.
        assertNull(Durations.parseMs(":30"))
        assertNull(Durations.parseMs("1:"))
        assertNull(Durations.parseMs("1::30"))
        assertNull(Durations.parseMs("1."))
        assertNull(Durations.parseMs("1.2.3"))
        assertNull(Durations.parseMs("1,5"))
        // `\d` is ASCII here: non-ASCII digits are not duration components.
        assertNull(Durations.parseMs("٤٢"))
        // Surrounding whitespace is trimmed — an accepted leniency, pinned deliberately.
        assertEquals(42_000L, Durations.parseMs(" 42"))
        assertEquals(42_000L, Durations.parseMs("42 "))
        // Components are not range-checked: "0:60" is a minute of sixty seconds = 60 s.
        assertEquals(60_000L, Durations.parseMs("0:60"))
        // Fractions longer than 3 digits truncate to whole milliseconds, not reject.
        assertEquals(1_999L, Durations.parseMs("1.9999"))
    }

    @Test
    fun rejectsOver48Hours() {
        assertNull(Durations.parseMs("49:00:00"))
        assertNull(Durations.parseMs("48:00:01"))
        assertEquals(48 * 3_600_000L, Durations.parseMs("48:00:00"))
        assertEquals(48 * 3_600_000L, Durations.parseMs("172800"))
        // The cap applies after the fraction, not before: values over the cap must not truncate in.
        assertEquals(48 * 3_600_000L - 1, Durations.parseMs("47:59:59.999"))
        assertNull(Durations.parseMs("172800.5"))
        assertNull(Durations.parseMs("48:00:00.1"))
        // Absurd magnitudes are rejected by the per-part cap before any multiplication — never wrapped.
        assertNull(Durations.parseMs("9223372036854775807"))
        assertNull(Durations.parseMs("999999999999999999999999"))
        // Same guarantee for colon forms, whose parts get multiplied afterwards.
        assertNull(Durations.parseMs("9223372036854775807:00"))
        assertNull(Durations.parseMs("9223372036854775807:00:00"))
    }
}
