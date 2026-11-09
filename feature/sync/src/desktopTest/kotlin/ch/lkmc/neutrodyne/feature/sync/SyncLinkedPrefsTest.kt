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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.SettingsError
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
import ch.lkmc.neutrodyne.sync.api.SyncDevice
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The linked device's own rows on Settings › Sync (08 "Rows while linked"): the Name row edits
 * this device's name through `renameDevice` with the shared validation, and the two device-local
 * switches (`sync.sync_playback_settings`, `sync.share_feed_passwords`) show only their saved
 * values — a failed write keeps them and says so, "Share feed passwords" asks before it turns
 * on, and nothing writes on load. Typed input, dialogs and completions belong to the linked
 * (server, account, device) identity: an `activity` update never disturbs them, an unlink or
 * relink drops them.
 */
@OptIn(ExperimentalTestApi::class)
class SyncLinkedPrefsTest {
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
    fun savedValuesDriveTheSwitchesAndNameWithoutWrites() =
        runComposeUiTest {
            val harness =
                Harness(
                    sharePasswords = true,
                    syncPlayback = false,
                )
            setScreen(harness)

            onNodeWithText("Anna's Pixel").assertIsDisplayed()
            onNodeWithText("Share feed passwords").assertIsOn()
            onNodeWithText("Sync playback settings").assertIsOff()

            // Loading only reads: no setter ran and nothing was persisted.
            assertTrue(
                harness.controller.fake.playbackSettings
                    .isEmpty(),
            )
            assertTrue(
                harness.controller.fake.passwordSharing
                    .isEmpty(),
            )
            assertTrue(harness.address.writtenShareFeedPasswords.isEmpty())
            assertTrue(harness.address.writtenSyncPlaybackSettings.isEmpty())
        }

    @Test
    fun sharingPasswordsAsksAndCancellingWritesNothing() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Share feed passwords").assertIsOff().performClick()
            waitForIdle()

            onNodeWithText("Share feed passwords?").assertIsDisplayed()
            onNodeWithText("stored on sync.example.org in readable form", substring = true).assertIsDisplayed()

            onNodeWithText("Cancel").performClick()
            waitForIdle()

