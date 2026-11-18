// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The settings slips keep their titles readable in a narrow pane (08 Settings). */
@OptIn(ExperimentalTestApi::class)
class SettingsSlipsTest {
    /**
     * A list pane beside a placeholder is narrow, and the desktop's close-behaviour value is long:
     * the value yields to the title, which must not break inside a word.
     */
    @Test
    fun aLongValueNeverBreaksTheTitle() =
        runDesktopComposeUiTest(width = 260, height = 200) {
            setContent {
                SettingsRow(
                    icon = null,
                    title = TITLE,
                    summary = null,
                    onClick = {},
                    value = VALUE,
                )
            }
            assertEquals(1, onNodeWithText(TITLE, useUnmergedTree = true).textLayout().lineCount)
            assertEquals(2, onNodeWithText(VALUE, useUnmergedTree = true).textLayout().lineCount)
        }

    /** Where even the value's longest word has no room beside the title, it takes its own line. */
    @Test
    fun aValueWithNoRoomBesideTheTitleMovesBelowIt() =
        runDesktopComposeUiTest(width = 160, height = 200) {
            setContent {
                SettingsRow(
                    icon = null,
                    title = TITLE,
                    summary = null,
                    onClick = {},
                    value = VALUE,
                )
            }
            val title = onNodeWithText(TITLE, useUnmergedTree = true)
            val value = onNodeWithText(VALUE, useUnmergedTree = true)
            assertEquals(1, title.textLayout().lineCount)
            val titleBottom = title.fetchSemanticsNode().boundsInRoot.bottom
            val valueTop = value.fetchSemanticsNode().boundsInRoot.top
            assertTrue(valueTop >= titleBottom, "the value ($valueTop) sits below the title ($titleBottom)")
        }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(layouts)
        return layouts.single()
    }

    private companion object {
        const val TITLE = "Desktop"
        const val VALUE = "Quit unless playing or downloading"
    }
}
