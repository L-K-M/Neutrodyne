// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import io.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class RadioScreenshotTest {
    @Test fun presets() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            for ((name, dimensions) in mapOf(
                "phone" to (390 to 844),
                "small" to (320 to 568),
                "landscape" to (700 to 360),
                "tablet" to (700 to 900),
                "large_text" to (390 to 844),
                "playing" to (390 to 844),
            )) {
                runDesktopComposeUiTest(width = dimensions.first, height = dimensions.second) {
                    setContent {
                        NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                            CompositionLocalProvider(
                                LocalDensity provides Density(1f, if (name == "large_text") 1.3f else 1f),
                            ) {
                                RadioScreen(radioFixture(name == "playing"), RadioActions())
                            }
                        }
                    }
                    waitForIdle()
                    onRoot().captureRoboImage("screenshots/desktop/radio_$name.png")
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
