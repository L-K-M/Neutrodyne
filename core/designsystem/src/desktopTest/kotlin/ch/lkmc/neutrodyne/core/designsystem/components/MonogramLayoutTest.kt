// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.art.MonogramMode
import ch.lkmc.neutrodyne.core.model.art.MonogramSpec
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MonogramLayoutTest {
    @Test fun mediumTileAtDoubleFontSeparatesInitialsAndTitle() =
        runDesktopComposeUiTest(width = 100, height = 100) {
            val spec = MonogramSpec("H", 200.0)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                        Image(rememberMonogramPainter(spec, "MMMMMMMMMMMM MMMMMMMMMMMM"), null, Modifier.size(100.dp))
                    }
                }
            }
            val pixels = onRoot().captureToImage().toPixelMap()
            val foreground = Color(spec.colors(MonogramMode.LIGHT).second)
            val inkRows =
                (0 until pixels.height).filter { y ->
                    (0 until pixels.width).any { x ->
                        val color = pixels[x, y]
                        kotlin.math.abs(color.red - foreground.red) +
                            kotlin.math.abs(color.green - foreground.green) +
                            kotlin.math.abs(color.blue - foreground.blue) < 0.15f
                    }
                }
            assertTrue(inkRows.isNotEmpty())
            // With overlap, initials merge into the first title line, leaving only the
            // ordinary inter-line gap. Separate blocks retain a clear band between them.
            val largestGap = inkRows.zipWithNext { a, b -> b - a - 1 }.maxOrNull() ?: 0
            assertTrue(largestGap >= 16, "Initials and title overlap: largest clear band is $largestGap px")
        }
}
