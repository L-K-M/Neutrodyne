// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common.art

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 08's [MonogramTest] cases: the documented initials examples, the fallback and hue rules. */
class MonogramTest {
    @Test
    fun `documented initials examples`() {
        assertEquals("TD", Monogram.spec("The Daily").initials)
        assertEquals("9I", Monogram.spec("99% Invisible").initials)
        assertEquals("🎧C", Monogram.spec("🎧 Commute").initials)
        assertEquals("日", Monogram.spec("日本語ポッドキャスト").initials)
        assertEquals("ÄT", Monogram.spec("Ärzte Talk").initials)
        assertEquals("בט", Monogram.spec("בוקר טוב").initials)
    }

    @Test
    fun `decomposed titles normalise before initials`() {
        // "Ärzte" in NFD (A + combining diaeresis) must NFC to "Ä" before splitting.
        assertEquals("ÄT", Monogram.spec("Ärzte Talk").initials)
    }

    @Test
    fun `separator-only and empty titles fall back to hash`() {
        assertEquals("#", Monogram.spec("").initials)
        assertEquals("#", Monogram.spec("   ").initials)
        assertEquals("#", Monogram.spec("— · —").initials)
        assertEquals("#", Monogram.spec("!!!").initials)
    }

    @Test
    fun `a single kept word yields one grapheme`() {
        assertEquals("S", Monogram.spec("Solo").initials)
    }

    @Test
    fun `words starting with punctuation are dropped`() {
        // "'68" is not a word start (apostrophe isn't kept), so initials come from "Comeback".
        assertEquals("6C", Monogram.spec("68' Comeback").initials)
    }

    @Test
    fun `hyphenated and slashed titles split into words`() {
        assertEquals("RA", Monogram.spec("reply-all").initials)
        assertEquals("HN", Monogram.spec("Hacker/News").initials)
    }

    @Test
    fun `hue is the Java string hash of the lowercase NFC title mod 360`() {
        val title = "The Daily"
        val expected =
            title
                .lowercase()
                .hashCode()
                .mod(360)
                .toDouble()
        assertEquals(expected, Monogram.spec(title).hue)
    }

    @Test
    fun `hue is deterministic and in range`() {
        for (title in listOf("", "x", "🎧 Commute", "日本語ポッドキャスト", "Ärzte Talk")) {
            val hue = Monogram.spec(title).hue
            assertTrue(hue >= 0.0 && hue < 360.0, "hue $hue out of range for '$title'")
            assertEquals(hue, Monogram.spec(title).hue)
        }
    }
}
