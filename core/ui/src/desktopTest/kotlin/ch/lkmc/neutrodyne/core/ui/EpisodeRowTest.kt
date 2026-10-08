// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `EpisodeRow` through `runComposeUiTest` (08 EpisodeRow): the PODCAST leading date block shows
 * the day over the abbreviated month inside a 48 dp slot; at 200 % text the block must grow so
 * both lines stay inside it (08's "no fixed heights" accessibility rule).
 */
@OptIn(ExperimentalTestApi::class)
class EpisodeRowTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun dateBlockFitsDayAndMonthAtLargeText() =
        runDesktopComposeUiTest(width = 400, height = 400) {
            // Same artwork key as the podcast -> the date block leads, not a cover.
            val row = testEpisodeRow(1)
            setRow(row, fontScale = 2f)
            val (day, month) = FeedDates.dayMonth(row.pubDate ?: row.sortDate)

            val dayBounds =
                onNodeWithText(day, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val monthBounds =
                onNodeWithText(month, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val blockBounds =
                onNodeWithTag(DATE_BLOCK_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot

            assertTrue(
                dayBounds.top >= blockBounds.top &&
                    dayBounds.bottom <= blockBounds.bottom &&
                    monthBounds.top >= blockBounds.top &&
                    monthBounds.bottom <= blockBounds.bottom,
                "Day and month must stay inside the date block " +
                    "(day=$dayBounds, month=$monthBounds, block=$blockBounds)",
            )
        }

    private fun ComposeUiTest.setRow(
        row: ch.lkmc.neutrodyne.core.model.EpisodeRow,
        fontScale: Float = 1f,
    ) {
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale),
                    ) {
                        EpisodeRow(
                            row = row,
                            live = null,
                            style = EpisodeRowStyle.PODCAST,
                            caps = RowCaps.FULL,
                            highlightNew = false,
                            selected = null,
                            onAction = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
    }
}
