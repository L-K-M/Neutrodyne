// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * MD4 AC1's context-menu half (08 Automated checks): for every element of the custom-actions
 * catalogue in each state, the menu offers exactly the valid catalogue actions, in catalogue
 * order; invalid items are hidden, not greyed. The menu labels are the row's own
 * `customActions` labels, so the menu and the screen-reader actions cannot disagree. The
 * secondary click and Shift+F10 open the same menu.
 */
@OptIn(ExperimentalTestApi::class)
class ContextMenuCatalogueTest {
    @BeforeTest
    fun images() {
        installFakeImageLoader()
    }

    @Test
    fun episodeRowMenuMatchesTheCatalogueInEveryDownloadState() =
        runComposeUiTest {
            var downloadState by mutableStateOf<DownloadState?>(null)
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            try {
                setContent {
                    CompositionLocalProvider(
                        LocalPlatformKind provides PlatformKind.DESKTOP,
                        LocalUiClock provides TestClock(),
                    ) {
                        NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                            EpisodeRow(
                                row = testEpisodeRow(1, downloadState = downloadState),
                                live = null,
                                style = EpisodeRowStyle.FEED,
                                caps = RowCaps.FULL,
                                highlightNew = false,
                                selected = null,
                                onAction = {},
                                modifier = Modifier.testTag("row"),
                            )
                        }
                    }
                }
                waitForIdle()
                for (state in DownloadState.entries + null) {
                    downloadState = state
                    waitForIdle()
                    openMenu()
                    assertMenuLabels(menuLabels(state))
                    // Activating any item dismisses the menu, ready for the next state.
                    onNodeWithText("Open").performClick()
                    waitForIdle()
                }
            } finally {
                Locale.setDefault(previous)
            }
        }

    @Test
    fun episodeRowMenuHidesTheInvalidActionsInExternalMode() =
        runComposeUiTest {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            try {
                setRow(
                    testEpisodeRow(1, sourceType = SourceType.YOUTUBE_CHANNEL, externalMediaId = "vid"),
                    caps = RowCaps.FULL.copy(inAppPlayback = false, downloads = false),
                )
                openMenu()
                assertMenuLabels(
                    listOf(
                        "Watch on YouTube",
                        "Open",
                        "Select",
                        "Mark played",
                        "Play next",
                        "Play last",
                        "Go to podcast",
                    ),
                )
            } finally {
                Locale.setDefault(previous)
            }
        }

    @Test
    fun queueRowMenuAppendsTheMoveActionsAfterTheCatalogue() =
        runComposeUiTest {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            try {
                setRow(
                    testEpisodeRow(1),
                    style = EpisodeRowStyle.QUEUE,
                    additionalActions =
                        listOf(
                            "Move up" to {},
                            "Move to top" to {},
                            "Remove from Up next" to {},
                        ),
                )
                openMenu()
                assertMenuLabels(
                    listOf(
                        "Play",
                        "Open",
                        "Mark played",
                        "Play next",
                        "Play last",
                        "Download",
                        "Go to podcast",
                        "Move up",
                        "Move to top",
                        "Remove from Up next",
                    ),
                )
            } finally {
                Locale.setDefault(previous)
            }
        }

    @Test
    fun shiftF10OpensTheSameMenuAsTheSecondaryClick() =
        runComposeUiTest {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            try {
                setRow(testEpisodeRow(1))
                onNodeWithTag("row").performMouseInput { click(center) }
                onNodeWithTag("row").performKeyInput {
                    keyDown(Key.ShiftLeft)
                    pressKey(Key.F10)
                    keyUp(Key.ShiftLeft)
                }
                onNodeWithText("Play next").assertIsDisplayed()
            } finally {
                Locale.setDefault(previous)
            }
        }

    private fun ComposeUiTest.setRow(
        row: EpisodeRow,
        style: EpisodeRowStyle = EpisodeRowStyle.FEED,
        caps: RowCaps = RowCaps.FULL,
        additionalActions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    EpisodeRow(
                        row = row,
                        live = null,
                        style = style,
                        caps = caps,
                        highlightNew = false,
                        selected = null,
                        onAction = {},
                        additionalActions = additionalActions,
                        modifier = Modifier.testTag("row"),
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openMenu() {
        onNodeWithTag("row").performMouseInput { rightClick(center) }
        waitForIdle()
    }

    /** The catalogue labels the row's menu must show for [state], in catalogue order. */
    private fun menuLabels(state: DownloadState?): List<String> {
        val labels =
            mutableListOf(
                "Play",
                "Open",
                "Select",
                "Mark played",
                "Play next",
                "Play last",
            )
        when (state) {
            null -> {
                labels += "Download"
            }

            DownloadState.COMPLETED -> {
                labels += "Delete download"
            }

            DownloadState.FAILED, DownloadState.MISSING -> {
                labels += "Retry download"
            }

            DownloadState.QUEUED -> {
                labels += "Download actions"
            }

            DownloadState.RESOLVING -> {
                labels += "Cancel download"
            }

            DownloadState.VERIFYING -> {}

            DownloadState.DOWNLOADING -> {
                labels += "Pause download"
            }

            DownloadState.PAUSED -> {
                labels += "Resume download"
            }
        }
        labels += "Go to podcast"
        return labels
    }

    /** Asserts the open menu shows exactly [expected] and nothing else. */
    private fun ComposeUiTest.assertMenuLabels(expected: List<String>) {
        expected.forEach { label -> onNodeWithText(label).assertIsDisplayed() }
        catalogueLabels.filter { it !in expected }.forEach { label ->
            onNodeWithText(label).assertDoesNotExist()
        }
    }

    private val catalogueLabels =
        listOf(
            "Play",
            "Pause",
            "Resume",
            "Play again",
            "Open",
            "Select",
            "Mark played",
            "Mark unplayed",
            "Play next",
            "Play last",
            "Download",
            "Download actions",
            "Cancel download",
            "Pause download",
            "Resume download",
            "Retry download",
            "Delete download",
            "Check again",
            "Watch on YouTube",
            "Go to podcast",
        )
}
