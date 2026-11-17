// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import io.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Desktop goldens; English locale so the mini player's semantics labels resolve. */
@OptIn(ExperimentalTestApi::class)
class PlayerVisualsScreenshotTest {
    private var previousLocale = Locale.ENGLISH

    @BeforeTest fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun playerStates() {
        installFakeImageLoader()
        for (visual in PlayerVisual.entries) {
            runDesktopComposeUiTest(width = PLAYER_DESKTOP_WIDTH_DP, height = PLAYER_CAPTURE_HEIGHT_DP) {
                setContent {
                    NeutrodyneTheme(
                        SystemUiState(
                            dark = visual.appearance != PlayerAppearance.LIGHT,
                            contrast = 0.0,
                            reducedMotion = true,
                        ),
                    ) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            PlayerVisualContent(visual)
                        }
                    }
                }
                // Initialize composition before freezing the indeterminate buffering ring.
                if (visual.state == PlayerVisualState.MINI_BUFFERING) {
                    mainClock.autoAdvance = false
                    mainClock.advanceTimeByFrame()
                }
                waitForIdle()
                onRoot().captureRoboImage("screenshots/desktop/player_${visual.name.lowercase()}.png")
            }
        }
    }
}
