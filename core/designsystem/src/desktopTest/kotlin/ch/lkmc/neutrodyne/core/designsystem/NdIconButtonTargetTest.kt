// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NdIconButtonTargetTest {
    @Test fun glyphOnlyButtonsHaveReal48DpClickLayouts() =
        runDesktopComposeUiTest(width = 160, height = 160) {
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    NdIconButton({}, NdIcons.MoreVert, "More")
                }
            }
            val nodes = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue(nodes.isNotEmpty())
            nodes.forEach { node ->
                val minimum = with(node.layoutInfo.density) { 48.dp.toPx() }
                assertTrue(node.size.width >= minimum && node.size.height >= minimum, "Clickable layout: ${node.size}")
            }
        }
}
