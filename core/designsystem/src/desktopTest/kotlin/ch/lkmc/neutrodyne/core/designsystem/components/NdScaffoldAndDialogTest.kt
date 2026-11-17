// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The review fixes of 2026-10-06: `NdDialog`'s text must scroll inside the dialog's bounded body
 * (long licence terms stay reachable). The navigation suite these tests also covered was removed
 * with the radio-wide look; the radio home replaced it in #515.
 */
@OptIn(ExperimentalTestApi::class)
class NdScaffoldAndDialogTest {
    @Test
    fun dialogTextScrollsInsideItsBoundedBody() =
        runComposeUiTest {
            val longText = List(400) { "licence line $it" }.joinToString("\n")
            setContent {
                NdDialog(onDismissRequest = {}, title = "Licence", text = longText)
            }

            val scrollable = onAllNodes(hasScrollAction()).onFirst().fetchSemanticsNode()
            val range =
                scrollable.config.getOrElse(SemanticsProperties.VerticalScrollAxisRange) {
                    ScrollAxisRange(value = { 0f }, maxValue = { 0f })
                }
            assertTrue(
                range.maxValue() > 0f,
                "the dialog body must scroll with a positive range (max was ${range.maxValue()})",
            )
        }

    /**
     * A destructive confirm draws in the scheme's error colour; an ordinary action keeps the
     * default content colour (94-3). The scheme pins error to pure red so antialiased glyph
     * pixels stay red-dominant while the purple primary never is.
     */
    @Test
    fun destructiveConfirmUsesErrorColour() =
        runComposeUiTest {
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(error = Color(0xFFFF0000))) {
                    NdDialog(
                        onDismissRequest = {},
                        title = "Confirm",
                        text = "Body",
                        confirm = NdDialogAction("Delete", role = NdActionRole.DESTRUCTIVE) {},
                        dismiss = NdDialogAction("Cancel") {},
                    )
                }
            }

            // Saturated red only: the keys' rendered ivory and brass shade into warm browns.
            fun redDominantPixelCount(label: String): Int {
                val map = onNodeWithText(label).captureToImage().toPixelMap()
                var count = 0
                for (x in 0 until map.width) {
                    for (y in 0 until map.height) {
                        val c = map[x, y]
                        if (c.alpha > 0f && c.red > 0.6f && c.green < 0.25f && c.blue < 0.25f) count++
                    }
                }
                return count
            }
            assertTrue(
                redDominantPixelCount("Delete") > 0,
                "the destructive confirm must draw in the error colour",
            )
            assertTrue(
                redDominantPixelCount("Cancel") == 0,
                "a normal action must not draw in the error colour",
            )
        }
}
