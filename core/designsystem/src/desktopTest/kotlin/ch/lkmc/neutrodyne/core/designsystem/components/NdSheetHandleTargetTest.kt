// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NdSheetHandleTargetTest {
    @Test
    fun dragHandleLaysOutAReal48DpTarget() =
        runDesktopComposeUiTest(width = 480, height = 640) {
            var open by mutableStateOf(true)
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    if (open) {
                        NdModalBottomSheet(onDismissRequest = { open = false }) { Text("Sheet body") }
                    }
                }
            }
            waitForIdle()
            // The M3 drag handle is the sheet's only content-description node here. It carries
            // expand/collapse semantics, so the on-device ATF audit measures its layout as a
            // touch target: 08's 48 dp minimum applies, not Material's 32 dp stock width.
            val described =
                SemanticsMatcher("describes itself") { it.config.contains(SemanticsProperties.ContentDescription) }
            val nodes = onAllNodes(described, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue(nodes.isNotEmpty(), "the drag handle node must be exposed")
            for (node in nodes) {
                val width = with(density) { node.size.width.toDp() }
                val height = with(density) { node.size.height.toDp() }
                assertTrue(width >= 48.dp && height >= 48.dp, "touch target ${width}x$height: ${node.config}")
            }
        }
}
