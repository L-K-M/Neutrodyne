// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
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

/** Stable en-US fixture labels; Next remains enabled. */
private val TRANSPORT_LABELS =
    listOf("Back 10 seconds", "Previous episode", "Play", "Next episode", "Forward 30 seconds")
private val TRANSPORT_TEXT_LABELS = TRANSPORT_LABELS - "Play"

/** At 320dp, transport targets must stay large enough and disjoint. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h640dp-mdpi")
class PlayerCompactTransportTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun fakeImages() {
        installFakeImageLoader()
    }

    @Test fun fullPlayerTransportKeepsDisjoint48DpTouchTargets() {
        rule.setContent {
            NeutrodyneTheme(
                AppearancePrefs(dynamicColor = false),
                SystemUiState.DEFAULT.copy(reducedMotion = true),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlayerVisualContent(PlayerVisual.FULL)
                }
            }
        }
        rule.waitForIdle()

        val root = rule.onRoot().fetchSemanticsNode()
        val density = root.layoutInfo.density
        assertEquals(COMPACT_WINDOW_WIDTH.value, root.boundsInRoot.width / density.density)
        val minimum = with(density) { MINIMUM_TOUCH_TARGET.toPx() }
        val targets =
            TRANSPORT_LABELS.associateWith { label ->
                val node = rule.onNodeWithContentDescription(label).fetchSemanticsNode()
                checkNotNull(node.clickableOrDescendant()) { "$label has no clickable node" }
            }

        for ((label, node) in targets) {
            val bounds = node.touchBoundsInRoot
            assertTrue(
                bounds.width >= minimum && bounds.height >= minimum,
                "$label touch bounds $bounds under $MINIMUM_TOUCH_TARGET",
            )
        }
        val touchBounds = targets.map { it.key to it.value.touchBoundsInRoot }
        for ((index, first) in touchBounds.withIndex()) {
            for ((other, bounds) in touchBounds.drop(index + 1)) {
                assertTrue(
                    !first.second.overlaps(bounds),
                    "${first.first} ${first.second} overlaps $other $bounds",
                )
            }
        }

        for (label in TRANSPORT_TEXT_LABELS) {
            val layouts = mutableListOf<TextLayoutResult>()
            val action = targets.getValue(label).config.getOrNull(SemanticsActions.GetTextLayoutResult)
            assertTrue(action?.action?.invoke(layouts) == true, "$label has no text layout")
            val layout = layouts.single()
            assertEquals(1, layout.lineCount, "$label wrapped")
            // A wide paragraph can report overflow even when the short symbol fits.
            assertTrue(
                layout.getLineLeft(0) >= 0f && layout.getLineRight(0) <= layout.size.width &&
                    layout.getLineTop(0) >= 0f && layout.getLineBottom(0) <= layout.size.height,
                "$label line bounds exceed ${layout.size}",
            )
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
