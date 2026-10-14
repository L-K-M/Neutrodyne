// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

            // Semantics bounds alone don't prove the text laid out or wasn't clipped — the real
            // TextLayoutResult does. didOverflowWidth trips on subpixel rounding even when the
            // text fits, so the unclipped check is the height axis (the dimension size(48.dp)
            // squeezed); width is covered by the nonzero and in-block bounds checks.
            val dayLayout = textLayoutOf(day)
            val monthLayout = textLayoutOf(month)
            for ((text, layout) in listOf(day to dayLayout, month to monthLayout)) {
                assertTrue(
                    layout.size.width > 0 && layout.size.height > 0,
                    "'$text' must have a nonzero text layout (was ${layout.size})",
                )
                assertFalse(
                    layout.didOverflowHeight,
                    "'$text' overflowed its text layout height (was ${layout.size})",
                )
            }

            val dayBounds =
                onNodeWithText(day, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val monthBounds =
                onNodeWithText(month, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val blockBounds =
                onNodeWithTag(DATE_BLOCK_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot

            // A fixed 48 dp block can't hold two 200 % lines — the block must have grown.
            assertTrue(
                blockBounds.height > with(density) { 48.dp.toPx() },
                "At 200 % text the date block must exceed 48 dp (was $blockBounds)",
            )
            assertTrue(
                dayBounds.top >= blockBounds.top &&
                    dayBounds.bottom <= blockBounds.bottom &&
                    monthBounds.top >= blockBounds.top &&
                    monthBounds.bottom <= blockBounds.bottom,
                "Day and month must stay inside the date block " +
                    "(day=$dayBounds, month=$monthBounds, block=$blockBounds)",
            )
        }

    @Test
    fun hoveredRowsOverflowLeavesThePlayButtonClickable() =
        runDesktopComposeUiTest(width = 400, height = 200) {
            val actions = mutableListOf<EpisodeAction>()
            setRow(testEpisodeRow(1), onAction = { actions += it })

            // On hover the trailing overflow button appears; it must take its own space. An
            // overlay centred on the row's end sits on top of the play affordance and wins
            // hit-testing exactly when a mouse user reaches for it.
            onNodeWithText("Episode 1").performMouseInput { moveTo(center) }
            waitForIdle()

            onNodeWithContentDescription("Open", useUnmergedTree = true)
                .performMouseInput { click(center) }
            assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayToggle(1)), actions)
            onNodeWithText("Mark played").assertDoesNotExist()

            onNodeWithContentDescription("More actions", useUnmergedTree = true)
                .performMouseInput { click(center) }
            onNodeWithText("Mark played").assertIsDisplayed()
        }

    @Test
    fun menuActionsFollowEachDownloadState() =
        runDesktopComposeUiTest(width = 400, height = 200) {
            var downloadState by mutableStateOf<DownloadState?>(DownloadState.QUEUED)
            setContent {
                CompositionLocalProvider(
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                    LocalUiClock provides TestClock(),
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        EpisodeRow(
                            row = testEpisodeRow(1, downloadState = downloadState),
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

            // 08's tap table: Cancel while queued or resolving, nothing while verifying,
            // Pause while downloading, Resume while paused ("Download now" waits for M6).
            val downloadLabels =
                listOf(
                    "Download",
                    "Download now",
                    "Cancel download",
                    "Pause download",
                    "Resume download",
                    "Retry download",
                    "Delete download",
                )

            downloadState = DownloadState.QUEUED
            waitForIdle()
            assertEquals(listOf("Cancel download"), downloadActionLabels(downloadLabels))

            downloadState = DownloadState.RESOLVING
            waitForIdle()
            assertEquals(listOf("Cancel download"), downloadActionLabels(downloadLabels))

            downloadState = DownloadState.VERIFYING
            waitForIdle()
            assertEquals(emptyList(), downloadActionLabels(downloadLabels))

            downloadState = DownloadState.DOWNLOADING
            waitForIdle()
            assertEquals(listOf("Pause download"), downloadActionLabels(downloadLabels))

            downloadState = DownloadState.PAUSED
            waitForIdle()
            assertEquals(listOf("Resume download"), downloadActionLabels(downloadLabels))

            downloadState = DownloadState.COMPLETED
            waitForIdle()
            assertEquals(listOf("Delete download"), downloadActionLabels(downloadLabels))

            downloadState = DownloadState.FAILED
            waitForIdle()
            assertEquals(listOf("Retry download"), downloadActionLabels(downloadLabels))

            downloadState = DownloadState.MISSING
            waitForIdle()
            assertEquals(listOf("Retry download"), downloadActionLabels(downloadLabels))

            downloadState = null
            waitForIdle()
            assertEquals(listOf("Download"), downloadActionLabels(downloadLabels))
        }

    @Test
    fun offlineNotDownloadedRowPlayAffordanceStillDispatches() =
        runDesktopComposeUiTest(width = 400, height = 200) {
            // 08's row table: offline and not downloaded -> the play affordance stays enabled as
            // cloud_off at 60 % alpha; the tap still dispatches PlayToggle so the screen can
            // answer with the offline snackbar. The button clears its semantics for the merged
            // row, so the tap goes through real touch input on the icon's centre.
            val actions = mutableListOf<EpisodeAction>()
            setRow(
                testEpisodeRow(1),
                caps = RowCaps.FULL.copy(offline = true),
                onAction = { actions += it },
            )
            onNodeWithContentDescription("Open", useUnmergedTree = true)
                .performTouchInput { click(center) }
            assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayToggle(1)), actions)
        }

    /** The row's custom-action labels intersected with the download catalogue (order kept). */
    private fun ComposeUiTest.downloadActionLabels(downloadLabels: List<String>): List<String> =
        onNodeWithText("Episode 1")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .map { it.label }
            .filter { it in downloadLabels }

    private fun ComposeUiTest.textLayoutOf(text: String): TextLayoutResult {
        val node = onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val action =
            node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null }
                ?: error("'$text' exposes no GetTextLayoutResult action")
        val results = mutableListOf<TextLayoutResult>()
        action.action?.invoke(results)
        return results.single()
    }

    private fun ComposeUiTest.setRow(
        row: ch.lkmc.neutrodyne.core.model.EpisodeRow,
        fontScale: Float = 1f,
        caps: RowCaps = RowCaps.FULL,
        onAction: (EpisodeAction) -> Unit = {},
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
                            caps = caps,
                            highlightNew = false,
                            selected = null,
                            onAction = onAction,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }
}
