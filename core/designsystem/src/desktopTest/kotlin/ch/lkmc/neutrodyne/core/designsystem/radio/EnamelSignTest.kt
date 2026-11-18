// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import kotlin.test.Test
import kotlin.test.assertTrue

/** A page's sign keeps its whole name in a narrow pane (08 Nd wrappers). */
@OptIn(ExperimentalTestApi::class)
class EnamelSignTest {
    /**
     * A list pane beside a detail is narrow: the Library's sign, between its keys, had room for
     * "Libra…" only. Somewhat less room than the sign's natural width letters the name smaller.
     */
    @Test
    fun aNarrowSignLettersItsNameSmallerBeforeCuttingIt() =
        runDesktopComposeUiTest(width = 400, height = 200) {
            var room by mutableStateOf<Dp?>(null)
            setContent {
                val limit = room
                Box(if (limit == null) Modifier else Modifier.width(limit)) {
                    EnamelSign(NAME, Modifier.testTag(SIGN))
                }
            }
            val natural =
                with(density) {
                    onNodeWithTag(SIGN)
                        .fetchSemanticsNode()
                        .size.width
                        .toDp()
                }
            assertTrue(layout().showsWholeName(), "the sign cut its name short at its natural width")

            room = natural * NARROWER
            waitForIdle()
            assertTrue(layout().showsWholeName(), "the sign cut \"$NAME\" short in $room")
        }

    // The visible end: isLineEllipsized misses a cut single line here, and the sign's tight line
    // height makes every layout overflow vertically.
    private fun TextLayoutResult.showsWholeName(): Boolean = getLineEnd(0, visibleEnd = true) == NAME.length

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(NAME, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(layouts)
        return layouts.single()
    }

    private companion object {
        const val NAME = "Library"
        const val SIGN = "sign"
        const val NARROWER = 0.85f
    }
}
