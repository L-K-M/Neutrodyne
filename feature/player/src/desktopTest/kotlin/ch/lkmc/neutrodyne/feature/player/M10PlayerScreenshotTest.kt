// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import io.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class M10PlayerScreenshotTest {
    @Test fun referenceArtAcrossEveryAdaptivePresentation() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            installFakeImageLoader(
                ReferenceArt.entries.associate { referenceArt(it) to referenceImage(it) },
            )
            for (visual in m10PlayerCases(full = false)) {
                runDesktopComposeUiTest(width = visual.viewport.width, height = visual.viewport.height) {
                    setContent { M10PlayerContent(visual) }
                    waitForIdle()
                    waitUntil(timeoutMillis = 5_000) {
                        val pixels = onNodeWithTag(visual.sheetTag).captureToImage().toPixelMap()
                        pixels[
                            if (visual.viewport == PlayerViewport.MINI) pixels.width / 2 else pixels.width - 4,
                            if (visual.viewport == PlayerViewport.MINI) 8 else 32,
                        ].toArgb() ==
                            visual.expectedSheetArgb()
                    }
                    onRoot().captureRoboImage("screenshots/desktop/m10-player-${visual.name}.png")
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
