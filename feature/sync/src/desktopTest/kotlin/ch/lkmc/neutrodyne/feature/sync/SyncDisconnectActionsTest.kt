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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
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
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Unlink this device" and "Delete my data on the server" on Settings › Sync (08 "Rows while
 * linked", 10 Unlink and Delete my data): both live only under `Linked`, each behind its own
 * confirmation — Unlink's dialog, the exact untranslated `DELETE` for the purge — and a
 * confirm is the only path that calls the controller. Everything async belongs to the linked
 * (server, account, device) identity: an `activity` update keeps typed input, an unlink or
 * relink drops open dialogs, gates a completion out of the new identity's screen.
 */
@OptIn(ExperimentalTestApi::class)
class SyncDisconnectActionsTest {
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
    fun theRowsOnlyExistWhileLinked() =
        runComposeUiTest {
            val harness = Harness(SyncStatus.NotConfigured)
            setScreen(harness)

            onNodeWithText("Unlink this device").assertDoesNotExist()
            onNodeWithText("Delete my data on the server").assertDoesNotExist()

            harness.controller.fake.status.value = LINKED
            waitForIdle()

            onNodeWithText("Unlink this device").performScrollTo().assertIsDisplayed()
            onNodeWithText("Delete my data on the server").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun cancellingUnlinkWritesNothing() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Unlink this device").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Unlink this device?").assertIsDisplayed()
            onNodeWithText(
                "stops syncing; its library stays as it is, and your other devices keep syncing",
                substring = true,
            ).assertIsDisplayed()

            onNodeWithText("Cancel").performClick()
            waitForIdle()

