// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.ui.geometry.Rect
import ch.lkmc.neutrodyne.core.model.HingeOrientation
import ch.lkmc.neutrodyne.core.model.HingeState
import ch.lkmc.neutrodyne.core.model.WindowRectDp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class WindowPostureAdapterTest {
    @Test fun androidWindowPixelsBecomeWindowDpAtFractionalAndIntegerDensities() {
        for (density in listOf(1f, 1.5f, 2f, 3.5f)) {
            val input =
                Posture(
                    true,
                    listOf(
                        HingeInfo(Rect(0f, 400f * density, 1000f * density, 424f * density), false, false, true, true),
                    ),
                )
            val actual = windowPostureDp(input, density).hinges.single()
            assertEquals(WindowRectDp(0f, 400f, 1000f, 424f), actual.bounds)
            assertEquals(HingeState.HALF_OPENED, actual.state)
            assertEquals(HingeOrientation.HORIZONTAL, actual.orientation)
            assertEquals(true, actual.isSeparating)
        }
    }

    @Test fun flatBookAndNonSeparatingDataAreRetainedWithoutInventingTabletop() {
        val input = Posture(false, listOf(HingeInfo(Rect(990f, 0f, 1010f, 1600f), true, true, false, false)))
        val actual = windowPostureDp(input, 2f).hinges.single()
        assertEquals(WindowRectDp(495f, 0f, 505f, 800f), actual.bounds)
        assertEquals(HingeOrientation.VERTICAL, actual.orientation)
        assertEquals(HingeState.FLAT, actual.state)
        assertEquals(false, actual.isSeparating)
    }

    @Test fun emptyPostureAndInvalidDensity() {
        assertEquals(emptyList(), windowPostureDp(Posture(), 2f).hinges)
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { windowPostureDp(Posture(), density) }
        }
    }
}
