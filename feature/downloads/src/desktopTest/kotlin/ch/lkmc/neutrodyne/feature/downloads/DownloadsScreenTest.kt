// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testDownloadEntry
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import ch.lkmc.neutrodyne.download.api.StorageUsage
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DownloadsScreenTest {
    private lateinit var locale: Locale

    @BeforeTest fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest fun teardown() {
        Locale.setDefault(locale)
    }

    private val storage = StorageUsage("dir:default", 0, 0, 0, null, null, false)
    private val empty = DownloadsOverview(emptyList(), emptyList(), emptyList(), storage, emptySet())

    @Test fun emptyLoadingLinksToSettingsWithoutImplicitBulkRequests() =
        runDesktopComposeUiTest(width = 600, height = 900) {
            val state = mutableStateOf(DownloadsUiState())
            setContent {
                CompositionLocalProvider(
                    LocalAppNavigator provides RecordingAppNavigator(),
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { Screen(state.value) }
                }
            }
            onNodeWithText("Loading downloads").assertIsDisplayed()
            state.value = DownloadsUiState(overview = empty, storage = storage)
            waitForIdle()
            onNodeWithText("Downloaded episodes play offline").assertIsDisplayed()
            onNodeWithText("Download settings").assertIsDisplayed()
            onNodeWithText("Auto-download settings").assertDoesNotExist()
            onNodeWithText("Download all").assertDoesNotExist()
        }

    @Test fun offlineWaitAndFailureRowsHaveActionsAndDesktopHidesShare() =
        runDesktopComposeUiTest(
            width =
            900,
            height = 1400,
        ) {
            val overview =
                empty.copy(
                    inProgress = listOf(testDownloadEntry(1, wait = WaitReason.NONE)),
                    failed = listOf(testDownloadEntry(2, DownloadState.FAILED)),
                    completed =
                        listOf(testDownloadEntry(3, DownloadState.COMPLETED)),
                )
            val played = mutableListOf<Long?>()
            val retried = mutableListOf<Long>()
            val deleted = mutableListOf<Long>()
            setContent {
                CompositionLocalProvider(
                    LocalAppNavigator provides RecordingAppNavigator(),
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        Screen(
                            DownloadsUiState(overview = overview, storage = storage, offline = true),
                            onPlay = { played += it },
                            onToggle = { retried += it },
                            onDelete = { deleted += it },
                        )
                    }
                }
            }
            onNodeWithText("Waiting for a connection", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Play all").performClick()
            assertEquals(listOf<Long?>(null), played)
            onNodeWithContentDescription(
                "Episode 2. Podcast. Download failed",
            ).performCustomAccessibilityActionWithLabel("Retry download")
            assertEquals(listOf(2L), retried)
            onNodeWithContentDescription("Episode 3. Podcast. 0 B").performCustomAccessibilityActionWithLabel("Play")
            assertEquals(listOf(null, 3L), played)
            onNodeWithText("Share file").assertDoesNotExist()
            onNodeWithContentDescription("Episode 3. Podcast. 0 B").performMouseInput { click(centerLeft) }
            onNodeWithContentDescription("Episode 3. Podcast. 0 B").performKeyInput { pressKey(Key.Delete) }
            assertEquals(listOf(3L), deleted)
        }

    @Test fun onlyTwoHighestPriorityNoticesRender() =
        runDesktopComposeUiTest(width = 900, height = 1200) {
            val overview =
                empty.copy(
                    notices =
                        setOf(
                            DownloadNotice.STORAGE_LOW,
                            DownloadNotice.DATA_SAVER,
                            DownloadNotice.BACKGROUND_RESTRICTED,
                        ),
                )
            setContent {
                CompositionLocalProvider(
                    LocalAppNavigator provides RecordingAppNavigator(),
                    LocalPlatformKind provides PlatformKind.ANDROID,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        Screen(
                            DownloadsUiState(overview = overview, storage = storage),
                        )
                    }
                }
            }
            onNodeWithText(
                "Data Saver is on. Downloads over mobile data may pause while Neutrodyne is in the background.",
            ).assertIsDisplayed()
            onNodeWithText(
                "Background activity is restricted for Neutrodyne. Automatic downloads only run while the app is open.",
            ).assertIsDisplayed()
            onNodeWithText("Not enough storage. 0 downloads are waiting.").assertDoesNotExist()
        }

    @Test fun automaticCapAndMoveNoticesUseTheManualDownloadsScreen() =
        runDesktopComposeUiTest(width = 900, height = 1200) {
            val overview =
                empty.copy(
                    storage = storage.copy(capBytes = 1_073_741_824L),
                    notices = setOf(DownloadNotice.CAP_REACHED, DownloadNotice.MOVE_IN_PROGRESS),
                )
            val actions = mutableListOf<DownloadNotice>()
            setContent {
                CompositionLocalProvider(
                    LocalAppNavigator provides RecordingAppNavigator(),
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        Screen(DownloadsUiState(overview = overview, storage = overview.storage), onNotice = {
                            actions +=
                                it
                        })
                    }
                }
            }
            onNodeWithText("Storage limit of 1 GB reached. Automatic downloads are paused.").assertIsDisplayed()
            onNodeWithText("Moving downloads…").assertIsDisplayed()
            onAllNodesWithText("Download settings")[1].performClick()
            assertEquals(listOf(DownloadNotice.CAP_REACHED), actions)
        }

    @androidx.compose.runtime.Composable
    private fun Screen(
        state: DownloadsUiState,
        onPlay: (Long?) -> Unit = {},
        onToggle: (Long) -> Unit = {},
        onDelete: (Long) -> Unit = {},
        onNotice: (DownloadNotice) -> Unit = {},
    ) {
        DownloadsScreen(
            state = state,
            live = emptyMap(),
            onVisibleIds = {},
            onOpen = {},
            onToggle = onToggle,
            onCommand = { _, _ -> },
            onCancel = {},
            onDelete = onDelete,
            onPlay = onPlay,
            onShare = null,
            onPauseAll = {},
            onResumeAll = {},
            onRetryAll = {},
            onSelect = {},
            onSelectAll = {},
            onSelectPlayedEpisodes = {},
            onClearSelection = {},
            onDeleteSelection = {},
            onSettings = {},
            onReload = {},
            onNotice = onNotice,
        )
    }
}