            assertTrue(
                harness.controller.fake.unlinks
                    .isEmpty(),
                "cancelling never calls",
            )
        }

    @Test
    fun confirmingUnlinkCallsTheController() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Unlink this device").performScrollTo().performClick()
            onNodeWithText("Unlink").performClick()
            waitForIdle()

            assertEquals(1, harness.controller.fake.unlinks.size)
        }

    @Test
    fun aFailedUnlinkShowsTheProblemAndRetries() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.unlinkResult = Outcome.Failure(SyncProblem.Unreachable("timeout"))
            setScreen(harness)

            onNodeWithText("Unlink this device").performScrollTo().performClick()
            onNodeWithText("Unlink").performClick()
            waitForIdle()

            onNodeWithText("reach sync.example.org.", substring = true).performScrollTo().assertIsDisplayed()

            harness.controller.fake.unlinkResult = Outcome.Success(Unit)
            onNodeWithText("Retry").performScrollTo().performClick()
            waitForIdle()

            assertEquals(2, harness.controller.fake.unlinks.size)
            onNodeWithText("reach sync.example.org.", substring = true).assertDoesNotExist()
        }

    @Test
    fun cancellingDeleteWritesNothing() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Delete your data on sync.example.org?").assertIsDisplayed()
            onNode(hasSetTextAction()).performTextReplacement("DELETE")

            onNodeWithText("Cancel").performClick()
            waitForIdle()

            assertTrue(
                harness.controller.fake.serverDataDeletes
                    .isEmpty(),
                "cancelling never calls",
            )
        }

    @Test
    fun deleteShowsTheHostAndFullDisclosure() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            waitForIdle()

            onNodeWithText("Delete your data on sync.example.org?").assertIsDisplayed()
            onNodeWithText("subscriptions, groups, listening history, Up next and synced settings", substring = true)
                .assertIsDisplayed()
            onNodeWithText("backups of your account", substring = true).assertIsDisplayed()
            onNodeWithText("unlinks all your devices", substring = true).assertIsDisplayed()
            onNodeWithText("Every device keeps its own library", substring = true).assertIsDisplayed()
            onNodeWithText("nightly backups keep a copy for up to 7 days", substring = true).assertIsDisplayed()
            onNodeWithText("Type DELETE to confirm").assertIsDisplayed()
        }

    @Test
    fun deleteConfirmationRemainsReachableInAShortWindow() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            waitForIdle()
            onNode(hasSetTextAction()).assertIsNotDisplayed()
            onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed().performTextReplacement("DELETE")
            deleteConfirm().assertIsEnabled().performClick()
            waitForIdle()

            assertEquals(listOf("DELETE"), harness.controller.fake.serverDataDeletes)
        }

    @Test
    fun theDeleteButtonNeedsExactlyDelete() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            waitForIdle()
            deleteConfirm().assertIsNotEnabled()

            for (rejected in listOf("delete", "Delete", " DELETE", "DELETE ", "DELET", "DΕLETE", "ＤＥＬＥＴＥ")) {
                onNode(hasSetTextAction()).performTextReplacement(rejected)
                waitForIdle()
                deleteConfirm().assertIsNotEnabled()
            }

            onNode(hasSetTextAction()).performTextReplacement("DELETE")
            waitForIdle()
            deleteConfirm().assertIsEnabled().performClick()
            waitForIdle()

            assertEquals(listOf("DELETE"), harness.controller.fake.serverDataDeletes)
        }

    @Test
    fun aFailedDeleteKeepsTheLinkAndRetriesTheConfirmedOp() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.deleteServerDataResult =
                Outcome.Failure(SyncProblem.Server(503))
            setScreen(harness)

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            onNode(hasSetTextAction()).performTextReplacement("DELETE")
            deleteConfirm().performClick()
            waitForIdle()

            onNodeWithText("sync.example.org reported an error (503)", substring = true)
                .performScrollTo()
                .assertIsDisplayed()

            harness.controller.fake.deleteServerDataResult = Outcome.Success(Unit)
            onNodeWithText("Retry").performScrollTo().performClick()
            waitForIdle()

            // The retry re-sends the already-confirmed op; the user is not asked to type again.
            assertEquals(listOf("DELETE", "DELETE"), harness.controller.fake.serverDataDeletes)
            onNodeWithText("reported an error", substring = true).assertDoesNotExist()
        }

    @Test
    fun aBusyUnlinkCannotBeSubmittedTwice() =
        runComposeUiTest {
            val harness = Harness()
            val gate = CompletableDeferred<Unit>()
            harness.controller.unlinkGate = gate
            setScreen(harness)

            onNodeWithText("Unlink this device").performScrollTo().performClick()
            onNodeWithText("Unlink").performClick()
            waitForIdle()

            // The first call is in flight; a second confirm is swallowed by the busy guard.
            onNodeWithText("Unlink this device").performScrollTo().performClick()
            onNodeWithText("Unlink").performClick()
            gate.complete(Unit)
            waitForIdle()

            assertEquals(1, harness.controller.fake.unlinks.size)
        }

    @Test
    fun aBusyDeleteDisablesASecondConfirm() =
        runComposeUiTest {
            val harness = Harness()
            val gate = CompletableDeferred<Unit>()
            harness.controller.deleteServerDataGate = gate
            setScreen(harness)

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            onNode(hasSetTextAction()).performTextReplacement("DELETE")
            deleteConfirm().performClick()
            waitForIdle()

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            onNode(hasSetTextAction()).performTextReplacement("DELETE")
            waitForIdle()
            deleteConfirm().assertIsNotEnabled()

            gate.complete(Unit)
            waitForIdle()
            assertEquals(listOf("DELETE"), harness.controller.fake.serverDataDeletes)
        }

    @Test
    fun typedConfirmationSurvivesStatusUpdates() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            onNode(hasSetTextAction()).performTextReplacement("DEL")

            // Same identity, fresh activity/timestamps — live status updates keep the input.
            harness.controller.fake.status.value =
                LINKED.copy(activity = SyncActivity.SYNCING, lastSyncAtMs = 1234L)
            waitForIdle()

            onNode(hasSetTextAction())
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText,
                        AnnotatedString("DEL"),
                    ),
                )
            deleteConfirm().assertIsNotEnabled()
        }

    @Test
    fun anOpenDialogDiesWithItsIdentity() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            onNode(hasSetTextAction()).performTextReplacement("DELETE")
            onNodeWithText("Delete your data on sync.example.org?").assertIsDisplayed()

            harness.controller.fake.status.value = OTHER_ACCOUNT
            waitForIdle()

            onNodeWithText("Delete your data on sync.example.org?").assertDoesNotExist()
            onNodeWithText("Delete your data on other.example.org?").assertDoesNotExist()

            // The new identity's section is fresh: opening the dialog starts with an empty field.
            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Delete your data on other.example.org?").assertIsDisplayed()
            onNode(hasSetTextAction())
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText,
                        AnnotatedString(""),
                    ),
                )
            deleteConfirm().assertIsNotEnabled()
            onNodeWithText("Cancel").performClick()
            assertTrue(
                harness.controller.fake.serverDataDeletes
                    .isEmpty(),
            )
        }

    @Test
    fun aLateDeleteFailureCannotReachANewIdentity() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.deleteServerDataResult =
                Outcome.Failure(SyncProblem.Unreachable("timeout"))
            setScreen(harness)

            onNodeWithText("Delete my data on the server").performScrollTo().performClick()
            onNode(hasSetTextAction()).performTextReplacement("DELETE")
            val gate = CompletableDeferred<Unit>()
            harness.controller.deleteServerDataGate = gate
            deleteConfirm().performClick()
            waitForIdle()

            harness.controller.fake.status.value = OTHER_ACCOUNT
            gate.complete(Unit)
            waitForIdle()

            // The call was admitted while valid; its completion is owned by the dead identity
            // and paints no error on the new account's screen.
            assertEquals(listOf("DELETE"), harness.controller.fake.serverDataDeletes)
            onNodeWithText("reach other.example.org.", substring = true).assertDoesNotExist()
            onNodeWithText("reach sync.example.org.", substring = true).assertDoesNotExist()
        }

    @Test
    fun aLateUnlinkFailureCannotReachANewIdentity() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.unlinkResult = Outcome.Failure(SyncProblem.Unreachable("timeout"))
            setScreen(harness)

            onNodeWithText("Unlink this device").performScrollTo().performClick()
            val gate = CompletableDeferred<Unit>()
            harness.controller.unlinkGate = gate
            onNodeWithText("Unlink").performClick()
            waitForIdle()

            harness.controller.fake.status.value = OTHER_ACCOUNT
            gate.complete(Unit)
            waitForIdle()

            assertEquals(1, harness.controller.fake.unlinks.size)
            onNodeWithText("reach other.example.org.", substring = true).assertDoesNotExist()
            onNodeWithText("reach sync.example.org.", substring = true).assertDoesNotExist()
        }

    /** The dialog's confirm: excludes the field, whose editable text can itself be "Delete". */
    private fun ComposeUiTest.deleteConfirm() = onNode(hasText("Delete") and !hasSetTextAction())

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
        val controller = GatedSyncController(FakeSyncController(status))
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
