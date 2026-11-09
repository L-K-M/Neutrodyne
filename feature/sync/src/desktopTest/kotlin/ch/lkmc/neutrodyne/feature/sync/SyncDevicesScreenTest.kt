// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
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
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
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
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Devices list (08 Devices): "This device" offers Rename only — its removal is the separate
 * Unlink flow — while the other rows offer Rename and a name-confirming Remove. Operations refresh
 * the list on success; failures show the typed `SyncProblem` text with Retry; and the screen's
 * work belongs to the (server, account, device) identity it was opened under — an `activity`/SSE
 * update never cancels it, an identity change drops every late completion.
 */
@OptIn(ExperimentalTestApi::class)
class SyncDevicesScreenTest {
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
    fun showsRowsWithCurrentLastSeenAndStaleMarkers() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Anna's phone").assertIsDisplayed()
            onNodeWithText("This device").assertIsDisplayed()
            onNodeWithText("Desk").assertIsDisplayed()
            onAllNodesWithText("Neutrodyne 0.9.3").assertCountEquals(2)
            onNodeWithText("Last seen 1 h ago").assertIsDisplayed()
            onNodeWithText("Not seen for 6 months").assertIsDisplayed()
            assertEquals(1, harness.controller.fake.deviceLists.size)
        }

    @Test
    fun theCurrentDeviceOffersRenameOnly() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Anna's phone").performClick()
            waitForIdle()

            onNodeWithText("Rename").assertIsDisplayed()
            onNodeWithText("Remove").assertDoesNotExist()
        }

    @Test
    fun removeAsksForTheDeviceNameAndRefreshesOnSuccess() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Desk").performClick()
            onNodeWithText("Remove").performClick()
            waitForIdle()

            onNodeWithText("Remove Desk?").assertIsDisplayed()
            onNodeWithText("It stops syncing. Its library stays on it.").assertIsDisplayed()

            onNodeWithText("Remove").performClick()
            waitForIdle()

            assertEquals(listOf("dev-b"), harness.controller.fake.revokes)
            // A successful operation reloads the list before the snackbar's suspension returns.
            assertEquals(2, harness.controller.fake.deviceLists.size)
            assertEquals(
                "Desk removed",
                harness.snackbar.currentSnackbarData
                    ?.visuals
                    ?.message,
            )
        }

    @Test
    fun renameTrimsTheNameAndValidatesItsLength() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Desk").performClick()
            onNodeWithText("Rename").performClick()
            waitForIdle()
            onNodeWithText("Rename Desk").assertIsDisplayed()

            onNode(hasSetTextAction()).performTextReplacement("")
            onNodeWithText("Save").assertIsNotEnabled()
            onNode(hasSetTextAction()).performTextReplacement("x".repeat(41))
            onNodeWithText("Save").assertIsNotEnabled()

            onNode(hasSetTextAction()).performTextReplacement("  Living room  ")
            onNodeWithText("Save").performClick()
            waitForIdle()

            assertEquals(listOf("dev-b" to "Living room"), harness.controller.fake.renames)
            assertEquals(2, harness.controller.fake.deviceLists.size)
            assertEquals(
                "Renamed to Living room",
                harness.snackbar.currentSnackbarData
                    ?.visuals
                    ?.message,
            )
        }

    @Test
    fun aFailedListLoadShowsTheProblemAndRetries() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.devicesResult = Outcome.Failure(SyncProblem.Unreachable("timeout"))
            setScreen(harness)

            onNodeWithText("reach sync.example.org.", substring = true).assertIsDisplayed()

            harness.controller.fake.devicesResult = Outcome.Success(DEVICES)
            onNodeWithText("Retry").performClick()
            waitForIdle()

            onNodeWithText("Desk").assertIsDisplayed()
            assertEquals(2, harness.controller.fake.deviceLists.size)
        }

    @Test
    fun aFailedOperationShowsTheProblemAndRetries() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.revokeResult = Outcome.Failure(SyncProblem.Unreachable("timeout"))
            setScreen(harness)

            onNodeWithText("Desk").performClick()
            onNodeWithText("Remove").performClick()
            onNodeWithText("Remove").performClick()
            waitForIdle()

            onNodeWithText("reach sync.example.org.", substring = true).assertIsDisplayed()

            harness.controller.fake.revokeResult = Outcome.Success(Unit)
            onNodeWithText("Retry").performClick()
            waitForIdle()

            assertEquals(listOf("dev-b", "dev-b"), harness.controller.fake.revokes)
            assertEquals(2, harness.controller.fake.deviceLists.size)
        }

    @Test
    fun anAlreadyGoneDeviceRefreshesTheList() =
        runComposeUiTest {
            val harness = Harness()
            // 10 Errors: a 404 on PATCH/DELETE /devices/{id} is the missing row — the screen
            // reloads and words it, never the wrong-server text or an op retry.
            harness.controller.fake.revokeResult = Outcome.Failure(SyncProblem.DeviceGone)
            setScreen(harness)

            onNodeWithText("Desk").performClick()
            onNodeWithText("Remove").performClick()
            onNodeWithText("Remove").performClick()
            waitForIdle()

            onNodeWithText("That device is already gone.").assertIsDisplayed()
            assertEquals(listOf("dev-b"), harness.controller.fake.revokes)
            assertEquals(2, harness.controller.fake.deviceLists.size, "the list reloads on DeviceGone")
        }

    @Test
    fun anOperationCompletingAfterAnIdentityChangeIsDropped() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            val gate = CompletableDeferred<Unit>()
            harness.controller.revokeGate = gate
            onNodeWithText("Desk").performClick()
            onNodeWithText("Remove").performClick()
            onNodeWithText("Remove").performClick()
            waitForIdle()

            harness.controller.fake.status.value = OTHER_ACCOUNT
            gate.complete(Unit)
            waitForIdle()

            // The old body's list, snackbar and op state died with the identity that owned them;
            // the new body simply loads its own account's devices.
            assertNull(harness.snackbar.currentSnackbarData)
            assertEquals(listOf("dev-b"), harness.controller.fake.revokes)
            assertTrue(harness.controller.fake.deviceLists.size >= 2)
        }

    @Test
    fun anActivityUpdateDoesNotCancelAValidOperation() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            val gate = CompletableDeferred<Unit>()
            harness.controller.revokeGate = gate
            onNodeWithText("Desk").performClick()
            onNodeWithText("Remove").performClick()
            onNodeWithText("Remove").performClick()
            waitForIdle()

            // Same identity, new activity/lastSync — an SSE round must not drop the operation.
            harness.controller.fake.status.value =
                LINKED.copy(activity = SyncActivity.SYNCING, lastSyncAtMs = TestClock.DEFAULT_NOW)
            gate.complete(Unit)
            waitForIdle()

            assertEquals(2, harness.controller.fake.deviceLists.size)
            assertEquals(
                "Desk removed",
                harness.snackbar.currentSnackbarData
                    ?.visuals
                    ?.message,
            )
        }

    private fun ComposeUiTest.setScreen(harness: Harness) {
        setContent {
            val rootScope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalAppNavigator provides harness.navigator,
                LocalUiClock provides harness.clock,
                LocalSnackbarHost provides harness.snackbar,
                LocalRootScope provides rootScope,
                LocalPlatformActions provides harness.actions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SyncDevicesScreen(harness.controller)
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

    private class Harness {
        val controller = GatedSyncController(FakeSyncController(LINKED))
        val navigator = RecordingAppNavigator()
        val clock = TestClock()
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
            controller.fake.devicesResult = Outcome.Success(DEVICES)
        }
    }

    private companion object {
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
                    name = "Anna's phone",
                    platform = "android",
                    appVersion = "0.9.3",
                    lastSeenAtMs = TestClock.DEFAULT_NOW - 10 * 60 * 1000,
                    current = true,
                    stale = false,
                ),
                SyncDevice(
                    id = "dev-b",
                    name = "Desk",
                    platform = "windows",
                    appVersion = "0.9.3",
                    lastSeenAtMs = TestClock.DEFAULT_NOW - 60 * 60 * 1000,
                    current = false,
                    stale = false,
                ),
                SyncDevice(
                    id = "dev-c",
                    name = "Old tablet",
                    platform = "linux",
                    appVersion = null,
                    lastSeenAtMs = null,
                    current = false,
                    stale = true,
                ),
            )
    }
}
