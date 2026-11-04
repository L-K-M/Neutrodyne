// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `EmptyState`'s 400 dp cap (08 Banners, snackbars and undo): on a wide window the content column
 * caps at 400 dp centred, it does not stretch the full width — `widthIn` after `fillMaxSize` on
 * one node clamps the cap away.
 */
@OptIn(ExperimentalTestApi::class)
class EmptyStateTest {
    @Test
    fun allOnboardingActionsRemainReachableInAShortWindow() =
        runDesktopComposeUiTest(width = 320, height = 180) {
            val clicked = mutableListOf<String>()
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    EmptyState(
                        icon = NdIcons.Download,
                        title = "Your feeds live here",
                        body = "Search for podcasts, import subscriptions or add a feed URL.",
                        actionLabel = "Search",
                        onAction = { clicked += "Search" },
                        secondaryLabel = "Import",
                        onSecondary = { clicked += "Import" },
                        tertiaryLabel = "Add by URL",
                        onTertiary = { clicked += "Add by URL" },
                    )
                }
            }
            for (label in listOf("Search", "Import", "Add by URL")) {
                onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
            }
            assertEquals(listOf("Search", "Import", "Add by URL"), clicked)
        }

    @Test
    fun aLazyParentKeepsOwnershipOfScrolling() =
        runDesktopComposeUiTest(width = 320, height = 180) {
            var clicked = false
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            EmptyState(
                                icon = NdIcons.Download,
                                title = "Nothing here",
                                body = "Add a podcast to get started.",
                                actionLabel = "Search",
                                onAction = { clicked = true },
                            )
                        }
                    }
                }
            }
            onNodeWithText("Search").performScrollTo().assertIsDisplayed().performClick()
            assertTrue(clicked)
        }

    @Test
    fun ordinaryOnboardingStaysVerticallyCentered() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    EmptyState(NdIcons.Download, "Nothing here", "Start with a podcast")
                }
            }
            val title = onNodeWithText("Nothing here").fetchSemanticsNode().boundsInRoot
            assertTrue(title.center.y in 300f..450f, "Onboarding is top-aligned: $title")
        }

    @Test
    fun shortWindowAtDoubleFontKeepsEveryActionReachable() =
        runDesktopComposeUiTest(width = 360, height = 240) {
            var clicks = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        EmptyState(
                            NdIcons.GridView,
                            "Your feeds live here",
                            "Long onboarding text. ".repeat(8),
                            showBrandMark = true,
                            actionLabel = "Search podcasts",
                            onAction = { clicks++ },
                            secondaryLabel = "Import subscriptions",
                            onSecondary = { clicks++ },
                            tertiaryLabel = "Add by URL or YouTube link",
                            onTertiary = { clicks++ },
                        )
                    }
                }
            }
            onNodeWithTag("onboarding-brand-mark").assertExists()
            listOf("Search podcasts", "Import subscriptions", "Add by URL or YouTube link").forEach {
                onNodeWithText(it).performScrollTo().assertIsDisplayed().performClick()
            }
            assertTrue(clicks == 3)
        }

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
