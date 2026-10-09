// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common.art

/**
 * 08: a pure-Kotlin subset of UAX #29 grapheme segmentation — enough for a *leading* cluster
 * (combining marks, variation selectors, emoji modifiers, ZWJ sequences, tag sequences and
 * regional-indicator pairs stay with their base). `BreakIterator` is JVM-only and banned
 * in `commonMain`.
 */
public object Graphemes {
    /**
     * The first extended grapheme cluster of [text] (`""` when [text] is empty). Kept rules:
     * GB9 (× Extend/ZWJ/VS/emoji-modifier/tag), GB11 (ExtPict Extend* ZWJ × ExtPict) and
     * GB12/13 (RI × RI once).
     */
    public fun first(text: String): String {
        if (text.isEmpty()) return ""

        var i = charCount(codePointAt(text, 0))
        var regionalIndicators = if (isRegionalIndicator(codePointAt(text, 0))) 1 else 0

        while (i < text.length) {
            val cp = codePointAt(text, i)
            val next = i + charCount(cp)
            when {
                // GB9: extend marks, variation selectors, emoji modifiers, tag chars.
                isExtend(cp) || isVariationSelector(cp) || isEmojiModifier(cp) || isTag(cp) -> {
                    i = next
                }

                // GB9+GB11: ZWJ stays with its base; a following pictograph joins the cluster.
                cp == ZWJ -> {
                    i = next
                    if (i < text.length && isExtendedPictographic(codePointAt(text, i))) {
                        i += charCount(codePointAt(text, i))
                        continue
                    }
                    break
                }

                // GB12/13: two regional indicators pair (a flag), a third breaks.
                isRegionalIndicator(cp) && regionalIndicators % 2 == 1 -> {
                    regionalIndicators++
                    i = next
                }

                else -> {
                    break
                }
            }
        }
        return text.substring(0, i)
    }

    /** Whether [text]'s first code point satisfies [predicate] (`false` on empty input). */
    public fun startsWith(
        text: String,
        predicate: (Int) -> Boolean,
    ): Boolean = text.isNotEmpty() && predicate(codePointAt(text, 0))

    /** The UTF-16 code point at [index] (paired surrogates decode to the supplementary cp). */
    internal fun codePointAt(
        text: String,
        index: Int,
    ): Int {
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length) {
            val low = text[index + 1]
            if (low.isLowSurrogate()) {
                return (high.code shl 10) + low.code + SUPPLEMENTARY_OFFSET
            }
        }
        return high.code
    }

    private fun charCount(cp: Int): Int = if (cp >= SUPPLEMENTARY_START) 2 else 1

    // --- Category approximations for the leading-cluster subset ------------------

    /** Grapheme_Extend: combining marks (Mn/Me/Spare) the monogram must glue to its base. */
    private fun isExtend(cp: Int): Boolean =
        cp in 0x0300..0x036F || // combining diacritical marks
            cp in 0x0483..0x0489 || // Cyrillic
            cp in 0x0591..0x05BD || cp == 0x05BF || cp in 0x05C1..0x05C2 ||
            cp in 0x05C4..0x05C5 || cp == 0x05C7 || // Hebrew points
            cp in 0x0610..0x061A || cp in 0x064B..0x065F || cp == 0x0670 ||
            cp in 0x06D6..0x06ED || // Arabic marks
            cp in 0x1AB0..0x1AFF || // combining diacriticals extended
            cp in 0x1DC0..0x1DFF || // combining diacriticals supplement
            cp in 0x20D0..0x20FF || // combining marks for symbols
            cp in 0xFE20..0xFE2F // combining half marks

    /** Variation selectors VS1–16 and the supplement VS17–256. */
    private fun isVariationSelector(cp: Int): Boolean = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    /** Emoji modifiers (skin tones, Emod in UAX #29 terms). */
    private fun isEmojiModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    /** Tag characters (subdivision flags; the cancel tag E007F included). */
    private fun isTag(cp: Int): Boolean = cp in 0xE0020..0xE007F

    /** Regional indicators — flags pair two of these (GB12/13). */
    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    /**
     * "Extended pictographic" approximation: the emoji blocks and symbol ranges that can
     * stand after a ZWJ or open a cluster (the emoji test of `Monogram.spec` uses
     * [isEmojiLike], a superset for the first code point).
     */
    private fun isExtendedPictographic(cp: Int): Boolean = isEmojiLike(cp)

    /** Emoji by code-point ranges (08): misc symbols, dingbats and the SMP emoji blocks. */
    internal fun isEmojiLike(cp: Int): Boolean =
        cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 ||
            cp == 0x2139 || cp in 0x2194..0x2199 || cp in 0x21A9..0x21AA ||
            cp in 0x231A..0x231B || cp == 0x2328 || cp == 0x23CF || cp in 0x23E9..0x23F3 ||
            cp in 0x23F8..0x23FA || cp == 0x24C2 || cp in 0x25AA..0x25AB || cp == 0x25B6 ||
            cp == 0x25C0 || cp in 0x25FB..0x25FE || cp in 0x2600..0x27BF ||
            cp in 0x2934..0x2935 || cp in 0x2B00..0x2BFF || cp == 0x3030 || cp == 0x303D ||
            cp == 0x3297 || cp == 0x3299 || cp in 0x1F000..0x1FAFF

    private const val ZWJ = 0x200D
    private const val SUPPLEMENTARY_START = 0x10000
    private const val SUPPLEMENTARY_OFFSET = -0x35FDC00 // 0x10000 - (0xD800 shl 10) - 0xDC00
}
