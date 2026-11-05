// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w1400dp-h900dp-mdpi")
class M10PlayerScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun referenceArtAcrossEveryAdaptivePresentation() {
        installFakeImageLoader(
            ReferenceArt.entries.associate {
                referenceArt(it) to
                    referenceImage(it)
            },
        )
        val cases = m10PlayerCases(System.getProperty("neutrodyne.screenshotTier", "pr") == "full")
        var visual by mutableStateOf(cases.first())
        rule.setContent {
            Box(Modifier.requiredSize(visual.viewport.width.dp, visual.viewport.height.dp)) { M10PlayerContent(visual) }
        }
        for (case in cases) {
            rule.runOnIdle { visual = case }
            rule.waitForIdle()
            var actual = 0
            try {
                rule.waitUntil(timeoutMillis = 5_000) {
                    val pixels = rule.onNodeWithTag(case.sheetTag).captureToImage().toPixelMap()
                    actual =
                        pixels[
                            if (case.viewport == PlayerViewport.MINI) pixels.width / 2 else pixels.width - 4,
                            if (case.viewport == PlayerViewport.MINI) 8 else 32,
                        ].toArgb()
                    actual == case.expectedSheetArgb()
                }
            } catch (error: Throwable) {
                val expected = case.expectedSheetArgb().toUInt().toString(16)
                val observed = actual.toUInt().toString(16)
                throw AssertionError("${case.name}: expected $expected, actual $observed", error)
            }
            rule.onRoot().captureRoboImage("screenshots/android/m10-player-${case.name}.png")
        }
    }
}
