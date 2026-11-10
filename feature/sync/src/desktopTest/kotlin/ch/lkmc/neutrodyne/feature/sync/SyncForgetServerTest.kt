// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
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
import ch.lkmc.neutrodyne.sync.api.ForgetServerError
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Forget server" on the `Reconnect` and `Revoked` cards (08 Settings › Sync): the local-only
 * clear exists only while disconnected, calls `SyncController.forgetServer` once per click and
 * disables itself in flight; a typed failure keeps the stored profile and the actual status and
 * surfaces an actionable error with a Retry that re-sends the same call. A completion arriving
 * after its status identity died — or after the screen left — touches no UI state and reports
 * no success it did not cause.
 */
@OptIn(ExperimentalTestApi::class)
class SyncForgetServerTest {
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
    fun forgetServerIsOfferedOnlyWhileDisconnected() =
        runComposeUiTest {
            val harness = Harness(SyncStatus.NotConfigured)
            setScreen(harness)
            onNodeWithText("Forget server").assertDoesNotExist()

            harness.controller.fake.status.value = LINKED
            waitForIdle()
            onNodeWithText("Forget server").assertDoesNotExist()

            harness.controller.fake.status.value =
                SyncStatus.LinkUnfinished("https://sync.example.org")
            waitForIdle()
            onNodeWithText("Forget server").assertDoesNotExist()

            harness.controller.fake.status.value = RECONNECT
            waitForIdle()
            onNodeWithText("Forget server").performScrollTo().assertIsDisplayed()

            harness.controller.fake.status.value = REVOKED
            waitForIdle()
            onNodeWithText("Forget server").performScrollTo().assertIsDisplayed()
            assertTrue(
                harness.controller.fake.serverForgets
                    .isEmpty(),
            )
        }

