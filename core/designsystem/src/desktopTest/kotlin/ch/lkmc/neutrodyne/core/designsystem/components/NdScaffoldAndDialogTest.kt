// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The review fixes of 2026-10-06: `NdNavigationSuiteScaffold` must put the themed background and
 * content colour behind every layout type, and `NdDialog`'s text must scroll inside the dialog's
 * bounded body (long licence terms stay reachable).
 */
@OptIn(ExperimentalTestApi::class)
class NdScaffoldAndDialogTest {
    private val items =
        listOf(
            NdNavItem(label = "Feeds", icon = NdIcons.DynamicFeed, testTag = "nav_feeds"),
            NdNavItem(label = "Library", icon = NdIcons.GridView, testTag = "nav_library"),
        )

    @Test
    fun scaffoldSuppliesThemedContentColourOnEveryLayout() =
        runComposeUiTest {
            val onBackground = Color(0xFFE0E2EC)
            val seenColors = mutableListOf<Color>()
            setContent {
                MaterialTheme(colorScheme = darkColorScheme(onBackground = onBackground)) {
                    for (
                    type in
                    listOf(
                        NavigationSuiteType.NavigationBar,
                        NavigationSuiteType.WideNavigationRailCollapsed,
                    )
                    ) {
                        NdNavigationSuiteScaffold(
                            items = items,
                            selected = 0,
                            onSelect = {},
                            suiteType = type,
                        ) {
                            seenColors += LocalContentColor.current
                        }
                    }
                }
            }
            assertEquals(listOf(onBackground, onBackground), seenColors)
        }

    @Test
    fun scaffoldDrawsTheThemedBackgroundBehindContent() =
        runComposeUiTest {
            val background = Color(0xFF11333F)
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(background = background)) {
                    NdNavigationSuiteScaffold(
                        items = items,
                        selected = 0,
                        onSelect = {},
                        suiteType = NavigationSuiteType.NavigationBar,
                    ) {
                        // No content: the whole area above the bar shows the Surface's colour.
                    }
                }
            }
            onRoot().captureToImage().toPixelMap().let { pixels ->
                assertEquals(background, pixels[pixels.width / 2, 4])
            }
        }

    @Test
    fun dialogTextScrollsInsideItsBoundedBody() =
        runComposeUiTest {
            val longText = List(400) { "licence line $it" }.joinToString("\n")
            setContent {
                NdDialog(onDismissRequest = {}, title = "Licence", text = longText)
            }

            val scrollable = onAllNodes(hasScrollAction()).onFirst().fetchSemanticsNode()
            val range =
                scrollable.config.getOrElse(SemanticsProperties.VerticalScrollAxisRange) {
                    ScrollAxisRange(value = { 0f }, maxValue = { 0f })
                }
            assertTrue(
                range.maxValue() > 0f,
                "the dialog body must scroll with a positive range (max was ${range.maxValue()})",
            )
        }
}
