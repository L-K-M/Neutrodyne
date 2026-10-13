// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `EmptyState`'s 400 dp cap (08 Banners, snackbars and undo): on a wide window the content column
 * caps at 400 dp centred, it does not stretch the full width — `widthIn` after `fillMaxSize` on
 * one node clamps the cap away.
 */
@OptIn(ExperimentalTestApi::class)
class EmptyStateTest {
    @Test
    fun bodyStaysInsideTheFourHundredDpCapOnWideWindows() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            val body = "Nothing matches this filter right now. ".repeat(10)
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    EmptyState(
                        icon = NdIcons.Download,
                        title = "Nothing here",
                        body = body,
                    )
                }
            }
            waitForIdle()

            val density = onNodeWithText("Nothing here").fetchSemanticsNode().layoutInfo.density
            val capPx = with(density) { 400.dp.toPx() }
            val centrePx = with(density) { 500.dp.toPx() }
            val bounds = onNodeWithText(body).fetchSemanticsNode().boundsInRoot
            assertTrue(
                bounds.width <= capPx + 1,
                "empty-state body is ${bounds.width} px wide, over the 400 dp cap ($capPx px)",
            )
            assertTrue(
                abs(bounds.center.x - centrePx) <= 8,
                "empty-state body is not centred (centre ${bounds.center.x} vs $centrePx)",
            )
        }
}