    @Test
    fun reconnectShowsTheSpecCopyAndBothActions() =
        runComposeUiTest {
            val harness = Harness(RECONNECT)
            setScreen(harness)

            onNodeWithText("Reconnect to sync.example.org")
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithText(
                "This copy of Neutrodyne isn't linked yet. " +
                    "Nothing has been synced since it was restored or copied.",
            ).performScrollTo().assertIsDisplayed()
            onNodeWithText("Reconnect").performScrollTo().assertIsDisplayed()
            onNodeWithText("Forget server").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun aForgetCallsTheControllerOnceAndFollowsTheStatus() =
        runComposeUiTest {
            val harness = Harness(RECONNECT)
            setScreen(harness)

            onNodeWithText("Forget server").performScrollTo().performClick()
            waitForIdle()

            assertEquals(1, harness.controller.fake.serverForgets.size)
            assertTrue(harness.navigator.calls.isEmpty(), "forgetting navigates nowhere")
            assertTrue(
                harness.controller.fake.startLinks
                    .isEmpty(),
                "forgetting starts no link",
            )

            // The backend's refresh is what flips the card to NotConfigured; a success paints
            // nothing of its own.
            harness.controller.fake.status.value = SyncStatus.NotConfigured
            waitForIdle()
            onNodeWithText("Forget server").assertDoesNotExist()
        }

    @Test
    fun revokedForgetsTheSameWay() =
        runComposeUiTest {
            val harness = Harness(REVOKED)
            setScreen(harness)

            onNodeWithText("Link again").assertIsDisplayed()
            onNodeWithText("Forget server").performScrollTo().performClick()
            waitForIdle()

            assertEquals(1, harness.controller.fake.serverForgets.size)
        }

    @Test
    fun aPendingForgetDisablesDuplicateClicks() =
        runComposeUiTest {
            val harness = Harness(RECONNECT)
            val gate = CompletableDeferred<Unit>()
            harness.controller.forgetGate = gate
            setScreen(harness)

            onNodeWithText("Forget server").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Forget server").assertIsNotEnabled()

            gate.complete(Unit)
            waitForIdle()

            assertEquals(1, harness.controller.fake.serverForgets.size)
            onNodeWithText("Forget server").assertIsEnabled()
        }

    @Test
    fun aStaleLinkShowsAnActionableErrorAndRetries() =
        runComposeUiTest {
            val harness = Harness(RECONNECT)
            harness.controller.fake.forgetResult = Outcome.Failure(ForgetServerError.StaleLink)
            setScreen(harness)

            onNodeWithText("Forget server").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Nothing was cleared", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            // The stored profile and the actual status survive the refused action.
            onNodeWithText("Reconnect to sync.example.org").performScrollTo().assertIsDisplayed()

            harness.controller.fake.forgetResult = Outcome.Success(Unit)
            onNodeWithText("Retry").performScrollTo().performClick()
            waitForIdle()

            assertEquals(2, harness.controller.fake.serverForgets.size)
            onNodeWithText("Nothing was cleared", substring = true).assertDoesNotExist()
        }

    @Test
    fun aWriteFailureKeepsTheProfileAndRetries() =
        runComposeUiTest {
            val harness = Harness(RECONNECT)
            harness.controller.fake.forgetResult = Outcome.Failure(ForgetServerError.WriteFailed)
            setScreen(harness)

            onNodeWithText("Forget server").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("saved sync data is unchanged", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            // A failed write never reports NotConfigured.
            onNodeWithText("Reconnect to sync.example.org").performScrollTo().assertIsDisplayed()

            harness.controller.fake.forgetResult = Outcome.Success(Unit)
            onNodeWithText("Retry").performScrollTo().performClick()
            waitForIdle()

            assertEquals(2, harness.controller.fake.serverForgets.size)
            onNodeWithText("saved sync data is unchanged", substring = true).assertDoesNotExist()
        }

    @Test
    fun anUnexpectedThrowBecomesAnActionableError() =
        runComposeUiTest {
            val harness = Harness(RECONNECT)
            harness.controller.forgetGate =
                CompletableDeferred<Unit>().apply {
                    completeExceptionally(IllegalStateException("boom"))
                }
            setScreen(harness)

            onNodeWithText("Forget server").performScrollTo().performClick()
            waitForIdle()

            onNodeWithText(
                "Something went wrong while forgetting the server",
                substring = true,
            ).performScrollTo().assertIsDisplayed()
            assertTrue(
                harness.controller.fake.serverForgets
                    .isEmpty(),
                "the throw reached the UI before the call",
            )

            harness.controller.forgetGate = null
            onNodeWithText("Retry").performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, harness.controller.fake.serverForgets.size)
        }

    @Test
    fun aLateCompletionDropsWhenTheIdentityChanged() =
        runComposeUiTest {
            val harness = Harness(RECONNECT)
            setScreen(harness)

            val gate = CompletableDeferred<Unit>()
            harness.controller.forgetGate = gate
            onNodeWithText("Forget server").performScrollTo().performClick()
            waitForIdle()

            // The admitted call is in flight when the stored profile turns into a live link.
            harness.controller.fake.status.value = LINKED
            harness.controller.fake.forgetResult = Outcome.Failure(ForgetServerError.WriteFailed)
            gate.complete(Unit)
            waitForIdle()

            // The call was admitted while valid; its completion is owned by the dead identity
            // and paints no error on the linked screen.
            assertEquals(1, harness.controller.fake.serverForgets.size)
            onNodeWithText("saved sync data is unchanged", substring = true).assertDoesNotExist()
            onNodeWithText("Forget server").assertDoesNotExist()
        }

    @Test
    fun leavingTheScreenCancelsThePendingAwait() =
        runComposeUiTest {
            val harness = Harness(RECONNECT)
            var show by mutableStateOf(true)
            setScreen(harness) { show }

            val gate = CompletableDeferred<Unit>()
            harness.controller.forgetGate = gate
            onNodeWithText("Forget server").performScrollTo().performClick()
            waitForIdle()

            runOnIdle { show = false }
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()

            // Leaving cancelled the await: the late gate completion finds no listener, the call
            // never ran and nothing is painted or reported.
            assertTrue(
                harness.controller.fake.serverForgets
                    .isEmpty(),
            )
        }

    @Test
    fun theActionStaysReachableInAShortWindow() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            val harness = Harness(REVOKED)
            setScreen(harness)

            onNodeWithText("Forget server").performScrollTo().performClick()
            waitForIdle()

            assertEquals(1, harness.controller.fake.serverForgets.size)
        }

    private fun ComposeUiTest.setScreen(
        harness: Harness,
        show: () -> Boolean = { true },
    ) {
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
                    if (show()) {
                        SyncSettingsScreen(harness.controller, harness.address, RUN_SERVER_URL)
                    }
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
        status: SyncStatus,
    ) {
        val controller = GatedSyncController(FakeSyncController(status))
        val address = FakeSyncServerAddress(serverUrl = "https://sync.example.org")
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

        val RECONNECT = SyncStatus.Reconnect("https://sync.example.org", username = "anna")
        val REVOKED = SyncStatus.Revoked("https://sync.example.org")

        val LINKED =
            SyncStatus.Linked(
                serverUrl = "https://sync.example.org",
                accountId = "acc",
                deviceId = "dev",
                username = "anna",
                nodeId = "node",
                lastSyncAtMs = null,
                activity = SyncActivity.IDLE,
                problem = null,
            )
    }
}
