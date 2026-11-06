// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBarAppearance
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ArtworkToolbarContrastTest {
    @Test fun titlesAndIconsStayReadableOverEveryBackdropAtLargeText() {
        for (background in listOf(Color.White, Color.Black, Color.Red, Color.Blue)) {
            for (font in listOf(1f, 2f)) {
                runDesktopComposeUiTest(width = 360, height = 160) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, font)) {
                            NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState(false, 0.0, true)) {
                                Box(Modifier.fillMaxSize().background(background)) {
                                    NdTopAppBar(
                                        title = "MMMMMM",
                                        navigation = { NdTooltipIconButton({}, NdIcons.ArrowBack, "Back") },
                                        actions = { NdIconButton({}, NdIcons.MoreVert, "More") },
                                        appearance = NdTopAppBarAppearance.ARTWORK_OVERLAY,
                                    )
                                }
                            }
                        }
                    }
                    for (node in listOf(
                        onNodeWithText("MMMMMM"),
                        onNodeWithContentDescription("Back"),
                        onNodeWithContentDescription("More"),
                    )) {
                        val pixels = node.captureToImage().toPixelMap()
                        val base = pixels[0, 0].luminance()
                        var foreground = base
                        for (y in 0 until pixels.height) {
                            for (x in 0 until pixels.width) foreground = max(foreground, pixels[x, y].luminance())
                        }
                        val contrast = (foreground + .05f) / (base + .05f)
                        assertTrue(contrast >= 4.5f, "$background at font $font: foreground contrast $contrast")
                    }
                }
            }
        }
    }
}
