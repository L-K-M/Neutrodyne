// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ArtworkKeysTest {
    /** A query with no `/` before it is not part of the host: its bytes stay verbatim (KDoc). */
    @Test
    fun queryOnlyTailKeepsItsCase() {
        assertEquals(
            "https://cdn.example.com?id=AbC",
            ArtworkKeys.normalize("https://cdn.example.com?id=AbC"),
        )
    }

    @Test
    fun queryCaseVariantsStayDistinct() {
        assertNotEquals(
            ArtworkKeys.normalize("https://cdn.example.com?A=1"),
            ArtworkKeys.normalize("https://cdn.example.com?a=1"),
        )
    }

    @Test
    fun defaultPortStillDropsBeforeAQuery() {
        assertEquals("http://cdn.example.com?x=1", ArtworkKeys.normalize("http://cdn.example.com:80?x=1"))
    }

    @Test
    fun fragmentDropsWhileQuerySurvives() {
        assertEquals(
            "https://cdn.example.com/p?q=1",
            ArtworkKeys.normalize("https://cdn.example.com/p?q=1#frag"),
        )
    }
}
