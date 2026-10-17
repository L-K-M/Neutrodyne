// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.opml

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `category` decoding contract (05 walk rules): literal commas split, the last non-empty `/`
 * segment survives, and exactly one percent-decoding pass touches `%25`, `%2C`, `%2F`.
 */
class OpmlCategoriesTest {
    @Test
    fun nullAndBlankDecodeToEmpty() {
        assertEquals(emptyList(), OpmlCategories.decode(null))
        assertEquals(emptyList(), OpmlCategories.decode(""))
        assertEquals(emptyList(), OpmlCategories.decode("   "))
        assertEquals(emptyList(), OpmlCategories.decode(",,,"))
    }

    @Test
    fun plainTokensSplitOnCommasAndTrim() {
        assertEquals(listOf("tech", "news"), OpmlCategories.decode("tech,news"))
        assertEquals(listOf("a", "b"), OpmlCategories.decode("  a ,  b  "))
    }

    @Test
    fun lastNonEmptyPathSegmentSurvives() {
        assertEquals(listOf("Berkman"), OpmlCategories.decode("/Harvard/Berkman"))
        assertEquals(listOf("c"), OpmlCategories.decode("a/b/c"))
        assertEquals(listOf("a"), OpmlCategories.decode("a//"))
        assertEquals(emptyList(), OpmlCategories.decode("///"))
    }

    @Test
    fun documentedTriplesDecodeExactlyOnce() {
        assertEquals(listOf("a,b"), OpmlCategories.decode("a%2Cb"))
        assertEquals(listOf("a%2Cb"), OpmlCategories.decode("a%252Cb"))
        assertEquals(listOf("100%"), OpmlCategories.decode("100%25"))
        assertEquals(listOf("x/Y"), OpmlCategories.decode("x%2fY"))
        assertEquals(listOf("x%2eY"), OpmlCategories.decode("x%252eY"))
    }

    /** A decoded comma or slash is output text, not a structural character. */
    @Test
    fun decodedSeparatorsNeverResplit() {
        assertEquals(listOf("Harvard/Berkman"), OpmlCategories.decode("Harvard%2FBerkman"))
        assertEquals(listOf("x"), OpmlCategories.decode("/Harvard%2FBerkman/x"))
        assertEquals(listOf("Tech,News"), OpmlCategories.decode("/Tech%2CNews"))
    }

    @Test
    fun malformedAndUndocumentedEscapesStayLiteral() {
        assertEquals(listOf("a%zz"), OpmlCategories.decode("a%zz"))
        assertEquals(listOf("a%"), OpmlCategories.decode("a%"))
        assertEquals(listOf("a%2"), OpmlCategories.decode("a%2"))
        assertEquals(listOf("a%20b"), OpmlCategories.decode("a%20b"))
        assertEquals(listOf("a+b"), OpmlCategories.decode("a+b"))
    }
}
