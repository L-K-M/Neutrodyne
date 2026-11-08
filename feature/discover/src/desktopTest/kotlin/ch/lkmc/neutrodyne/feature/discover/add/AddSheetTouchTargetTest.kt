// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdModalBottomSheet
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testFeedPreview
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The device ATF TouchTargetSizeCheck evaluates only clickable nodes and measures their
 * clipped accessibility bounds, never the layout's declared size (the API 37 Subscribe
 * journey flagged two 48x29 nodes at the sheet top). `touchBoundsInRoot` is the same
 * quantity — clipped and minimum-touch-target expanded — so every node ATF can flag must
 * keep a 48 dp target here on the smallest supported window. The unmerged tree is used
 * because that is the node shape the Android accessibility tree exposes.
 */
@OptIn(ExperimentalTestApi::class)
class AddSheetTouchTargetTest {
    @BeforeTest
    fun setup() {
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @Test
    fun everyA11yNodeKeepsA48DpTouchTarget() =
        runDesktopComposeUiTest(width = 320, height = 640) {
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    NdModalBottomSheet(onDismissRequest = {}) {
                        AddPodcastSheet(
                            initialInput = "https://example.com/feed",
                            state =
                                AddPodcastUiState(
                                    step = AddSheetStep.Preview(testFeedPreview(title = "M7 Subscribe Journey")),
                                ),
                            onResolve = {},
                            onAutoResolve = {},
                            onCredentials = {},
                            onCancelResolve = {},
                            onInputChange = {},
                            onCandidate = {},
                            onSubscribe = {},
                            onOpenPodcast = {},
                            onDismiss = {},
                        )
                    }
                }
            }
            val a11yTarget =
                SemanticsMatcher("clickable") { node ->
                    node.config.contains(SemanticsActions.OnClick) ||
                        node.config.contains(SemanticsActions.OnLongClick)
                }
            val nodes = onAllNodes(a11yTarget, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue(
                onAllNodes(hasContentDescription("Drag handle"), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
                "no drag handle node found - the check would be vacuous without it",
            )
            val undersized =
                nodes.filter { node ->
                    with(density) {
                        node.touchBoundsInRoot.width.toDp() < MIN_TARGET ||
                            node.touchBoundsInRoot.height.toDp() < MIN_TARGET
                    }
                }
            assertTrue(
                undersized.isEmpty(),
                "a11y targets under $MIN_TARGET: " +
                    undersized.joinToString("; ") { node ->
                        "id=${node.id} touch=${node.touchBoundsInRoot} " +
                            "text=${node.config.getOrNull(SemanticsProperties.Text)} " +
                            "cd=${node.config.getOrNull(SemanticsProperties.ContentDescription)}"
                    },
            )
        }

    private companion object {
        val MIN_TARGET = 48.dp
    }
}
