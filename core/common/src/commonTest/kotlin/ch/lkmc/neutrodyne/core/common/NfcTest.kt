// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NfcTest {
    @Test
    fun `decomposed input normalizes to composed form`() {
        // "Café" with a combining acute accent (NFD) becomes the single composed code point.
        assertEquals("Caf\u00E9", Nfc.normalize("Cafe\u0301"))
    }

    @Test
    fun `already-composed input is unchanged`() {
        assertEquals("Caf\u00E9", Nfc.normalize("Caf\u00E9"))
    }

    @Test
    fun `composed and decomposed forms compare equal after normalize`() {
        val decomposed = Nfc.normalize("A\u0308a")
        val composed = Nfc.normalize("\u00C4a")
        assertEquals(composed, decomposed)
        assertNotEquals("A\u0308a", composed)
    }
}

class DateFormatterTest {
    @Test
    fun `formats a fixed epoch day deterministically`() {
        // 2026-10-04T00:00:00Z — localised output, so assert invariants, not the literal string.
        val day = DateFormatter.date(1_791_072_000_000L)
        val nextDay = DateFormatter.date(1_791_158_400_000L)
        assertTrue(day.isNotBlank())
        assertNotEquals(day, nextDay)
    }
}
