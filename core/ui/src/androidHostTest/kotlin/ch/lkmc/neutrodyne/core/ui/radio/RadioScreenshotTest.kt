// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class RadioScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun phone() = capture("phone")

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h568dp-mdpi")
    fun small() = capture("small")

    @Test
    @Config(qualifiers = "en-rUS-w700dp-h360dp-mdpi")
    fun landscape() = capture("landscape")

    @Test
    @Config(qualifiers = "en-rUS-w700dp-h900dp-mdpi")
    fun tablet() = capture("tablet")

    @Test fun largeText() = capture("large_text")

    @Test fun playing() = capture("playing")

    private fun capture(name: String) {
        rule.setContent {
            NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, if (name == "large_text") 1.3f else 1f),
                ) {
                    RadioScreen(radioFixture(name == "playing"), RadioActions())
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("screenshots/android/radio_$name.png")
    }
}
