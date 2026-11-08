// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.testing.screenshots.ScreenSnapshot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M10ScreenScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp-mdpi")
    fun compact() = capture("compact")

    @Test
    @Config(qualifiers = "en-rUS-w700dp-h800dp-mdpi")
    fun medium() = capture("medium")

    @Test
    @Config(qualifiers = "en-rUS-w900dp-h800dp-mdpi")
    fun expanded() = capture("expanded")

    @Test
    @Config(qualifiers = "en-rUS-w1400dp-h900dp-mdpi")
    fun large() = capture("large")

    @Test
    @Config(qualifiers = "en-rUS-w800dp-h700dp-mdpi")
    fun tabletop() = capture("tabletop")

    private fun capture(viewport: String) {
        installFakeImageLoader()
        var dark by mutableStateOf(false)
        var variant by mutableIntStateOf(0)
        rule.setContent { ScreenSnapshot(dark, PlatformKind.ANDROID) { key(variant) { M10ScreenContent(variant) } } }
        for (night in listOf(false, true)) {
            val theme = if (night) "dark" else "light"
            for (subject in 0 until 2) {
                rule.runOnIdle {
                    dark = night
                    variant = subject
                }
                rule.waitForIdle()
                rule.onRoot().captureRoboImage("screenshots/android/m10-screen-$viewport-$subject-$theme.png")
            }
        }
    }
}
