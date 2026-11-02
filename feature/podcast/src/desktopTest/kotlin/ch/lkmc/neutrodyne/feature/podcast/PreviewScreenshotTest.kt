// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalTestApi::class)
class PreviewScreenshotTest {
    @Test fun loading() = capture(PreviewShot.LOADING)

    @Test fun error() = capture(PreviewShot.ERROR)

    @Test fun content() = capture(PreviewShot.CONTENT)

    private fun capture(shot: PreviewShot) =
        runDesktopComposeUiTest(width = 360, height = 640) {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            installFakeImageLoader()
            try {
                mainClock.autoAdvance = false
                setContent {
                    NeutrodyneTheme(
                        AppearancePrefs(dynamicColor = false),
                        SystemUiState(dark = false, contrast = 0.0, reducedMotion = true),
                    ) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            PreviewScreenshotFixture(shot)
                        }
                    }
                }
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                onRoot().captureRoboImage("screenshots/desktop/preview_${shot.name.lowercase(Locale.ROOT)}.png")
            } finally {
                Locale.setDefault(previous)
            }
        }
}