            assertTrue(
                harness.controller.fake.passwordSharing
                    .isEmpty(),
                "cancelling never writes",
            )
            assertTrue(harness.address.writtenShareFeedPasswords.isEmpty())
            onNodeWithText("Share feed passwords").assertIsOff()
        }

    @Test
    fun sharingPasswordsWritesOnlyAfterConfirmation() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Share feed passwords").performClick()
            onNodeWithText("Share").performClick()
            waitForIdle()

            assertEquals(listOf(true), harness.controller.fake.passwordSharing)
            assertTrue(runBlocking { harness.address.shareFeedPasswords() })
            onNodeWithText("Share feed passwords").assertIsOn()
        }

    @Test
    fun turningPasswordSharingOffNeedsNoConfirmation() =
        runComposeUiTest {
            val harness = Harness(sharePasswords = true)
            setScreen(harness)

            onNodeWithText("Share feed passwords").assertIsOn().performClick()
            waitForIdle()

            onNodeWithText("Share feed passwords?").assertDoesNotExist()
            assertEquals(listOf(false), harness.controller.fake.passwordSharing)
            onNodeWithText("Share feed passwords").assertIsOff()
        }

    @Test
    fun thePlaybackSwitchPersistsBothWays() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Sync playback settings").assertIsOn().performClick()
            waitForIdle()
            assertEquals(listOf(false), harness.controller.fake.playbackSettings)
            onNodeWithText("Sync playback settings").assertIsOff()

            onNodeWithText("Sync playback settings").performClick()
            waitForIdle()
            assertEquals(listOf(false, true), harness.controller.fake.playbackSettings)
            assertTrue(runBlocking { harness.address.syncPlaybackSettings() })
        }

    @Test
    fun aFailedSwitchWriteKeepsTheSavedStateAndRetries() =
        runComposeUiTest {
            val harness = Harness()
            harness.address.nextPreferenceError = SettingsError.WriteFailed
            setScreen(harness)

            onNodeWithText("Share feed passwords").performClick()
            onNodeWithText("Share").performClick()
            waitForIdle()

            onNodeWithText("Couldn't save the setting").assertIsDisplayed()
            onNodeWithText("Share feed passwords").assertIsOff()
            assertTrue(runBlocking { !harness.address.shareFeedPasswords() })

            onNodeWithText("Retry").performClick()
            waitForIdle()

            assertEquals(listOf(true, true), harness.controller.fake.passwordSharing)
            assertTrue(runBlocking { harness.address.shareFeedPasswords() })
            onNodeWithText("Couldn't save the setting").assertDoesNotExist()
            onNodeWithText("Share feed passwords").assertIsOn()
        }

    @Test
    fun renameWritesThisDeviceThroughTheController() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Name").performClick()
            waitForIdle()

            onNodeWithText("Rename this device").assertIsDisplayed()
            onNode(hasSetTextAction())
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText,
                        AnnotatedString("Anna's Pixel"),
                    ),
                )
            onNode(hasSetTextAction()).performTextReplacement("")
            onNodeWithText("Save").assertIsNotEnabled()
            onNode(hasSetTextAction()).performTextReplacement("x".repeat(41))
            onNodeWithText("Save").assertIsNotEnabled()

            onNode(hasSetTextAction()).performTextReplacement("  Kitchen hub  ")
            onNodeWithText("Save").performClick()
            waitForIdle()

            assertEquals(listOf("dev-a" to "Kitchen hub"), harness.controller.fake.renames)
            assertEquals(
                "Renamed to Kitchen hub",
                harness.snackbar.currentSnackbarData
                    ?.visuals
                    ?.message,
            )
        }

    @Test
    fun aFailedRenameShowsTheProblemAndRetries() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.renameResult = Outcome.Failure(SyncProblem.Unreachable("timeout"))
            setScreen(harness)

            onNodeWithText("Name").performClick()
            onNode(hasSetTextAction()).performTextReplacement("Kitchen hub")
            onNodeWithText("Save").performClick()
            waitForIdle()

            onNodeWithText("reach sync.example.org.", substring = true).assertIsDisplayed()

            harness.controller.fake.renameResult = Outcome.Success(Unit)
            onNodeWithText("Retry").performClick()
            waitForIdle()

            assertEquals(
                listOf("dev-a" to "Kitchen hub", "dev-a" to "Kitchen hub"),
                harness.controller.fake.renames,
            )
            assertEquals(
                "Renamed to Kitchen hub",
                harness.snackbar.currentSnackbarData
                    ?.visuals
                    ?.message,
            )
        }

    @Test
    fun typedInputAndDialogsSurviveStatusUpdates() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Name").performClick()
            onNode(hasSetTextAction()).performTextReplacement("Draft name")

            // Same identity, fresh activity/timestamps — live status updates must not disturb it.
            harness.controller.fake.status.value =
                LINKED.copy(activity = SyncActivity.SYNCING, lastSyncAtMs = 1234L)
            waitForIdle()

            onNode(hasSetTextAction())
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText,
                        AnnotatedString("Draft name"),
                    ),
                )

            onNodeWithText("Cancel").performClick()
            onNodeWithText("Share feed passwords").performClick()
            onNodeWithText("Share feed passwords?").assertIsDisplayed()
            harness.controller.fake.status.value =
                LINKED.copy(activity = SyncActivity.LIVE, lastSyncAtMs = 5678L)
            waitForIdle()
            onNodeWithText("Share feed passwords?").assertIsDisplayed()
        }

    @Test
    fun anOpenDialogDiesWithItsIdentity() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Share feed passwords").performClick()
            onNodeWithText("Share feed passwords?").assertIsDisplayed()

            harness.controller.fake.status.value = OTHER_ACCOUNT
            waitForIdle()

            onNodeWithText("Share feed passwords?").assertDoesNotExist()
            assertTrue(
                harness.controller.fake.passwordSharing
                    .isEmpty(),
            )
        }

    @Test
    fun aLateRenameCompletionCannotReachANewIdentity() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Name").performClick()
            onNode(hasSetTextAction()).performTextReplacement("Old account name")
            val gate = CompletableDeferred<Unit>()
            harness.controller.renameGate = gate
            onNodeWithText("Save").performClick()
            waitForIdle()

            harness.controller.fake.status.value = OTHER_ACCOUNT
            gate.complete(Unit)
            waitForIdle()

            // The issued call reached the controller, but its completion is owned by the dead
            // identity: no snackbar, no error row, no dialog on the new account's screen.
            assertEquals(listOf("dev-a" to "Old account name"), harness.controller.fake.renames)
            assertNull(harness.snackbar.currentSnackbarData)
            onNodeWithText("Renamed to Old account name").assertDoesNotExist()
            onNodeWithText("Rename this device").assertDoesNotExist()
        }

    @Test
    fun aLateSwitchCompletionCannotReachANewIdentity() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Share feed passwords").performClick()
            val gate = CompletableDeferred<Unit>()
            harness.controller.sharePasswordsGate = gate
            onNodeWithText("Share").performClick()
            waitForIdle()

            harness.controller.fake.status.value = OTHER_ACCOUNT
            gate.complete(Unit)
            waitForIdle()

            // The write itself was admitted while valid and lands in storage; the new identity's
            // fresh section re-reads it — the stale completion writes nothing to the UI.
            assertEquals(listOf(true), harness.controller.fake.passwordSharing)
            onNodeWithText("Couldn't save the setting").assertDoesNotExist()
            onNodeWithText("Share feed passwords").assertIsOn()
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
        sharePasswords: Boolean = false,
        syncPlayback: Boolean = true,
    ) {
        val controller = GatedSyncController(FakeSyncController(LINKED))
        val address =
            FakeSyncServerAddress(
                serverUrl = LINKED.serverUrl,
                deviceName = "Anna's Pixel",
            )
        val navigator = RecordingAppNavigator()
        val snackbar = SnackbarHostState()
        val openedUrls = mutableListOf<String>()

        val actions =
            object : PlatformActions {
                override val urls =
                    ExternalUrlOpener { url ->
                        openedUrls += url
                        OpenResult.OPENED
                    }
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

        init {
            // Successful switch writes land in the same port the screen reads back.
            controller.preferenceSink = address
            address.updateShareFeedPasswords(sharePasswords)
            address.updateSyncPlaybackSettings(syncPlayback)
            controller.fake.devicesResult = Outcome.Success(DEVICES)
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

        val DEVICES =
            listOf(
                SyncDevice(
                    id = "dev-a",
                    name = "Anna's Pixel",
                    platform = "android",
                    appVersion = "0.9.3",
                    lastSeenAtMs = null,
                    current = true,
                    stale = false,
                ),
                SyncDevice(
                    id = "dev-b",
                    name = "Desk",
                    platform = "windows",
                    appVersion = "0.9.3",
                    lastSeenAtMs = null,
                    current = false,
                    stale = false,
                ),
            )
    }
}
