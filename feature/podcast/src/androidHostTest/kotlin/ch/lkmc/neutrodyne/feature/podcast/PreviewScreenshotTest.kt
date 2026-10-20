// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.podcast

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class PreviewScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun loading() = capture(PreviewShot.LOADING)

    @Test fun error() = capture(PreviewShot.ERROR)

    @Test fun content() = capture(PreviewShot.CONTENT)

    private fun capture(shot: PreviewShot) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
        try {
            rule.mainClock.autoAdvance = false
            rule.setContent {
                NeutrodyneTheme(
                    AppearancePrefs(dynamicColor = false),
                    SystemUiState(dark = false, contrast = 0.0, reducedMotion = true),
                ) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        PreviewScreenshotFixture(shot)
                    }
                }
            }
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            rule.onRoot().captureRoboImage("screenshots/android/preview_${shot.name.lowercase(Locale.ROOT)}.png")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
