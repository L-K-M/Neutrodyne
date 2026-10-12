// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common.art

import ch.lkmc.neutrodyne.core.common.Nfc
import ch.lkmc.neutrodyne.core.model.art.MonogramSpec

/**
 * 08 "Initials"/"Hue": the deterministic podcast monogram — same input produces the same
 * [MonogramSpec] on every device and platform (NFC titles, a Java-compatible UTF-16 string
 * hash for the hue). Lives here and not in `:core:model` because it needs [Nfc].
 */
public object Monogram {
    /** The monogram spec for a podcast's display title (`customTitle ?: title`). */
    public fun spec(displayTitle: String): MonogramSpec {
        val nfc = Nfc.normalize(displayTitle)
        return MonogramSpec(initials = initialsOf(nfc), hue = hueOf(nfc))
    }

    /**
     * The initials rule (08): keep words whose first grapheme is a letter, digit or emoji; a
     * Han/Hiragana/Katakana/Hangul first word yields its single first grapheme; otherwise the
     * first grapheme of the first two kept words, upper-cased. [FALLBACK] when nothing is
     * usable. Examples: "The Daily" → "TD", "99% Invisible" → "9I", "🎧 Commute" → "🎧C",
     * "日本語ポッドキャスト" → "日", "Ärzte Talk" → "ÄT", "בוקר טוב" → "בט".
     */
    internal fun initialsOf(nfcTitle: String): String {
        val words =
            WORD_SPLIT.split(nfcTitle).filter { word ->
                Graphemes.startsWith(word) { cp -> isUsableStart(cp) }
            }
        if (words.isEmpty()) return FALLBACK

        if (Graphemes.startsWith(words[0], ::isIdeographicStart)) {
            return Graphemes.first(words[0])
        }
        val first = Graphemes.first(words[0])
        val second = words.getOrNull(1)?.let(Graphemes::first).orEmpty()
        return (first + second).uppercase()
    }

    /**
     * `floorMod(javaStringHash(nfc(title).lowercase()), 360)` (08): the hue is Java's specified
     * `String.hashCode` formula over UTF-16 code units in `Int` arithmetic, written out in
     * common code, so it is identical on Android, the desktop and a future non-JVM target.
     */
    internal fun hueOf(nfcTitle: String): Double = javaStringHash(nfcTitle.lowercase()).mod(HUE_TURN).toDouble()

    private fun javaStringHash(text: String): Int {
        var h = 0
        for (c in text) h = HASH_MULT * h + c.code
        return h
    }

    /** Word start must be a letter, a digit or an emoji (08). Letters/digits are BMP-only. */
    private fun isUsableStart(cp: Int): Boolean =
        (cp <= BMP_MAX && cp.toChar().isLetterOrDigit()) || Graphemes.isEmojiLike(cp)

    /** Han, Hiragana, Katakana or Hangul (08's one-grapheme scripts). */
    private fun isIdeographicStart(cp: Int): Boolean =
        cp in 0x3040..0x309F || // Hiragana
            cp in 0x30A0..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF65..0xFF9F || // Katakana
            cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF || // Han
            cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2EBEF || // Han extensions
            cp in 0x1100..0x11FF || cp in 0x3130..0x318F || cp in 0xAC00..0xD7AF || // Hangul
            cp in 0xA960..0xA97F || cp in 0xD7B0..0xD7FF

    /** No usable word → the neutral placeholder (08). */
    private const val FALLBACK = "#"

    /** Words split on whitespace and `-_/:|·•,` (08). */
    private val WORD_SPLIT = Regex("""[\s\-_/:|·•,]+""")

    private const val BMP_MAX = 0xFFFF
    private const val HASH_MULT = 31
    private const val HUE_TURN = 360
}
