// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AdaptiveRowBoundsTest {
    private var previousLocale = Locale.ENGLISH

    @BeforeTest fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun widePaneCentersThe720DpEpisodeRow() =
        runDesktopComposeUiTest(width = 1104, height = 400) {
            installFakeImageLoader()
            val actions = mutableListOf<EpisodeAction>()
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    EpisodeRow(
                        testEpisodeRow(1),
                        null,
                        EpisodeRowStyle.FEED,
                        RowCaps.FULL,
                        false,
                        null,
                        { actions += it },
                    )
                }
            }
            val bounds = onNodeWithText("Episode 1").fetchSemanticsNode().boundsInRoot
            assertEquals(720f, bounds.width)
            assertEquals(192f, bounds.left)
            onNodeWithText("Episode 1").performCustomAccessibilityActionWithLabel("Play")
            runOnIdle { assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayToggle(1)), actions) }
        }

    @Test
    fun minimumContentPaneAtDoubleFontKeepsRowActionOperable() =
        runDesktopComposeUiTest(width = 300, height = 600) {
            installFakeImageLoader()
            val actions = mutableListOf<EpisodeAction>()
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        EpisodeRow(
                            testEpisodeRow(1),
                            null,
                            EpisodeRowStyle.FEED,
                            RowCaps.FULL,
                            false,
                            null,
                            { actions += it },
                        )
                    }
                }
            }
            val bounds = onNodeWithText("Episode 1").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= 0f && bounds.right <= 300f && bounds.bottom <= 600f)
            onNodeWithText("Episode 1").assertIsDisplayed().performCustomAccessibilityActionWithLabel("Play")
            runOnIdle { assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayToggle(1)), actions) }
        }
}
