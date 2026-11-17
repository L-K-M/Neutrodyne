// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StepCounterTest {
    @Test
    fun stepKeysHave48DpTargetsAndDimAtTheEnds() =
        runDesktopComposeUiTest(width = 320, height = 240) {
            var steps = 0
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    StepCounter(
                        label = "Skip back",
                        value = "10 s",
                        onDecrease = null,
                        onIncrease = { steps++ },
                        decreaseLabel = "Shorter",
                        increaseLabel = "Longer",
                    )
                }
            }
            listOf("Shorter", "Longer").forEach { name ->
                val node = onNodeWithContentDescription(name).fetchSemanticsNode()
                val minimum = with(node.layoutInfo.density) { 48.dp.toPx() }
                assertTrue(node.size.width >= minimum && node.size.height >= minimum, "$name target: ${node.size}")
            }
            onNodeWithContentDescription("Shorter").assertIsNotEnabled()
            onNodeWithContentDescription("Longer").performClick()
            assertEquals(1, steps)
        }
}
