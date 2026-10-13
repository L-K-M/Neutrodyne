// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common.art

import ch.lkmc.neutrodyne.core.common.Nfc
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
        // "'68" is dropped: an apostrophe is not a letter, digit or emoji start.
        assertEquals("C", Monogram.spec("'68 Comeback").initials)
        // A trailing apostrophe stays inside the word: "68'" still starts with a digit.
        assertEquals("6C", Monogram.spec("68' Comeback").initials)
    }

    @Test
    fun `hyphenated and slashed titles split into words`() {
        assertEquals("RA", Monogram.spec("reply-all").initials)
        assertEquals("HN", Monogram.spec("Hacker/News").initials)
    }

    @Test
    fun `hue is the Java string hash of the lowercase NFC title mod 360`() {
        val titles =
            listOf(
                "The Daily",
                "the daily",
                "THE DAILY",
                "99% Invisible",
                "🎧 Commute",
                "日本語ポッドキャスト",
                "Ärzte Talk",
                "A\u0308rzte Talk",
                "ärzte talk",
                "ÄRZTE TALK",
                "בוקר טוב",
                "",
                "   ",
                "— · —",
                "!!!",
                "Solo",
                "reply-all",
                "Hacker/News",
                "68' Comeback",
                "Café",
                "Cafe\u0301",
                "CAFÉ",
                "mañana",
                "man\u0303ana",
                "Ångström",
                "A\u030angstro\u0308m",
                "İstanbul",
                "istanbul",
                "Straße",
                "STRASSE",
                "Ελληνικά",
                "Καλημέρα",
                "Привет мир",
                "한국어",
                "中文播客",
                "العربية",
                "हिन्दी",
                "ไทย",
                "👨‍👩‍👧‍👦 Family",
                "🇨🇭 News",
                "👍🏽 Talk",
                "🎙️ Radio",
                "Music 𝄞",
                "Science 🚀",
                "a".repeat(1000),
                "Aa",
                "BB",
                "0",
                "Podcast #50",
                "\u0000 edge",
            )
        assertEquals(50, titles.size)
        for (title in titles) {
            // On the desktop JVM, String.hashCode() is Java's UTF-16 hash, including overflow.
            val expected =
                Nfc
                    .normalize(title)
                    .lowercase()
                    .hashCode()
                    .mod(360)
                    .toDouble()
            assertEquals(expected, Monogram.spec(title).hue, "title '$title'")
        }
        assertEquals(Monogram.spec("Ärzte Talk").hue, Monogram.spec("A\u0308rzte Talk").hue)
        assertEquals(Monogram.spec("Ärzte Talk").hue, Monogram.spec("ÄRZTE TALK").hue)
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
