// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * A form's paper strips must keep their names once filled: a dialog with a username and a
 * password, or Discover's two masked keys, is otherwise a row of unnamed strips.
 */
@OptIn(ExperimentalTestApi::class)
class PaperTextFieldTest {
    @Test
    fun aFilledFieldKeepsItsName() =
        runComposeUiTest {
            setContent {
                PaperTextField(value = "alice", onValueChange = {}, label = "Username")
            }

            // Printed in capitals, but read as written.
            onNode(hasSetTextAction() and hasText("Username")).assertExists()
        }
}
