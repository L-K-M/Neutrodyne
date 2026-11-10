// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.root.LocalRootScope
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.sync.api.ForgetServerError
import ch.lkmc.neutrodyne.sync.api.LinkPurpose
import ch.lkmc.neutrodyne.sync.api.PendingDevice
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncDevice
import ch.lkmc.neutrodyne.sync.api.SyncDisclosure
import ch.lkmc.neutrodyne.sync.api.SyncPreferenceError
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
 * The "Link another device" sheet (08 Link another device): `approveLink` only names the pending
 * device — the code alone never links; "Link"/"Don't link" is a separate explicit
 * `confirmLink`. Covers normalisation, pending invalidation on edit, late completions after an
 * input edit or an identity change (dropped), and an `activity`/SSE update preserving valid
 * in-flight work.
 */
@OptIn(ExperimentalTestApi::class)
class SyncApproveSheetTest {
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
    fun codeAloneNeverLinksAndLookupNamesThePendingDevice() =
        runComposeUiTest {
            val harness = Harness()
            setSheet(harness)

            onNodeWithText("Anna's phone").assertDoesNotExist()
            onNode(hasSetTextAction()).performTextInput("wdjb-mjht")
            waitForIdle()
            // Typing the code only fills the field: nothing is looked up or confirmed yet.
            assertTrue(
                harness.controller.fake.approvals
                    .isEmpty(),
            )
            assertTrue(
                harness.controller.fake.confirmations
                    .isEmpty(),
            )

            onNodeWithText("Continue").performClick()
            waitForIdle()

            // Spaces, hyphens and case are ignored — the normalised code reaches the controller.
            assertEquals(listOf("WDJBMJHT"), harness.controller.fake.approvals)
            onNodeWithText("Link Anna's phone?").assertIsDisplayed()
            onNodeWithText("Android · Neutrodyne 0.9.3 · requested 5 min ago").assertIsDisplayed()
            assertTrue(
                harness.controller.fake.confirmations
                    .isEmpty(),
                "a lookup never confirms",
            )
        }

