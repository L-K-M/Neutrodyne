// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.root.LocalRootScope
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The expandable "What syncs" row on Settings › Sync (08 "Rows while linked", 10 What syncs):
 * it exists only while `Linked`, starts collapsed, and expanding it shows the full scope —
 * synced categories and the device-local exclusions. The expansion belongs to the linked
 * (server, account, device) identity: an `activity` update keeps it, a relink drops it.
 */
@OptIn(ExperimentalTestApi::class)
class SyncWhatSyncsTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun theRowOnlyExistsWhileLinked() =
        runComposeUiTest {
            val harness = Harness(SyncStatus.NotConfigured)
            setScreen(harness)

            onNodeWithText("What syncs").assertDoesNotExist()

            harness.controller.status.value = LINKED
            waitForIdle()

            onNodeWithText("What syncs").assertIsDisplayed()
        }

    @Test
    fun expandingShowsTheFullScopeAndCollapsingHidesIt() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("private feed links", substring = true).assertDoesNotExist()
            onNodeWithText("Not synced:", substring = true).assertDoesNotExist()
            onNodeWithText("What syncs")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))

            onNodeWithText("What syncs").performClick()
            waitForIdle()

            onNodeWithText("What syncs")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
            onNodeWithText("subscriptions (including private feed links)", substring = true)
                .assertIsDisplayed()
            onNodeWithText("groups and their order", substring = true).assertIsDisplayed()
            onNodeWithText("Up next, what’s playing", substring = true).assertIsDisplayed()
            onNodeWithText("with Sync playback settings", substring = true).assertIsDisplayed()
            onNodeWithText("Not synced: downloads and audio files", substring = true)
                .assertIsDisplayed()
            onNodeWithText("refresh schedule, appearance", substring = true).assertIsDisplayed()
            onNodeWithText("the YouTube engine and update settings", substring = true)
                .assertIsDisplayed()

            onNodeWithText("What syncs").performClick()
            waitForIdle()

            onNodeWithText("private feed links", substring = true).assertDoesNotExist()
            onNodeWithText("Not synced:", substring = true).assertDoesNotExist()
        }

    @Test
    fun expansionSurvivesStatusUpdatesAndDiesWithIdentity() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("What syncs").performClick()
            waitForIdle()
            onNodeWithText("private feed links", substring = true).assertIsDisplayed()

            // Same identity, fresh activity/timestamps — live status updates keep the state.
            harness.controller.status.value =
                LINKED.copy(activity = SyncActivity.SYNCING, lastSyncAtMs = 1234L)
            waitForIdle()
            onNodeWithText("private feed links", substring = true).assertIsDisplayed()

            // A different (server, account, device) re-keys the section and resets the row.
            harness.controller.status.value = OTHER_ACCOUNT
            waitForIdle()
            onNodeWithText("private feed links", substring = true).assertDoesNotExist()
            onNodeWithText("What syncs")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        }

    @Test
    fun theExpandedTextAndLowerRowsStayReachableInAShortWindow() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("What syncs").performScrollTo().performClick()
            waitForIdle()

            onNodeWithText("the YouTube engine and update settings", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithText("Delete my data on the server").performScrollTo().assertIsDisplayed()
        }

    private fun ComposeUiTest.setScreen(harness: Harness) {
        setContent {
            val rootScope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalAppNavigator provides harness.navigator,
                LocalSnackbarHost provides harness.snackbar,
                LocalRootScope provides rootScope,
                LocalPlatformActions provides harness.actions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SyncSettingsScreen(harness.controller, harness.address, RUN_SERVER_URL)
                }
            }
        }
        waitForIdle()
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    private class Harness(
        status: SyncStatus = LINKED,
    ) {
        val controller = FakeSyncController(status)
        val address =
            FakeSyncServerAddress(
                serverUrl = LINKED.serverUrl,
                deviceName = "Anna's Pixel",
            )
        val navigator = RecordingAppNavigator()
        val snackbar = SnackbarHostState()

        val actions =
            object : PlatformActions {
                override val urls = ExternalUrlOpener { OpenResult.OPENED }
                override val share = null
                override val files =
                    object : FilePicker {
                        override suspend fun pickFile(
                            mimeTypes: List<String>,
                            extensions: List<String>,
                        ): String? = null

                        override suspend fun pickFolder(title: String): String? = null
                    }
                override val saver = FileSaver { _, _ -> null }
                override val reveal = null
                override val notifications = null
            }
    }

    private companion object {
        const val RUN_SERVER_URL = "https://repo.example/neutrodyne#run-the-server"

        val LINKED =
            SyncStatus.Linked(
                serverUrl = "https://sync.example.org",
                accountId = "acc",
                deviceId = "dev-a",
                username = "anna",
                nodeId = "node",
                lastSyncAtMs = null,
                activity = SyncActivity.IDLE,
                problem = null,
            )

        val OTHER_ACCOUNT =
            LINKED.copy(serverUrl = "https://other.example.org", accountId = "other-acc", deviceId = "other-dev")
    }
}
