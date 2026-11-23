// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A short needle window still shows its needle. The mini player's and a download row's windows
 * are 14 dp: the bezel leaves a 5 dp face, and the full-size drawing put a needle about 1 dp tall
 * on it, so no page but the radio home showed any progress.
 */
@OptIn(ExperimentalTestApi::class)
class NeedleWindowTest {
    @Test
    fun aShortWindowsNeedleCoversMostOfItsFace() =
        runDesktopComposeUiTest(width = WIDTH_DP * SCALE, height = SHORT_DP * SCALE) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(SCALE.toFloat())) {
                    NeedleWindow(0.5f, Modifier.testTag(TAG).size(WIDTH_DP.dp, SHORT_DP.dp))
                }
            }

            val pixels = onNodeWithTag(TAG).captureToImage().toPixelMap()
            val column =
                (0 until pixels.width).maxBy { x ->
                    (0 until pixels.height).count { y -> pixels[x, y].isNeedle() }
                }
            val needleRows = (0 until pixels.height).count { y -> pixels[column, y].isNeedle() }
            val faceRows = ((SHORT_DP - 2 * BEZEL_DP) * SCALE).toInt()

            assertTrue(
                needleRows * 10 >= faceRows * MIN_TENTHS,
                "the needle covers $needleRows of the face's $faceRows rows",
            )
        }

    /** The needle's red, also where its antialiased edge covers most of a pixel. */
    private fun Color.isNeedle(): Boolean = red > 0.55f && green < 0.45f && blue < 0.4f

    private companion object {
        const val TAG = "needle"
        const val SCALE = 2
        const val WIDTH_DP = 120
        const val SHORT_DP = 14
        const val BEZEL_DP = 4.5f
        const val MIN_TENTHS = 6
    }
}