    @Test
    fun linkConfirmsTheShownDeviceAndPops() =
        runComposeUiTest {
            val harness = Harness()
            setSheet(harness)
            lookup(harness)

            onNodeWithText("Link").performClick()
            waitForIdle()

            assertEquals(listOf("WDJBMJHT" to true), harness.controller.fake.confirmations)
            assertEquals(listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Pop), harness.navigator.calls)
            assertEquals(
                "Anna's phone is linked",
                harness.snackbar.currentSnackbarData
                    ?.visuals
                    ?.message,
            )
        }

    @Test
    fun dontLinkDeclinesAndPops() =
        runComposeUiTest {
            val harness = Harness()
            setSheet(harness)
            lookup(harness)

            onNodeWithText("Don't link").performClick()
            waitForIdle()

            assertEquals(listOf("WDJBMJHT" to false), harness.controller.fake.confirmations)
            assertEquals(listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Pop), harness.navigator.calls)
            assertEquals(
                "Link declined",
                harness.snackbar.currentSnackbarData
                    ?.visuals
                    ?.message,
            )
        }

    @Test
    fun editingTheCodeDiscardsThePendingDevice() =
        runComposeUiTest {
            val harness = Harness()
            setSheet(harness)
            lookup(harness)

            onNode(hasSetTextAction()).performTextReplacement("QQQQ-QQQQ")
            waitForIdle()

            onNodeWithText("Link Anna's phone?").assertDoesNotExist()
            onNodeWithText("Link").assertDoesNotExist()
            onNodeWithText("Don't link").assertDoesNotExist()
            assertTrue(
                harness.controller.fake.confirmations
                    .isEmpty(),
            )
        }

    @Test
    fun aLateLookupAfterAnEditNeverArrives() =
        runComposeUiTest {
            val harness = Harness()
            val gate = CompletableDeferred<Unit>()
            harness.controller.approveGate = gate
            setSheet(harness)

            onNode(hasSetTextAction()).performTextInput("WDJBMJHT")
            onNodeWithText("Continue").performClick()
            waitForIdle()
            // The lookup is still in flight; editing the code invalidates the request's result.
            onNode(hasSetTextAction()).performTextReplacement("QQQQQQQQ")
            gate.complete(Unit)
            waitForIdle()

            onNodeWithText("Link Anna's phone?").assertDoesNotExist()
            assertTrue(
                harness.controller.fake.confirmations
                    .isEmpty(),
            )
        }

    @Test
    fun aLookupCompletingAfterAnIdentityChangeIsDropped() =
        runComposeUiTest {
            val harness = Harness()
            val gate = CompletableDeferred<Unit>()
            harness.controller.approveGate = gate
            setSheet(harness)
            lookup(harness)

            // Unlink/relink: the sheet's work belongs to the identity it started under.
            harness.controller.fake.status.value = OTHER_ACCOUNT
            gate.complete(Unit)
            waitForIdle()

            onNodeWithText("Link Anna's phone?").assertDoesNotExist()
            assertTrue(
                harness.controller.fake.confirmations
                    .isEmpty(),
            )
        }

    @Test
    fun aConfirmCompletingAfterAnIdentityChangeIsDropped() =
        runComposeUiTest {
            val harness = Harness()
            setSheet(harness)
            lookup(harness)

            val gate = CompletableDeferred<Unit>()
            harness.controller.confirmGate = gate
            onNodeWithText("Link").performClick()
            waitForIdle()
            harness.controller.fake.status.value = OTHER_ACCOUNT
            gate.complete(Unit)
            waitForIdle()

            assertTrue(harness.navigator.calls.isEmpty(), "no pop for the old account")
            assertNull(harness.snackbar.currentSnackbarData, "no snackbar for the old account")
        }

    @Test
    fun anActivityUpdateDoesNotCancelAValidLookup() =
        runComposeUiTest {
            val harness = Harness()
            val gate = CompletableDeferred<Unit>()
            harness.controller.approveGate = gate
            setSheet(harness)
            lookup(harness)

            // Same identity, new activity/lastSync — an SSE round must not drop in-flight work.
            harness.controller.fake.status.value =
                LINKED.copy(activity = SyncActivity.SYNCING, lastSyncAtMs = TestClock.DEFAULT_NOW)
            gate.complete(Unit)
            waitForIdle()

            onNodeWithText("Link Anna's phone?").assertIsDisplayed()
        }

    @Test
    fun aFailedLookupShowsTheProblemAndRetries() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.approveResult = Outcome.Failure(SyncProblem.InvalidCode)
            setSheet(harness)

            onNode(hasSetTextAction()).performTextInput("WDJBMJHT")
            onNodeWithText("Continue").performClick()
            waitForIdle()

            onNodeWithText("valid or has expired.", substring = true).assertIsDisplayed()

            harness.controller.fake.approveResult = Outcome.Success(PENDING)
            onNodeWithText("Continue").performClick()
            waitForIdle()

            onNodeWithText("Link Anna's phone?").assertIsDisplayed()
        }

    @Test
    fun aDevicePurposeStatesTheGrant() =
        runComposeUiTest {
            val harness = Harness()
            setSheet(harness)
            lookup(harness)

            onNodeWithText("Link Anna's phone?").assertIsDisplayed()
            onNodeWithText("Anna's phone gets your whole library.").assertIsDisplayed()
            onNodeWithText("different network", substring = true).assertDoesNotExist()
        }

    @Test
    fun aWebPurposeSaysBrowserSignInNotADeviceLink() =
        runComposeUiTest {
            val harness = Harness()
            // 10 Link codes: `web` only signs a browser in — never presented as a device grant.
            harness.controller.fake.approveResult =
                Outcome.Success(
                    PENDING.copy(
                        deviceName = "Firefox on Linux",
                        platform = "Web",
                        purpose = LinkPurpose.WEB,
                    ),
                )
            setSheet(harness)
            lookup(harness)

            onNodeWithText("Sign Firefox on Linux in?").assertIsDisplayed()
            onNodeWithText("This signs a browser in to your account.").assertIsDisplayed()
            onNodeWithText("gets your whole library", substring = true).assertDoesNotExist()
        }

    @Test
    fun aRequestFromAnotherNetworkWarns() =
        runComposeUiTest {
            val harness = Harness()
            harness.controller.fake.approveResult = Outcome.Success(PENDING.copy(sameNetwork = false))
            setSheet(harness)
            lookup(harness)

            onNodeWithText("Requested from a different network.", substring = true).assertIsDisplayed()
        }

    @Test
    fun aShortCodeKeepsLookupDisabled() =
        runComposeUiTest {
            val harness = Harness()
            setSheet(harness)

            onNode(hasSetTextAction()).performTextInput("WDJ")
            waitForIdle()

            onNodeWithText("Continue").assertIsNotEnabled()
            assertTrue(
                harness.controller.fake.approvals
                    .isEmpty(),
            )
        }

    private fun ComposeUiTest.lookup(harness: Harness) {
        onNode(hasSetTextAction()).performTextInput("wdjb-mjht")
        onNodeWithText("Continue").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.setSheet(harness: Harness) {
        setContent {
            val rootScope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalAppNavigator provides harness.navigator,
                LocalUiClock provides harness.clock,
                LocalSnackbarHost provides harness.snackbar,
                LocalRootScope provides rootScope,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SyncApproveSheet(harness.controller, "")
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

        init {
            controller.fake.approveResult = Outcome.Success(PENDING)
        }
    }

    private companion object {
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

        val OTHER_ACCOUNT =
            LINKED.copy(serverUrl = "https://other.example.org", accountId = "other-acc", deviceId = "other-dev")

        val PENDING =
            PendingDevice(
                userCode = "WDJBMJHT",
                deviceName = "Anna's phone",
                platform = "Android",
                appVersion = "0.9.3",
                requestedAtMs = TestClock.DEFAULT_NOW - 5 * 60 * 1000,
                purpose = LinkPurpose.DEVICE,
                sameNetwork = true,
            )
    }
}

/**
 * [SyncController] that delegates everything to a [FakeSyncController] but parks chosen suspend
 * calls behind gates, so tests can hold a request in flight, change the world underneath it and
 * then release the completion (09 Shared helpers: delegation over API changes for tests).
 */
internal class GatedSyncController(
    val fake: FakeSyncController,
) : SyncController by fake {
    var approveGate: CompletableDeferred<Unit>? = null
    var confirmGate: CompletableDeferred<Unit>? = null
    var devicesGate: CompletableDeferred<Unit>? = null
    var renameGate: CompletableDeferred<Unit>? = null
    var revokeGate: CompletableDeferred<Unit>? = null
    var unlinkGate: CompletableDeferred<Unit>? = null
    var forgetGate: CompletableDeferred<Unit>? = null
    var deleteServerDataGate: CompletableDeferred<Unit>? = null
    var playbackSettingsGate: CompletableDeferred<Unit>? = null
    var sharePasswordsGate: CompletableDeferred<Unit>? = null
    var disclosureGate: CompletableDeferred<Unit>? = null

    /**
     * Where successful switch writes land, mirroring `SyncControllerImpl`'s persistence through
     * `SyncServerAddress`; a sink failure becomes `SyncPreferenceError.WriteFailed`.
     */
    var preferenceSink: SyncServerAddress? = null

    override suspend fun approveLink(userCode: String): Outcome<PendingDevice, SyncProblem> {
        approveGate?.await()
        return fake.approveLink(userCode)
    }

    override suspend fun confirmLink(
        userCode: String,
        approve: Boolean,
    ): Outcome<Unit, SyncProblem> {
        confirmGate?.await()
        return fake.confirmLink(userCode, approve)
    }

    override suspend fun devices(): Outcome<List<SyncDevice>, SyncProblem> {
        devicesGate?.await()
        return fake.devices()
    }

    override suspend fun disclosure(): SyncDisclosure? {
        disclosureGate?.await()
        return fake.disclosure()
    }

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): Outcome<Unit, SyncProblem> {
        renameGate?.await()
        return fake.renameDevice(deviceId, name)
    }

    override suspend fun revokeDevice(deviceId: String): Outcome<Unit, SyncProblem> {
        revokeGate?.await()
        return fake.revokeDevice(deviceId)
    }

    override suspend fun unlink(): Outcome<Unit, SyncProblem> {
        unlinkGate?.await()
        return fake.unlink()
    }

    override suspend fun forgetServer(): Outcome<Unit, ForgetServerError> {
        forgetGate?.await()
        return fake.forgetServer()
    }

    override suspend fun deleteServerData(confirm: String): Outcome<Unit, SyncProblem> {
        deleteServerDataGate?.await()
        return fake.deleteServerData(confirm)
    }

    override suspend fun setSyncPlaybackSettings(enabled: Boolean): Outcome<Unit, SyncPreferenceError> {
        playbackSettingsGate?.await()
        fake.setSyncPlaybackSettings(enabled)
        val sink = preferenceSink ?: return Outcome.Success(Unit)
        return if (sink.setSyncPlaybackSettings(enabled) is Outcome.Failure) {
            Outcome.Failure(SyncPreferenceError.WriteFailed)
        } else {
            Outcome.Success(Unit)
        }
    }

    override suspend fun setShareFeedPasswords(enabled: Boolean): Outcome<Unit, SyncPreferenceError> {
        sharePasswordsGate?.await()
        fake.setShareFeedPasswords(enabled)
        val sink = preferenceSink ?: return Outcome.Success(Unit)
        return if (sink.setShareFeedPasswords(enabled) is Outcome.Failure) {
            Outcome.Failure(SyncPreferenceError.WriteFailed)
        } else {
            Outcome.Success(Unit)
        }
    }
}
