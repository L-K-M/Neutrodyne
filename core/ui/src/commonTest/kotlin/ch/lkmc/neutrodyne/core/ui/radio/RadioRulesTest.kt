// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioRulesTest {
    @Test fun elapsedUsesFourTubesAcrossBoundary() {
        assertEquals("00:00", radioElapsed(-1000))
        assertEquals("99:59", radioElapsed(5_999_000))
        assertEquals("1H:40", radioElapsed(6_000_000))
        assertEquals("1H:42", radioElapsed(6_120_000))
        assertEquals("9H:59", radioElapsed(60_000_000))
    }

    @Test fun discardThresholdClampsInDp() {
        assertEquals(48f, radioDiscardThreshold(100f))
        assertEquals(70f, radioDiscardThreshold(250f))
        assertEquals(80f, radioDiscardThreshold(500f))
    }

    @Test fun removalSelectsNextThenPreviousAndLeavesOtherSelectionsAlone() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(3L, radioSelectionAfterRemoval(ids, 2, 2))
        assertEquals(2L, radioSelectionAfterRemoval(ids, 3, 3))
        assertEquals(1L, radioSelectionAfterRemoval(ids, 1, 3))
        assertNull(radioSelectionAfterRemoval(listOf(1L), 1, 1))
    }
}
