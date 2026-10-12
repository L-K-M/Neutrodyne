// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common.art

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 08's [GraphemesTest] cases: combining marks, variation selectors, ZWJ family emoji, flags,
 * skin-tone modifiers, tag sequences, Hangul/Han first characters and empty input.
 */
class GraphemesTest {
    @Test
    fun `empty input yields empty`() {
        assertEquals("", Graphemes.first(""))
        assertFalse(Graphemes.startsWith("") { true })
    }

    @Test
    fun `ascii letters are single clusters`() {
        assertEquals("T", Graphemes.first("The"))
        assertEquals("9", Graphemes.first("99%"))
    }

    @Test
    fun `combining marks join their base`() {
        // e + combining acute is one cluster; the next letter starts a new one.
        assertEquals("é", Graphemes.first("éx"))
        assertEquals("ä", Graphemes.first("äbout"))
    }

    @Test
    fun `variation selector joins its base`() {
        // Heavy black heart + VS16 renders as one emoji cluster.
        assertEquals("❤️", Graphemes.first("❤️x"))
    }

    @Test
    fun `zwj sequences stay one cluster`() {
        // Family emoji: man ZWJ woman ZWJ girl.
        assertEquals("👨‍👩‍👧", Graphemes.first("👨‍👩‍👧!"))
    }

    @Test
    fun `regional indicators pair into one flag`() {
        assertEquals("🇩🇪", Graphemes.first("🇩🇪x"))
        // A third indicator breaks — DE pairs, FR starts the next cluster.
        assertEquals("🇩🇪", Graphemes.first("🇩🇪🇫🇷"))
    }

    @Test
    fun `skin tone modifier joins the emoji`() {
        assertEquals("👍🏽", Graphemes.first("👍🏽!"))
    }

    @Test
    fun `tag sequence joins the flag base`() {
        // Waving black flag + "gbeng" tag letters + cancel tag = the England subdivision flag.
        val england = "🏴" + "󠁧󠁢󠁥󠁮󠁧" + "󠁿"
        assertEquals(england, Graphemes.first(england + "x"))
    }

    @Test
    fun `han and hangul first characters`() {
        assertEquals("日", Graphemes.first("日本語"))
        assertEquals("한", Graphemes.first("한국어"))
        assertEquals("あ", Graphemes.first("あいう"))
    }

    @Test
    fun `supplementary letters are one cluster`() {
        // Deseret capital O (surrogate pair) reads as one grapheme.
        assertEquals("𐐎", Graphemes.first("𐐎nes"))
    }

    @Test
    fun `dangling zwj does not swallow the next word boundary`() {
        // ZWJ followed by a non-pictograph letter does not merge.
        val cluster = Graphemes.first("👨‍x")
        assertEquals("👨‍", cluster)
    }

    @Test
    fun `startsWith tests the first code point`() {
        assertTrue(Graphemes.startsWith("🎧 Show") { it == 0x1F3A7 })
        assertTrue(Graphemes.startsWith("9lives") { it.toChar().isDigit() })
        assertFalse(Graphemes.startsWith("!bang") { it.toChar().isLetterOrDigit() })
    }
}
