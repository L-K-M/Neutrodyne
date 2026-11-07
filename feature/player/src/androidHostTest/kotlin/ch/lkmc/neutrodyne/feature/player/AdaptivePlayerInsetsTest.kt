// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.WindowPosture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w900dp-h420dp-mdpi")
class AdaptivePlayerInsetsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun controlsStayClearOfStatusBarsCutoutsAndNavigation() {
        rule.setContent {
            NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                AdaptivePlayerBody(
                    layout = PlayerContentLayout.LANDSCAPE,
                    posture = WindowPosture(),
                    header = { Text("Header", Modifier.fillMaxWidth().testTag("adaptive_header")) },
                    artwork = { Box(it) },
                    controls = { Box(Modifier.fillMaxWidth().height(600.dp)) },
                    tabs = { Text("Tabs", Modifier.height(40.dp).testTag("adaptive_tabs")) },
                    modifier = Modifier.fillMaxSize(),
                    safeInsets = WindowInsets(left = 16, top = 32, right = 18, bottom = 24),
                )
            }
        }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val header = rule.onNodeWithTag("adaptive_header").fetchSemanticsNode().boundsInRoot
        assertTrue(header.top >= 32f, "The header must clear the status bar")
        assertTrue(header.right <= root.right - 18f, "Controls must clear a side cutout")
        rule.onNodeWithTag("adaptive_tabs").performScrollTo()
        val tabs = rule.onNodeWithTag("adaptive_tabs").fetchSemanticsNode().boundsInRoot
        assertTrue(tabs.bottom <= root.bottom - 24f, "Tabs must clear navigation after scrolling")
    }
}
