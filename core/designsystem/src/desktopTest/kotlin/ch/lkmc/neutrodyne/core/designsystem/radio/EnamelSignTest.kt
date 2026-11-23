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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A page's sign keeps its whole name in a narrow pane (08 Nd wrappers). */
@OptIn(ExperimentalTestApi::class)
class EnamelSignTest {
    /**
     * A list pane beside a detail is narrow: the Library's sign, between its keys, had room for
     * "Libra…" only. Somewhat less room letters the name smaller; far too little cuts it short.
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
            val sign = width(onNodeWithTag(SIGN))
            val name = width(onNodeWithText(NAME, useUnmergedTree = true))
            val frame = sign - name
            val whole = layout()
            assertTrue(whole.showsWholeName(), "the sign cut its name short at its natural width")
            val lettering = whole.lettering()

            // The rooms leave the sign's frame whole and squeeze only its name.
            room = frame + name * SOMEWHAT_LESS
            waitForIdle()
            val smaller = layout()
            assertTrue(smaller.showsWholeName(), "the sign cut \"$NAME\" short in $room")
            assertTrue(smaller.lettering() < lettering, "the sign kept its full-size lettering in $room")

            // Far too little room: the lettering stops at three quarters of its size and the name
            // is cut short instead.
            room = frame + name * FAR_TOO_LITTLE
            waitForIdle()
            val cut = layout()
            assertFalse(cut.showsWholeName(), "the sign kept \"$NAME\" whole in $room")
            assertEquals(lettering * SMALLEST_LETTERING, cut.lettering(), ADVANCE_TOLERANCE, "letter advance in $room")
        }

    /**
     * A name that ends in a space still letters at full size when the room fits its visible
     * glyphs: the auto-size must not count the invisible trailing space against the name.
     */
    @Test
    fun aNameEndingInASpaceKeepsItsFullLettering() =
        runDesktopComposeUiTest(width = 400, height = 200) {
            var padded by mutableStateOf(true)
            setContent {
                Box {
                    EnamelSign(if (padded) "$NAME " else NAME, Modifier.testTag(SIGN))
                }
            }
            val paddedLettering = layout(if (padded) "$NAME " else NAME).lettering()
            padded = false
            waitForIdle()
            val plainLettering = layout(NAME).lettering()
            assertEquals(
                plainLettering,
                paddedLettering,
                ADVANCE_TOLERANCE,
                "a trailing space shrank the sign's lettering",
            )
        }

    private fun ComposeUiTest.width(node: SemanticsNodeInteraction): Dp =
        with(density) {
            node
                .fetchSemanticsNode()
                .size.width
                .toDp()
        }

    // The visible end: isLineEllipsized misses a cut single line here, and the sign's tight line
    // height makes every layout overflow vertically.
    private fun TextLayoutResult.showsWholeName(): Boolean = getLineEnd(0, visibleEnd = true) == NAME.length

    // The resolved auto size is not in the layout's style; the sign's Courier is monospaced, so its
    // first letter's advance scales with the lettering.
    private fun TextLayoutResult.lettering(): Float = getHorizontalPosition(1, true) - getHorizontalPosition(0, true)

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.layout(
        name: String = NAME,
    ): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(name, useUnmergedTree = true)
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
        const val SOMEWHAT_LESS = 0.9f
        const val FAR_TOO_LITTLE = 0.6f
        const val SMALLEST_LETTERING = 0.75f
        const val ADVANCE_TOLERANCE = 0.05f
    }
}
