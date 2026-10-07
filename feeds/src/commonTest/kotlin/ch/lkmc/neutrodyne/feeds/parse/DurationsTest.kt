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
    fun rejectsOver48Hours() {
        assertNull(Durations.parseMs("49:00:00"))
        assertNull(Durations.parseMs("48:00:01"))
        assertEquals(48 * 3_600_000L, Durations.parseMs("48:00:00"))
        assertEquals(48 * 3_600_000L, Durations.parseMs("172800"))
    }
}
