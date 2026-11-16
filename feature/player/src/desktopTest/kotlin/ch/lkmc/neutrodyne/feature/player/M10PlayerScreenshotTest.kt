// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

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
                        val pixels = onNodeWithTag(COVER_TAG).captureToImage().toPixelMap()
                        coverMatches(pixels[pixels.width / 2, pixels.height / 2], visual.expectedCoverCentre())
                    }
                    onRoot().captureRoboImage("screenshots/desktop/m10-player-${visual.name}.png")
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
