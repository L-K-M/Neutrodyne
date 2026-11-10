// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
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
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsError
import ch.lkmc.neutrodyne.sync.api.SyncDiagnosticsSnapshot
import ch.lkmc.neutrodyne.sync.api.SyncLanGateState
import ch.lkmc.neutrodyne.sync.api.SyncPendingCollection
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundFailure
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import ch.lkmc.neutrodyne.sync.api.SyncStreamState
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sync diagnostics screen (08 Sync diagnostics): a populated snapshot shows 10's lines —
 * unknowns stay unknown, the LAN-gate line is Android's — the last twenty rounds scroll, "Copy"
 * writes the plain text to the local clipboard on tap only, `UNAVAILABLE` is a retryable failure
 * rather than a zero-count success, and a profile switch discards a blocked read's result and any
 * stale copy.
 */
@OptIn(ExperimentalTestApi::class)
class SyncDiagnosticsScreenTest {
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
    fun aPopulatedSnapshotShowsItsLines() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            onNodeWithText("Server version: 1.4.2").assertIsDisplayed()
            onNodeWithText("Protocol: 3").assertIsDisplayed()
            onNodeWithText("Account: anna").assertIsDisplayed()
            onNodeWithText("This device: Anna's Pixel").assertIsDisplayed()
            onNodeWithText("Last sync: 2 min ago").assertIsDisplayed()
            onNodeWithText("Clock offset: -45 ms").assertIsDisplayed()
            onNodeWithText("Live connection: CONNECTED").assertIsDisplayed()
            onNodeWithText("Reconnects: 2").assertIsDisplayed()
            onNodeWithText("Last error: clock_skew").assertIsDisplayed()
            onNodeWithText("Cursor: abcd1234").assertIsDisplayed()
            onNodeWithText("Parked changes: 1").assertIsDisplayed()
            onNodeWithText("Held changes: 2").assertIsDisplayed()
            onNodeWithText("Podcasts: 2").assertIsDisplayed()
            onNodeWithText("Other: 3").assertIsDisplayed()
            onNodeWithText("Last round: SSE", substring = true).assertIsDisplayed()
            onNodeWithText("SYNC_NOW", substring = true).assertIsDisplayed()
            // The local-network gate is Android's line; the desktop build does not render it.
            onNodeWithText("Local network", substring = true).assertDoesNotExist()
            assertEquals(1, harness.controller.fake.diagnosticsReads.size)
        }

    @Test
    fun theLanGateLineAppearsOnAndroid() =
        runComposeUiTest {
            val harness = Harness(androidSdk = 37)
            setScreen(harness)

            onNodeWithText("Local network: GRANTED").assertIsDisplayed()
        }

    @Test
    fun unknownFieldsStayUnknownAndEmptyHistoryIsWorded() =
        runComposeUiTest {
            val harness = Harness(Outcome.Success(SyncDiagnosticsSnapshot()))
            setScreen(harness)

            onNodeWithText("Server version: Unknown").assertIsDisplayed()
            onNodeWithText("Account: Unknown").assertIsDisplayed()
            onNodeWithText("This device: Unknown").assertIsDisplayed()
            onNodeWithText("Last error: Unknown").assertIsDisplayed()
            onNodeWithText("Cursor: Unknown").assertIsDisplayed()
            onNodeWithText("No rounds recorded yet").assertIsDisplayed()
            // An empty snapshot is still real data: the canonical counts are honest zeros.
            onNodeWithText("Podcasts: 0").assertIsDisplayed()
            onNodeWithText("Other: 0").assertIsDisplayed()
        }

    @Test
    fun twentyRoundsReachTheBottomOfAShortWindow() =
        runComposeUiTest {
            val harness = Harness(Outcome.Success(HISTORY))
            setScreen(harness, shortWindow = true)

            // The scrollable column must bring the twentieth round on screen; the "Last
            // round:" summary shares its text, so the scroll target is the line itself.
            onNode(hasScrollAction()).performScrollToNode(textStartsWith("FIRST_LINK ·"))
            onNode(textStartsWith("FIRST_LINK ·")).assertIsDisplayed()
        }

    @Test
    fun copyWritesThePlainTextOnlyOnTap() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)

            assertNull(harness.clipboard.copied, "nothing is copied until the user taps")
            onNodeWithText("Copy").performClick()
            waitForIdle()

            val text = harness.clipboard.copied?.text ?: error("the tap must copy the report")
            assertTrue("serverVersion: 1.4.2" in text)
            assertTrue("cursorPrefix: abcd1234" in text)
            assertTrue("pending.other: 3" in text)
            assertFalse("://" in text, "no server or feed URL may be copied")
            assertFalse("token" in text.lowercase())
            assertFalse("password" in text.lowercase())
            waitUntil("the copied snackbar shows", TIMEOUT_MS) {
                harness.snackbar.currentSnackbarData != null
            }
            assertEquals(
                "Diagnostics copied",
                harness.snackbar.currentSnackbarData
                    ?.visuals
                    ?.message,
            )
        }

    @Test
    fun copyCarriesAllTwentyRounds() =
        runComposeUiTest {
            val harness = Harness(Outcome.Success(HISTORY))
            setScreen(harness)

            onNodeWithText("Copy").performClick()
            waitForIdle()

            val text = harness.clipboard.copied?.text ?: error("the tap must copy the report")
            assertTrue("round.19:" in text)
        }

    @Test
    fun unavailableShowsAnActionableFailureRatherThanZeros() =
        runComposeUiTest {
            val harness = Harness(Outcome.Failure(SyncDiagnosticsError.UNAVAILABLE))
            setScreen(harness)

            onNodeWithText("Sync diagnostics could not be loaded. Try again.").assertIsDisplayed()
            onNodeWithText("Podcasts: 0").assertDoesNotExist()
            onNodeWithText("Copy").assertIsNotEnabled()

            harness.controller.fake.diagnosticsResult = Outcome.Success(FULL)
            onNodeWithText("Retry").performClick()
            waitUntil("the retry's snapshot lands", TIMEOUT_MS) {
                harness.vm.uiState.value.snapshot == FULL
            }
            waitForIdle()

            onNodeWithText("Server version: 1.4.2").assertIsDisplayed()
        }

    @Test
    fun aProfileSwitchDiscardsABlockedReadAndItsStaleCopy() =
        runComposeUiTest {
            val harness = Harness()
            setScreen(harness)
            onNodeWithText("Account: anna").assertIsDisplayed()

            val gate = CompletableDeferred<Unit>()
            harness.controller.diagnosticsGate = gate
            harness.controller.fake.status.value = OTHER_ACCOUNT
            // `viewModelScope` runs on the real Main thread — wait for its collect to cancel
            // and replace the read behind the gate.
            waitUntil("the switch replaced the read", TIMEOUT_MS) {
                harness.controller.diagnosticsAttempts.size == 2
            }
            waitForIdle()

            // The old profile's lines leave the screen while the new read is in flight.
            onNodeWithText("Account: anna").assertDoesNotExist()
            onNodeWithText("Copy").assertIsNotEnabled()

            val other = FULL.copy(accountName = "bert", deviceName = "Bert's Mac")
            harness.controller.fake.diagnosticsResult = Outcome.Success(other)
            gate.complete(Unit)
            waitUntil("the new profile's read lands", TIMEOUT_MS) {
                harness.vm.uiState.value.snapshot
                    ?.accountName == "bert"
            }
            waitForIdle()

            onNodeWithText("Account: bert").assertIsDisplayed()
            onNodeWithText("Copy").performClick()
            waitForIdle()
            val text = harness.clipboard.copied?.text ?: error("the tap must copy the report")
            assertTrue("accountName: bert" in text)
            assertFalse("accountName: anna" in text, "the stale profile must not be copied")
        }

    @Test
    fun refreshReloadsWithoutOverlapping() =
        runComposeUiTest {
            val gate = CompletableDeferred<Unit>()
            val harness = Harness(diagnosticsGate = gate)
            setScreen(harness)

            onNode(hasContentDescription("Refresh")).performClick()
            waitUntil("the refresh replaced the parked read", TIMEOUT_MS) {
                harness.controller.diagnosticsAttempts.size == 2
            }
            gate.complete(Unit)
            waitForIdle()

            onNodeWithText("Server version: 1.4.2").assertIsDisplayed()
            assertEquals(1, harness.controller.fake.diagnosticsReads.size)
        }

    /** Matches a node whose text starts with [prefix] — "Last round: …" duplicates don't. */
    private fun textStartsWith(prefix: String) =
        SemanticsMatcher("text starts with '$prefix'") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true
        }

    private fun ComposeUiTest.setScreen(
        harness: Harness,
        shortWindow: Boolean = false,
    ) {
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
                LocalClipboardManager provides harness.clipboard,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    if (shortWindow) {
                        Box(Modifier.size(360.dp, 300.dp)) { SyncDiagnosticsScreen(vm = harness.vm) }
                    } else {
                        SyncDiagnosticsScreen(vm = harness.vm)
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

    /**
     * `Dispatchers.Main.immediate` runs eagerly inside `runComposeUiTest`, so the ViewModel's
     * first read completes during construction: [result] and [diagnosticsGate] must be in place
     * before `vm` is built.
     */
    private class Harness(
        result: Outcome<SyncDiagnosticsSnapshot, SyncDiagnosticsError> = Outcome.Success(FULL),
        androidSdk: Int? = null,
        diagnosticsGate: CompletableDeferred<Unit>? = null,
    ) {
        val controller = GatedSyncController(FakeSyncController(LINKED))

        init {
            controller.fake.diagnosticsResult = result
            controller.diagnosticsGate = diagnosticsGate
        }

        val vm = SyncDiagnosticsViewModel(controller, TestPlatformInfo(androidSdk))
        val navigator = RecordingAppNavigator()
        val clock = TestClock()
        val snackbar = SnackbarHostState()
        val clipboard = RecordingClipboard()
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

    private class TestPlatformInfo(
        override val androidSdkInt: Int? = null,
    ) : PlatformInfo {
        override val kind: PlatformKind =
            if (androidSdkInt == null) PlatformKind.DESKTOP else PlatformKind.ANDROID
        override val userAgentPlatform: String = "Test"
        override val regionCode: String = "DE"
    }

    /** The copy sink — the real desktop clipboard is the OS's, so test-local. */
    @Suppress("DEPRECATION")
    private class RecordingClipboard : ClipboardManager {
        var copied: AnnotatedString? = null

        override fun getText(): AnnotatedString? = copied

        override fun setText(annotatedString: AnnotatedString) {
            copied = annotatedString
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        val LINKED =
            SyncStatus.Linked(
                serverUrl = "https://sync.example.org",
                accountId = "acc-a",
                deviceId = "dev-a",
                username = "anna",
                nodeId = "node",
                lastSyncAtMs = null,
                activity = SyncActivity.IDLE,
                problem = null,
            )

        val OTHER_ACCOUNT =
            LINKED.copy(serverUrl = "https://other.example.org", accountId = "acc-b", deviceId = "dev-b")

        val FULL =
            SyncDiagnosticsSnapshot(
                serverVersion = "1.4.2",
                protocol = 3,
                accountName = "anna",
                deviceName = "Anna's Pixel",
                lastSyncAtMs = TestClock.DEFAULT_NOW - 2 * 60_000,
                rounds =
                    listOf(
                        SyncRoundSummary(
                            SyncRoundReason.SYNC_NOW,
                            TestClock.DEFAULT_NOW - 60_000,
                            SyncReport(
                                pushed = 1,
                                applied = 2,
                                stale = 0,
                                rejected = 0,
                                pulled = 3,
                                held = 0,
                                durationMs = 40,
                            ),
                        ),
                        SyncRoundSummary(
                            SyncRoundReason.SSE,
                            TestClock.DEFAULT_NOW - 30_000,
                            null,
                            SyncRoundFailure.TRANSPORT,
                            503,
                            "unavailable",
                        ),
                    ),
                pendingByCollection = mapOf(SyncPendingCollection.PODCAST to 2, SyncPendingCollection.OTHER to 3),
                parkedCount = 1,
                heldCount = 2,
                clockOffsetMs = -45,
                streamState = SyncStreamState.CONNECTED,
                reconnectCount = 2,
                lanGate = SyncLanGateState.GRANTED,
                lastErrorCode = "clock_skew",
                cursorPrefix = "abcd1234",
            )

        /** Twenty completed rounds, oldest first; the last one is the unique scroll target. */
        val HISTORY =
            FULL.copy(
                rounds =
                    (0..18).map { i ->
                        SyncRoundSummary(
                            SyncRoundReason.PERIODIC,
                            TestClock.DEFAULT_NOW - (20 - i) * 60_000L,
                            SyncReport(0, 0, 0, 0, 1, 0, 20),
                        )
                    } +
                        SyncRoundSummary(
                            SyncRoundReason.FIRST_LINK,
                            TestClock.DEFAULT_NOW - 60_000,
                            SyncReport(1, 2, 0, 0, 3, 0, 40),
                        ),
            )
    }
}
