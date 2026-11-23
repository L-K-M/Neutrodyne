// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The switch in dense panels keeps the platform's 48 dp touch target without a larger plate. */
@OptIn(ExperimentalTestApi::class)
class RadioSwitchTest {
    @Test
    fun theInteractiveSwitchReservesA48DpHitTarget() =
        runDesktopComposeUiTest(width = 320, height = 240) {
            var checked by mutableStateOf(false)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioSwitch(
                        checked = checked,
                        onCheckedChange = { checked = it },
                        modifier = Modifier.testTag("switch"),
                    )
                }
            }
            val node =
                onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                    .fetchSemanticsNodes()
                    .single()
            val minimum = with(node.layoutInfo.density) { 48.dp.toPx() }
            assertTrue(
                node.size.width >= minimum && node.size.height >= minimum,
                "switch hit target is ${node.size}, below the 48 dp minimum",
            )
            onNodeWithTag("switch").assertIsOff().performClick()
            assertTrue(checked)
            onNodeWithTag("switch").assertIsOn().performClick()
            assertEquals(false, checked)
        }
}
