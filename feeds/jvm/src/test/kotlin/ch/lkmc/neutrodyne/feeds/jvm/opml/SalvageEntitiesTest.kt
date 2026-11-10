// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The salvage entity decoder's bound and semantics, proven on the decoder itself: a
 * counting [CharSequence] bills every character read, so the `;` window's linear bound is
 * a property of the algorithm rather than a loaded machine's wall clock. This replaces
 * the timing ratio the hostile suite ran (it flaked on a loaded CI worker at 3.10/3
 * while the algorithm stayed linear — CI run 42755872530, job 129629997601).
 */
class SalvageEntitiesTest {
    /**
     * `&`-dense values whose only `;` sits at the very end: each `&` may rescan at most
     * [MAX_ENTITY_CHARS] neighbours, so the largest admitted value (the 64 KiB salvage
     * tag cap) costs a linear number of reads and stays literal. Reverting the window to
     * the former unbounded `indexOf` reads ~n²/2 characters through this same seam —
     * the assertion fails by orders of magnitude.
     */
    @Test(timeout = 60_000)
    fun ampDenseValuesReadLinearly() {
        val literal = "&".repeat(ADMITTED_VALUE_CHARS) + ";"
        val counted = CountingChars(literal)

        val decoded = unescapeEntities(counted)

        assertEquals(literal, decoded, "invalid dense references stay literal")
        val bound = ADMITTED_VALUE_CHARS.toLong() * (MAX_ENTITY_CHARS + COUNTED_PASSES)
        println(
            "ampDenseValuesReadLinearly: reads=${counted.reads} " +
                "input=$ADMITTED_VALUE_CHARS bound=$bound",
        )
        assertTrue(
            counted.reads <= bound,
            "decoder read ${counted.reads} chars of $ADMITTED_VALUE_CHARS input chars " +
                "(bound $bound = input × ($MAX_ENTITY_CHARS window + $COUNTED_PASSES passes))",
        )
    }

    /** Predefined and numeric references decode in one pass. */
    @Test
    fun predefinedAndNumericEntitiesDecode() {
        assertEquals("& < > \" '", unescapeEntities("&amp; &lt; &gt; &quot; &apos;"))
        assertEquals("A A € 🎧", unescapeEntities("&#65; &#x41; &#x20AC; &#X1F3A7;"))
        assertEquals("plain text", unescapeEntities("plain text"))
    }

    /** A decoded `&` is never re-scanned: `&amp;lt;` yields the literal `&lt;`, not `<`. */
    @Test
    fun decodedAmpersandIsNotRescanned() {
        assertEquals("&lt; &#65;", unescapeEntities("&amp;lt; &amp;#65;"))
    }

    /** Unterminated, malformed, unknown and non-XML references stay literal. */
    @Test
    fun invalidReferencesStayLiteral() {
        assertEquals(
            "&#xZZZZ; &#0; &#; & &x &AMP;",
            unescapeEntities("&#xZZZZ; &#0; &#; & &x &AMP;"),
        )
    }

    /**
     * Bills every character read (`get`) the decoder performs on the value, so the `;`
     * window's bound is a fact of the input rather than of a wall clock. `subSequence`
     * hands back the plain substring — the token path matches production, where the
     * decoded value is a `String`.
     */
    private class CountingChars(
        private val delegate: String,
    ) : CharSequence {
        var reads = 0L
            private set

        override val length: Int
            get() = delegate.length

        override fun get(index: Int): Char {
            reads++
            return delegate[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = delegate.subSequence(startIndex, endIndex)

        override fun toString(): String = delegate
    }

    private companion object {
        /**
         * The most `&`s one admitted value can carry: the salvage tag cap is 64 KiB and a
         * single attribute value fills nearly all of it.
         */
        const val ADMITTED_VALUE_CHARS = 64 * 1024

        /**
         * Character reads one input char can cost besides the `;` window: the
         * `'&' in value` pre-scan (≤ 1 per char), the main-loop read (1 per char) and the
         * disjoint literal-append copies (≤ 1 per char) — `MAX_ENTITY_CHARS + 3` in all.
         */
        const val COUNTED_PASSES = 3
    }
}
