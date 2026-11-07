// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TextFieldFocusTest {
    @Test
    fun aFocusedFieldLeavingCompositionReleasesTheShortcuts() =
        runComposeUiTest {
            val state = TextFieldFocusState()
            var shown by mutableStateOf(true)
            setContent {
                CompositionLocalProvider(LocalTextFieldFocusState provides state) {
                    if (shown) {
                        BasicTextField("", {}, Modifier.ndTextFieldFocus().testTag("field"))
                    }
                }
            }
            onNodeWithTag("field").requestFocus()
            waitForIdle()
            assertTrue(state.active)

            // Navigating away while typing removes the focused field.
            shown = false
            waitForIdle()

            assertFalse(state.active, "the root's shortcuts must not stay disabled")
        }
}
