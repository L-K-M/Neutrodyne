// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unoccluded targets retain their minimum size, role and selected state. */
@OptIn(ExperimentalTestApi::class)
class NdNavigationSuiteScaffoldTouchTargetTest {
    @Test
    fun shortBarItemsKeepA48DpTouchTargetSelectedOrNot() =
        runDesktopComposeUiTest(width = 400, height = 800) {
            val items =
                listOf(
                    NdNavItem("Feeds", NdIcons.DynamicFeed, testTag = "nav_feeds"),
                    NdNavItem("Library", NdIcons.GridView, testTag = "nav_library"),
                    NdNavItem("Up next", NdIcons.QueueMusic, testTag = "nav_up_next"),
                    NdNavItem("Downloads", NdIcons.Download, testTag = "nav_downloads"),
                    NdNavItem("Discover", NdIcons.Explore, testTag = "nav_discover"),
                )
            val types =
                listOf(
                    NavigationSuiteType.ShortNavigationBarCompact,
                    NavigationSuiteType.ShortNavigationBarMedium,
                    NavigationSuiteType.NavigationBar,
                )
            var suiteType by mutableStateOf(types.first())
            var selected by mutableIntStateOf(2)
            var lastSelected = -1
            setContent {
                MaterialTheme {
                    NdNavigationSuiteScaffold(
                        items = items,
                        selected = selected,
                        onSelect = { lastSelected = it },
                        suiteType = suiteType,
                    ) {}
                }
            }
            val density = onRoot().fetchSemanticsNode().layoutInfo.density
            val minimum = with(density) { MINIMUM_TOUCH_TARGET.roundToPx() }

            for (type in types) {
                for (selection in listOf(0, 2)) {
                    runOnIdle {
                        suiteType = type
                        selected = selection
                    }
                    for ((index, item) in items.withIndex()) {
                        val node = onNodeWithTag(requireNotNull(item.testTag)).fetchSemanticsNode()
                        val bounds = node.boundsInRoot
                        assertTrue(
                            bounds.height >= minimum && bounds.width >= minimum,
                            "$type selected=$selection ${item.testTag}: $bounds, minimum ${minimum}px",
                        )
                        assertEquals(Role.Tab, node.config[SemanticsProperties.Role])
                        assertEquals(index == selection, node.config[SemanticsProperties.Selected])
                    }
                }
            }
            onNodeWithTag("nav_library").performClick()
            assertEquals(1, lastSelected)
        }

    private companion object {
        val MINIMUM_TOUCH_TARGET = 48.dp
    }
}
