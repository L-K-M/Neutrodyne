// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test fun compactHeightFollowsTheUsableViewportNotTheRawWindow() {
        // An 844 dp window with 72 dp of system bars has 772 dp of content: the compact home.
        val tall = radioLayout(390.dp, 844.dp, 1f)
        assertFalse(tall.compact)
        assertFalse(tall.short)
        assertEquals(RadioRows.STANDARD, tall.rows)
        val withBars = radioLayout(390.dp, 772.dp, 1f)
        assertTrue(withBars.compact)
        assertFalse(withBars.short)
        assertEquals(RadioRows.COMPACT, withBars.rows)
    }

    @Test fun layoutMatrixCoversWidthAndUsableHeight() {
        val phone = radioLayout(320.dp, 568.dp, 1f)
        assertTrue(phone.narrow)
        assertFalse(phone.wide)
        assertTrue(phone.compact)
        assertTrue(phone.short)

        val landscape = radioLayout(700.dp, 360.dp, 1f)
        assertFalse(landscape.narrow)
        assertTrue(landscape.wide)
        assertTrue(landscape.compact)
        assertTrue(landscape.short)
        assertEquals(RadioRows.COMPACT, landscape.rows)

        val tablet = radioLayout(700.dp, 900.dp, 1f)
        assertTrue(tablet.wide)
        assertFalse(tablet.compact)
        assertFalse(tablet.short)
        assertEquals(RadioRows.WIDE, tablet.rows)

        // The thresholds are half-open: at the boundary the roomier mode wins.
        assertFalse(radioLayout(370.dp, 900.dp, 1f).narrow)
        assertTrue(radioLayout(600.dp, 900.dp, 1f).wide)
        assertFalse(radioLayout(700.dp, 800.dp, 1f).compact)
        assertFalse(radioLayout(700.dp, 660.dp, 1f).short)
    }

    @Test fun cabinetMinimumGrowsWithTextAndFollowsTheMode() {
        assertEquals(500.dp, radioLayout(390.dp, 844.dp, 1f).minimumHeight)
        assertEquals(440.dp, radioLayout(390.dp, 772.dp, 1f).minimumHeight)
        assertEquals(280.dp, radioLayout(700.dp, 900.dp, 1f).minimumHeight)
        assertEquals(660.dp, radioLayout(390.dp, 772.dp, 1.5f).minimumHeight)
        assertEquals(420.dp, radioLayout(700.dp, 900.dp, 1.5f).minimumHeight)

        // Both compact and wide hold: rows follow compact, minimumHeight follows wide.
        assertEquals(RadioRows.COMPACT, radioLayout(700.dp, 700.dp, 1f).rows)
        assertEquals(280.dp, radioLayout(700.dp, 700.dp, 1f).minimumHeight)
    }

    @Test fun removalSelectsNextThenPreviousAndLeavesOtherSelectionsAlone() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(3L, radioSelectionAfterRemoval(ids, 2, 2))
        assertEquals(2L, radioSelectionAfterRemoval(ids, 3, 3))
        assertEquals(1L, radioSelectionAfterRemoval(ids, 1, 3))
        assertNull(radioSelectionAfterRemoval(listOf(1L), 1, 1))
    }
}
