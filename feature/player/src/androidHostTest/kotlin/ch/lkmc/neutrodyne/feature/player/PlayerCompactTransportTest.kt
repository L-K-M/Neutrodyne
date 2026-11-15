// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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

private val COMPACT_WINDOW_WIDTH = 320.dp
private val MINIMUM_TOUCH_TARGET = 48.dp

/** Stable en-US fixture labels. */
private val TRANSPORT_LABELS =
    listOf("Back 30 seconds", "Play", "Forward 30 seconds")

/** At 320dp, transport targets must stay large enough and disjoint. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h640dp-mdpi")
class PlayerCompactTransportTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun fakeImages() {
        installFakeImageLoader()
    }

    @Test fun miniPlayerTransportKeepsDisjoint48DpTouchTargets() {
        rule.setContent {
            NeutrodyneTheme(
                AppearancePrefs(dynamicColor = false),
                SystemUiState.DEFAULT.copy(reducedMotion = true),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlayerVisualContent(PlayerVisual.MINI_PAUSED)
                }
            }
        }
        rule.waitForIdle()
        val root = rule.onRoot().fetchSemanticsNode()
        val density = root.layoutInfo.density
        assertEquals(COMPACT_WINDOW_WIDTH.value, root.boundsInRoot.width / density.density)
        val minimum = with(density) { MINIMUM_TOUCH_TARGET.toPx() }
        val targets =
            TRANSPORT_LABELS.map { label ->
                val node = rule.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
                checkNotNull(node.clickableOrDescendant() ?: node.parent?.clickableOrAncestor()) {
                    "$label has no clickable node"
                }.touchBoundsInRoot
            }
        targets.forEach { bounds ->
            assertTrue(
                bounds.width >= minimum && bounds.height >= minimum,
                "Touch bounds $bounds under $MINIMUM_TOUCH_TARGET",
            )
        }
        targets.forEachIndexed { index, first ->
            targets.drop(index + 1).forEach { other -> assertTrue(!first.overlaps(other), "$first overlaps $other") }
        }
    }
}

/** The node carrying the click action is what accessibility reports and expands for touch. */
private fun SemanticsNode.clickableOrDescendant(): SemanticsNode? =
    if (config.contains(SemanticsActions.OnClick)) {
        this
    } else {
        children.firstNotNullOfOrNull { it.clickableOrDescendant() }
    }

private fun SemanticsNode.clickableOrAncestor(): SemanticsNode? =
    if (config.contains(SemanticsActions.OnClick)) this else parent?.clickableOrAncestor()
