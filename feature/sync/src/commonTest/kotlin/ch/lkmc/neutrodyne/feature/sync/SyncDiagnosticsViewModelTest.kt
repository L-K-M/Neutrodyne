// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncController
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Sync › Diagnostics state (08 Sync diagnostics): the snapshot is read once per
 * profile through `SyncController.diagnostics`, an identity change cancels and discards a
 * previous profile's read, `UNAVAILABLE` is a retryable failure, and the copy text exists only
 * while the shown snapshot is still the live identity's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncDiagnosticsViewModelTest : MainDispatcherTest() {
    private fun viewModel(
        controller: SyncController,
        sdk: Int? = null,
    ) = SyncDiagnosticsViewModel(controller, TestPlatformInfo(sdk))

    @Test
    fun publishesTheSnapshotTheServiceReturns() =
        runTest {
            val controller = GatedController()
            controller.fake.diagnosticsResult = Outcome.Success(SNAPSHOT_A)
            val vm = viewModel(controller)
            runCurrent()

            assertEquals(SNAPSHOT_A, vm.uiState.value.snapshot)
            assertFalse(vm.uiState.value.loading)
            assertEquals(1, controller.attempts.size)
            assertEquals(1, controller.fake.diagnosticsReads.size)
        }

    @Test
    fun unavailableIsARetryableFailureNotAZeroCountSuccess() =
        runTest {
            val controller = GatedController()
            controller.fake.diagnosticsResult = Outcome.Failure(SyncDiagnosticsError.UNAVAILABLE)
            val vm = viewModel(controller)
            runCurrent()

            assertTrue(vm.uiState.value.failed)
            assertNull(vm.uiState.value.snapshot)

            controller.fake.diagnosticsResult = Outcome.Success(SNAPSHOT_A)
            vm.refresh()
            runCurrent()

            assertEquals(SNAPSHOT_A, vm.uiState.value.snapshot)
            assertFalse(vm.uiState.value.failed)
        }

    @Test
    fun anIdentityChangeClearsTheOldSnapshotBeforeTheNewReadLands() =
        runTest {
            val controller = GatedController()
            controller.fake.diagnosticsResult = Outcome.Success(SNAPSHOT_A)
            val vm = viewModel(controller)
            runCurrent()
            assertEquals(SNAPSHOT_A, vm.uiState.value.snapshot)

            val gate = CompletableDeferred<Unit>()
            controller.gate = gate
            controller.fake.status.value = OTHER
            runCurrent()

            assertTrue(vm.uiState.value.loading)
            assertNull(vm.uiState.value.snapshot)

            controller.fake.diagnosticsResult = Outcome.Success(SNAPSHOT_B)
            gate.complete(Unit)
            runCurrent()

            assertEquals(SNAPSHOT_B, vm.uiState.value.snapshot)
        }

    @Test
    fun aReadBlockedAcrossAProfileSwitchIsDiscarded() =
        runTest {
            val controller = GatedController()
            val gate = CompletableDeferred<Unit>()
            controller.gate = gate
            controller.fake.diagnosticsResult = Outcome.Success(SNAPSHOT_A)
            val vm = viewModel(controller)
            runCurrent()
            assertEquals(1, controller.attempts.size)

            controller.fake.status.value = OTHER
            runCurrent()
            // The switch cancelled the first read and started the new profile's.
            assertEquals(2, controller.attempts.size)

            controller.fake.diagnosticsResult = Outcome.Success(SNAPSHOT_B)
            gate.complete(Unit)
            runCurrent()

            assertEquals(SNAPSHOT_B, vm.uiState.value.snapshot)
            // The first read died in the gate and never reached the service.
            assertEquals(1, controller.fake.diagnosticsReads.size)
        }

    @Test
    fun theCopyTextExistsOnlyWhileTheSnapshotIsTheLiveIdentitys() =
        runTest {
            val controller = GatedController()
            controller.fake.diagnosticsResult = Outcome.Success(SNAPSHOT_A)
            val vm = viewModel(controller)
            runCurrent()
            assertTrue(vm.plainText() != null, "a current snapshot produces the copy text")

            // Hold the new profile's read: while nothing current is published, the old
            // profile's lines must never be copied — before or after the collector runs.
            controller.gate = CompletableDeferred()
            controller.fake.status.value = OTHER
            assertNull(vm.plainText())
            runCurrent()
            assertNull(vm.plainText())
        }

    @Test
    fun theCopyTextCarriesSnapshotFieldsAndNoSecrets() =
        runTest {
            val controller = GatedController()
            controller.fake.diagnosticsResult = Outcome.Success(SNAPSHOT_A)
            val vm = viewModel(controller)
            runCurrent()

            val text = vm.plainText() ?: error("a current snapshot must produce the copy text")
            assertTrue("serverVersion: 1.4.2" in text)
            assertTrue("protocol: 3" in text)
            assertTrue("accountName: anna" in text)
            assertTrue("deviceName: Anna's Pixel" in text)
            assertTrue("lastSyncAtMs: 1000000" in text)
            assertTrue("pending.podcast: 2" in text)
            assertTrue("pending.other: 3" in text)
            assertTrue("streamState: CONNECTED" in text)
            assertTrue("reconnectCount: 2" in text)
            assertTrue("lanGate: GRANTED" in text)
            assertTrue("lastError: clock_skew" in text)
            assertTrue("cursorPrefix: abcd1234" in text)
            assertFalse("://" in text, "no server or feed URL may appear")
            assertFalse("token" in text.lowercase())
            assertFalse("password" in text.lowercase())
        }

    @Test
    fun aRefreshCancelsTheReadItReplaces() =
        runTest {
            val controller = GatedController()
            val gate = CompletableDeferred<Unit>()
            controller.gate = gate
            controller.fake.diagnosticsResult = Outcome.Success(SNAPSHOT_A)
            val vm = viewModel(controller)
            runCurrent()
            assertEquals(1, controller.attempts.size)

            vm.refresh()
            runCurrent()
            assertEquals(2, controller.attempts.size)

            gate.complete(Unit)
            runCurrent()
            assertEquals(SNAPSHOT_A, vm.uiState.value.snapshot)
            // Only the surviving read reached the service.
            assertEquals(1, controller.fake.diagnosticsReads.size)
        }

    @Test
    fun theLanGateRowIsAndroidOnly() =
        runTest {
            assertFalse(viewModel(GatedController()).showLanGate)
            assertTrue(viewModel(GatedController(), sdk = 37).showLanGate)
        }

    @Test
    fun theCopiedMessageFollowsThePlatformRule() =
        runTest {
            val desktop = viewModel(GatedController())
            desktop.copied()
            runCurrent()
            assertEquals(1, desktop.messages.value.size)

            val newAndroid = viewModel(GatedController(), sdk = 33)
            newAndroid.copied()
            runCurrent()
            assertTrue(newAndroid.messages.value.isEmpty(), "Android 13+ shows its own toast")

            val oldAndroid = viewModel(GatedController(), sdk = 32)
            oldAndroid.copied()
            runCurrent()
            assertEquals(1, oldAndroid.messages.value.size)
        }

    /** Delegates to [FakeSyncController] but parks `diagnostics` behind an optional gate. */
    private class GatedController(
        val fake: FakeSyncController = FakeSyncController(LINKED),
    ) : SyncController by fake {
        var gate: CompletableDeferred<Unit>? = null
        val attempts = mutableListOf<Unit>()

        override suspend fun diagnostics(): Outcome<SyncDiagnosticsSnapshot, SyncDiagnosticsError> {
            attempts += Unit
            gate?.await()
            return fake.diagnostics()
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

    private companion object {
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

        val OTHER = LINKED.copy(serverUrl = "https://other.example.org", accountId = "acc-b", deviceId = "dev-b")

        val SNAPSHOT_A =
            SyncDiagnosticsSnapshot(
                serverVersion = "1.4.2",
                protocol = 3,
                accountName = "anna",
                deviceName = "Anna's Pixel",
                lastSyncAtMs = 1_000_000,
                rounds =
                    listOf(
                        SyncRoundSummary(
                            SyncRoundReason.SYNC_NOW,
                            900_000,
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
                            950_000,
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

        val SNAPSHOT_B = SNAPSHOT_A.copy(accountName = "bert", deviceName = "Bert's Mac")
    }
}
