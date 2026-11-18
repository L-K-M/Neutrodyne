// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

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

    @Test
    fun anEmptyFieldIsNamedByItsPlaceholder() =
        runComposeUiTest {
            setContent {
                PaperTextField(value = "", onValueChange = {}, placeholder = "Search podcasts")
            }

            onNode(hasSetTextAction() and hasContentDescription("Search podcasts")).assertExists()
        }

    @Test
    fun aLongNameWrapsRatherThanHidingItsEnd() =
        runComposeUiTest {
            // The German delete prompt is the only place that says what to type, so a narrow
            // dialog must not cut it off.
            val long = "Gib DELETE zur Bestätigung ein"
            setContent {
                Column {
                    PaperTextField(value = "", onValueChange = {}, modifier = Modifier.width(NARROW), label = "Name")
                    PaperTextField(value = "", onValueChange = {}, modifier = Modifier.width(NARROW), label = long)
                }
            }

            fun labelHeight(label: String) =
                onNode(hasText(label) and !hasSetTextAction(), useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                    .let { it.bottom - it.top }
            assertTrue(labelHeight(long) > labelHeight("Name") * 1.5f, "the long name should take more than one line")
        }

    private companion object {
        val NARROW = 140.dp
    }
}
