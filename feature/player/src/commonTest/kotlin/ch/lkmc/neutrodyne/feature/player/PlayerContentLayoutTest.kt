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
import kotlin.test.assertNull

class PlayerContentLayoutTest {
    @Test fun portraitAndLandscapeFollowAvailableHeight() {
        assertEquals(PlayerContentLayout.PORTRAIT, playerContentLayout(360f, 800f, WindowPosture()))
        assertEquals(PlayerContentLayout.LANDSCAPE, playerContentLayout(800f, 360f, WindowPosture()))
        assertEquals(PlayerContentLayout.LANDSCAPE, playerContentLayout(700f, 600f, WindowPosture()))
    }

    @Test fun onlyAHalfOpenedHorizontalHingeSplitsThePlayer() {
        val bounds = WindowRectDp(0f, 340f, 800f, 360f)
        val hinge = WindowHinge(bounds, HingeOrientation.HORIZONTAL, HingeState.HALF_OPENED, true)
        val tabletop = WindowPosture(persistentListOf(hinge))
        assertEquals(bounds, tabletopHinge(tabletop))
        assertEquals(PlayerContentLayout.TABLETOP, playerContentLayout(800f, 700f, tabletop))
        assertNull(tabletopHinge(WindowPosture(persistentListOf(hinge.copy(state = HingeState.FLAT)))))
        assertNull(tabletopHinge(WindowPosture(persistentListOf(hinge.copy(orientation = HingeOrientation.VERTICAL)))))
    }
}
