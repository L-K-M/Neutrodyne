// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.zIndex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each pane of a row draws the whole row's deck and must show only its own slice (PO-55).
 * Material's panes do not clip while they animate their bounds, so the page clips itself.
 */
@OptIn(ExperimentalTestApi::class)
class RadioCabinetPageTest {
    @Test
    fun aRowPaneDrawsOnlyWithinItsBounds() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            setContent {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        RadioCabinetPage(CabinetDeck.SHOWN, span = CabinetSpan.LEADING) {}
                    }
                    // Drawn first, so whatever the leading pane draws past its edge lands on it.
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .zIndex(-1f)
                            .background(Marker),
                    )
                }
            }

            val pixels = onRoot().captureToImage().toPixelMap()
            // The row's deck spans the window, and the cabinet's shadow falls past its walls.
            val overdrawn =
                (pixels.width / 2 + 1 until pixels.width).sumOf { x ->
                    (0 until pixels.height).count { y -> pixels[x, y] != Marker }
                }
            assertEquals(0, overdrawn, "pixels the leading pane drew past its edge")
        }

    private companion object {
        val Marker = Color(0xFFFF00FF)
    }
}
