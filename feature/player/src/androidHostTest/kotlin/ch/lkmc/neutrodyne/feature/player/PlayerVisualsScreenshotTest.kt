// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Robolectric goldens; stress cases run under the ar-XB pseudolocale like the queue set. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class PlayerVisualsScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun fakeImages() {
        installFakeImageLoader()
    }

    @Test fun miniPaused() = capture(PlayerVisual.MINI_PAUSED)

    @Test fun miniBufferingDark() = capture(PlayerVisual.MINI_BUFFERING_DARK)

    @Test fun full() = capture(PlayerVisual.FULL)

    @Test fun fullDark() = capture(PlayerVisual.FULL_DARK)

    @Test fun upNextPanel() = capture(PlayerVisual.UP_NEXT_PANEL)

    @Test fun notesPanel() = capture(PlayerVisual.NOTES_PANEL)

    @Test
    @Config(qualifiers = "ar-rXB-w360dp-h640dp-mdpi")
    fun fullStress() = capture(PlayerVisual.FULL_STRESS)

    @Test
    @Config(qualifiers = "en-rUS-w900dp-h640dp-mdpi")
    fun miniInset() = capture(PlayerVisual.MINI_INSET)

    @Test fun wideArt() = capture(PlayerVisual.WIDE_ART)

    @Test fun speed() = capture(PlayerVisual.SPEED)

    @Test fun speedDark() = capture(PlayerVisual.SPEED_DARK)

    @Test
    @Config(qualifiers = "ar-rXB-w360dp-h640dp-mdpi")
    fun speedStress() = capture(PlayerVisual.SPEED_STRESS)

    private fun capture(visual: PlayerVisual) {
        rule.setContent {
            NeutrodyneTheme(
                AppearancePrefs(dynamicColor = false),
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
            rule.mainClock.autoAdvance = false
            rule.mainClock.advanceTimeByFrame()
        }
        rule.waitForIdle()
        when (visual.state) {
            PlayerVisualState.PANEL_UP_NEXT -> {
                rule.onNodeWithContentDescription(PLAYER_EXPAND_PANEL_LABEL).performClick()
            }

            PlayerVisualState.PANEL_NOTES -> {
                rule.onNodeWithText(PLAYER_NOTES_TAB_LABEL).performClick()
            }

            else -> {}
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("screenshots/android/player_${visual.name.lowercase()}.png")
    }
}
