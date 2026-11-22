// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** A cabinet page ends at the keyboard, without counting the walls below it twice. */
@OptIn(ExperimentalTestApi::class)
class RadioCabinetKeyboardTest {
    @Test
    fun theKeyboardShortensThePageToItsTop() = assertPageHeight(keyboard = 300, expected = WINDOW - 300)

    @Test
    fun aClosedKeyboardLeavesThePageAlone() = assertPageHeight(keyboard = 0, expected = WINDOW - NAVIGATION_BAR - DOCK)

    @Test
    fun aKeyboardShorterThanTheDockLeavesThePageAlone() =
        assertPageHeight(keyboard = 100, expected = WINDOW - NAVIGATION_BAR - DOCK)

    private fun assertPageHeight(
        keyboard: Int,
        expected: Int,
    ) = runDesktopComposeUiTest(width = 320, height = WINDOW) {
        setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets(bottom = NAVIGATION_BAR))
                    .padding(bottom = DOCK.dp),
            ) {
                Box(Modifier.fillMaxSize().aboveKeyboard(DOCK.dp, WindowInsets(bottom = keyboard))) {
                    Box(Modifier.fillMaxSize().testTag(PAGE))
                }
            }
        }

        assertEquals(expected, onNodeWithTag(PAGE).fetchSemanticsNode().size.height)
    }

    private companion object {
        const val PAGE = "page"
        const val WINDOW = 600
        const val NAVIGATION_BAR = 48
        const val DOCK = 80
    }
}
