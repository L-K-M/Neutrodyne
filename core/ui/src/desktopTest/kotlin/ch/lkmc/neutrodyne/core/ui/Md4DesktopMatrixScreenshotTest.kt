// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdScrollbarStyle
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import io.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import kotlin.test.Test

/**
 * MD4 AC2's desktop matrix: 08's episode-row states at the three desktop window widths
 * (600, 900 and 1,400 dp) in light and dark. The rows are capped at 720 dp and centred,
 * as 08 Adaptive layouts requires inside wider panes; the label above each row names its
 * state so a golden can be reviewed against the test name. The captures run in the PR tier:
 * they are the acceptance evidence, so CI verifies them on every change.
 */
@OptIn(ExperimentalTestApi::class)
class Md4DesktopMatrixScreenshotTest {
    @Test
    fun episodeRowStatesAcrossDesktopWidths() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            installFakeImageLoader()
            val pages = md4MatrixRows.chunked(2)
            for (dark in listOf(false, true)) {
                for ((index, page) in pages.withIndex()) {
                    for (width in WIDTHS) {
                        runDesktopComposeUiTest(width = width, height = 800) {
                            setContent {
                                Md4MatrixSurface(dark) {
                                    page.forEach { state ->
                                        Text(
                                            state.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        EpisodeRow(
                                            state.row,
                                            state.live,
                                            EpisodeRowStyle.FEED,
                                            state.caps,
                                            highlightNew = true,
                                            selected = null,
                                            onAction = {},
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                            waitForIdle()
                            val states = page.joinToString("_") { it.name }
                            val theme = if (dark) "dark" else "light"
                            onRoot().captureRoboImage(
                                "screenshots/desktop/md4-rows-$states-$theme-w$width.png",
                            )
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** 08's MD4 extras: the hover and keyboard-focus states of a row at the default width. */
    @Test
    fun rowHoverAndFocusStates() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            installFakeImageLoader()
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                runDesktopComposeUiTest(width = 900, height = 800) {
                    setContent {
                        Md4MatrixSurface(dark) {
                            EpisodeRow(
                                md4MatrixRows.first().row,
                                null,
                                EpisodeRowStyle.FEED,
                                RowCaps.FULL,
                                highlightNew = false,
                                selected = null,
                                onAction = {},
                                modifier = Modifier.fillMaxWidth().testTag("matrixRow"),
                            )
                        }
                    }
                    waitForIdle()
                    onNodeWithTag("matrixRow").performMouseInput { moveTo(center) }
                    waitForIdle()
                    onRoot().captureRoboImage("screenshots/desktop/md4-row-hover-$theme-w900.png")
                }
                runDesktopComposeUiTest(width = 900, height = 800) {
                    setContent {
                        Md4MatrixSurface(dark) {
                            EpisodeRow(
                                md4MatrixRows.first().row,
                                null,
                                EpisodeRowStyle.FEED,
                                RowCaps.FULL,
                                highlightNew = false,
                                selected = null,
                                onAction = {},
                                modifier = Modifier.fillMaxWidth().testTag("matrixRow"),
                            )
                        }
                    }
                    waitForIdle()
                    onNodeWithTag("matrixRow").performMouseInput { click(center) }
                    onNodeWithTag("matrixRow").performMouseInput { moveTo(center + Offset(0f, 200f)) }
                    waitForIdle()
                    onRoot().captureRoboImage("screenshots/desktop/md4-row-focus-$theme-w900.png")
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** 08's MD4 extra: a list's overlay scrollbar on the desktop, drawn only when enabled. */
    @Test
    fun listScrollbar() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            installFakeImageLoader()
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                runDesktopComposeUiTest(width = 900, height = 800) {
                    setContent {
                        Md4MatrixSurface(dark) {
                            val listState = rememberLazyListState()
                            val bars = NdScrollbarStyle(enabled = true, alpha = 1f)
                            val barColor = ndScrollbarColor(bars)
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth().ndScrollbar(listState, bars, barColor),
                            ) {
                                items(40) { index ->
                                    Text(
                                        "Episode $index",
                                        Modifier.fillMaxWidth().padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    waitForIdle()
                    onRoot().captureRoboImage("screenshots/desktop/md4-scrollbar-$theme-w900.png")
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}

@Composable
private fun Md4MatrixSurface(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    NeutrodyneTheme(
        AppearancePrefs(dynamicColor = false),
        SystemUiState(dark = dark, contrast = 0.0, reducedMotion = true),
    ) {
        CompositionLocalProvider(
            LocalPlatformKind provides PlatformKind.DESKTOP,
            LocalUiClock provides TestClock(),
        ) {
            Surface(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = ROW_MAX_WIDTH)) { content() }
                }
            }
        }
    }
}

private data class Md4MatrixRow(
    val name: String,
    val row: EpisodeRow,
    val live: RowLive? = null,
    val caps: RowCaps = RowCaps.FULL,
)

private val WIDTHS = listOf(600, 900, 1400)
private val ROW_MAX_WIDTH = 720.dp

/** 08's episode-row states: playback, download and wait states, video/YouTube/external. */
private val md4MatrixRows: List<Md4MatrixRow> =
    buildList {
        val row = testEpisodeRow(1, title = "A long episode title that wraps at large text sizes")
        add(Md4MatrixRow("unplayed", row))
        add(Md4MatrixRow("new", row.copy(isNew = true)))
        add(Md4MatrixRow("in-progress", row.copy(startedAt = 1), RowLive(positionMs = 120_000)))
        add(Md4MatrixRow("playing", row, RowLive(isNowPlaying = true, isPlaying = true)))
        add(Md4MatrixRow("paused", row, RowLive(isNowPlaying = true)))
        add(Md4MatrixRow("played", row.copy(playedAt = 1)))
        add(Md4MatrixRow("offline", row, caps = RowCaps.FULL.copy(offline = true)))
        add(Md4MatrixRow("video", row.copy(isVideo = true)))
        add(
            Md4MatrixRow(
                "youtube",
                row.copy(sourceType = SourceType.YOUTUBE_CHANNEL, externalMediaId = "abcdefghijk"),
            ),
        )
        add(
            Md4MatrixRow(
                "external",
                row.copy(sourceType = SourceType.YOUTUBE_CHANNEL, externalMediaId = "abcdefghijk"),
                caps = RowCaps.FULL.copy(inAppPlayback = false, downloads = false),
            ),
        )
        add(Md4MatrixRow("unavailable", row.copy(availability = Availability.UNAVAILABLE)))
        DownloadState.entries.forEach { state ->
            add(
                Md4MatrixRow(
                    "download-${state.name.lowercase()}",
                    row,
                    RowLive(
                        downloadState = state,
                        downloadedBytes = 40,
                        totalBytes = 100,
                        lastError = DownloadError.NETWORK_IO.takeIf { state == DownloadState.FAILED },
                    ),
                ),
            )
        }
        WaitReason.entries.filter { it != WaitReason.NONE }.forEach { reason ->
            add(
                Md4MatrixRow(
                    "wait-${reason.name.lowercase().replace('_', '-')}",
                    row,
                    RowLive(
                        downloadState = DownloadState.QUEUED,
                        waitReason = reason,
                        nextAttemptAt = TestClock.DEFAULT_NOW + 60_000,
                    ),
                ),
            )
        }
    }
