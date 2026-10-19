// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.player

import ch.lkmc.neutrodyne.core.model.HingeOrientation
import ch.lkmc.neutrodyne.core.model.HingeState
import ch.lkmc.neutrodyne.core.model.WindowHinge
import ch.lkmc.neutrodyne.core.model.WindowPosture
import ch.lkmc.neutrodyne.core.model.WindowRectDp
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdaptivePlayerGeometryTest {
    @Test fun compactAndMediumLandscapeKeepTwoOperableColumns() {
        for ((width, height) in listOf(640f to 320f, 840f to 480f, 1199f to 599f)) {
            val g = adaptivePlayerGeometry(width, height, 0f, 0f, WindowPosture())
            assertEquals(PlayerLayoutKind.LANDSCAPE, g.kind)
            assertEquals(g.art.right, g.controls.left)
            assertTrue(g.controls.right - g.controls.left >= 384f)
            assertEquals(height, g.controls.bottom)
            assertInBounds(g, width, height)
        }
    }

    @Test fun portraitPanelLeavesMoreThanHalfItsHeightForScrollableControls() {
        for (width in listOf(360f, 412f)) {
            val g = adaptivePlayerGeometry(width, 800f, 96f, 24f, WindowPosture())
            assertEquals(PlayerLayoutKind.PORTRAIT, g.kind)
            assertTrue(g.controls.bottom - g.controls.top > 400f)
            assertInBounds(g, width, 800f)
        }
    }

    @Test fun horizontalHalfOpenedConvertsWindowDpIntoInsetPlayerExactlyOnce() {
        val hinge =
            WindowHinge(
                WindowRectDp(0f, 380f, 1000f, 404f),
                HingeOrientation.HORIZONTAL,
                HingeState.HALF_OPENED,
                true,
            )
        val g = adaptivePlayerGeometry(412f, 720f, 588f, 48f, WindowPosture(persistentListOf(hinge)))
        assertEquals(PlayerLayoutKind.TABLETOP, g.kind)
        assertEquals(332f, g.art.bottom)
        assertEquals(356f, g.controls.top)
        assertEquals(720f, g.controls.bottom)
        assertInBounds(g, 412f, 720f)
    }

    @Test fun bookFlatOutsideAndPartialHingesNeverCreateTabletop() {
        val base =
            WindowHinge(
                WindowRectDp(0f, 300f, 800f, 324f),
                HingeOrientation.HORIZONTAL,
                HingeState.HALF_OPENED,
                true,
            )
        val excluded =
            listOf(
                base.copy(orientation = HingeOrientation.VERTICAL),
                base.copy(state = HingeState.FLAT),
                base.copy(bounds = WindowRectDp(0f, 900f, 800f, 924f)),
                base.copy(bounds = WindowRectDp(0f, 300f, 200f, 324f)),
            )
        excluded.forEach { hinge ->
            assertEquals(
                PlayerLayoutKind.LANDSCAPE,
                adaptivePlayerGeometry(800f, 600f, 0f, 0f, WindowPosture(persistentListOf(hinge))).kind,
            )
        }
    }

    @Test fun zeroSpaceIsBoundedAndNonFiniteInputsAreRejected() {
        assertInBounds(adaptivePlayerGeometry(0f, 0f, 0f, 0f, WindowPosture()), 0f, 0f)
        assertFailsWith<IllegalArgumentException> { adaptivePlayerGeometry(Float.NaN, 400f, 0f, 0f, WindowPosture()) }
        assertFailsWith<IllegalArgumentException> { adaptivePlayerGeometry(400f, -1f, 0f, 0f, WindowPosture()) }
    }

    private fun assertInBounds(
        g: AdaptivePlayerGeometry,
        width: Float,
        height: Float,
    ) {
        for (r in listOf(g.art, g.controls)) {
            assertTrue(r.left >= 0f && r.top >= 0f && r.right <= width && r.bottom <= height)
            assertTrue(r.left <= r.right && r.top <= r.bottom)
        }
    }
}
