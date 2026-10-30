// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-rXB-w360dp-h640dp-mdpi")
class PlayerLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun fakeImages() {
        installFakeImageLoader()
    }

    @Test fun largeFontHeaderKeepsItsWholeContextLine() {
        rule.setContent {
            NeutrodyneTheme(
                AppearancePrefs(dynamicColor = false),
                SystemUiState.DEFAULT.copy(dark = true, reducedMotion = true),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlayerVisualContent(PlayerVisual.FULL_STRESS)
                }
            }
        }
        val context = rule.onNodeWithText("a long group name that wraps", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        context.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(PLAYER_STRESS_FONT_SCALE, layout.layoutInput.density.fontScale)
        assertEquals(1, layout.lineCount)

        val lineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
        val bounds = context.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height >= lineHeight, "Android header clipped its context: ${bounds.height} < $lineHeight")
        val artworkTop =
            rule
                .onNodeWithTag("playerArtwork")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue(bounds.top + lineHeight <= artworkTop, "Artwork covered the context line")
    }
}
