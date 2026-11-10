// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundFailure
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import ch.lkmc.neutrodyne.sync.api.SyncStreamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncRuntimeDiagnosticsTest {
    @Test
    fun retainsTheLastTwentyCompletedRoundsIncludingFailures() {
        val runtime = SyncRuntimeDiagnostics()
        val ticket = runtime.activate(A)
        repeat(25) { index ->
            runtime.completed(
                ticket,
                if (index == 24) {
                    SyncRoundSummary(SyncRoundReason.PERIODIC, index.toLong(), null, SyncRoundFailure.TRANSPORT, 503)
                } else {
                    SyncRoundSummary(SyncRoundReason.SYNC_NOW, index.toLong(), REPORT)
                },
            )
        }
        val rounds = runtime.snapshot(A).rounds
        assertEquals((5L..24L).toList(), rounds.map { it.completedAtMs })
        assertEquals(REPORT, rounds.first().report)
        assertEquals(SyncRoundFailure.TRANSPORT, rounds.last().failure)
        assertEquals(503, rounds.last().httpStatus)
        assertNull(rounds.last().report)
    }

    @Test
    fun anOldInFlightCompletionCannotRotateBackToThePreviousAccount() {
        val runtime = SyncRuntimeDiagnostics()
        val old = runtime.activate(A)
        val current = runtime.activate(B)
        runtime.completed(current, SUMMARY)
        runtime.completed(old, SUMMARY.copy(completedAtMs = 99))
        assertEquals(listOf(SUMMARY), runtime.snapshot(B).rounds)
        assertTrue(runtime.snapshot(A).rounds.isEmpty())
    }

    @Test
    fun returningToAnIdenticalProfileStillRejectsItsOldGeneration() {
        val runtime = SyncRuntimeDiagnostics()
        val old = runtime.activate(A)
        runtime.activate(B)
        val returned = runtime.activate(A)
        runtime.completed(returned, SUMMARY)
        runtime.completed(old, SUMMARY.copy(completedAtMs = 99))
        assertEquals(listOf(SUMMARY), runtime.snapshot(A).rounds)
    }

    @Test
    fun changingProfileClearsStreamStateAndCounts() {
        val runtime = SyncRuntimeDiagnostics()
        val old = runtime.activate(A)
        runtime.streamOpening(old)
        runtime.streamState(old, SyncStreamState.CONNECTED)
        runtime.streamOpening(old)
        assertEquals(1, runtime.snapshot(A).reconnectCount)
        val current = runtime.activate(B)
        runtime.streamOpening(current)
        runtime.streamState(current, SyncStreamState.CONNECTED)
        runtime.streamState(old, SyncStreamState.STOPPED)
        assertEquals(SyncStreamState.CONNECTED, runtime.snapshot(B).streamState)
        assertEquals(0, runtime.snapshot(B).reconnectCount)
    }

    @Test
    fun gateCloseAndReopenCountsAnotherAttemptWithoutResettingTheProfile() {
        val runtime = SyncRuntimeDiagnostics()
        val ticket = runtime.activate(A)
        runtime.streamOpening(ticket)
        runtime.streamState(ticket, SyncStreamState.STOPPED)
        runtime.streamOpening(runtime.activate(A))
        assertEquals(1, runtime.snapshot(A).reconnectCount)
        assertEquals(SyncStreamState.CONNECTING, runtime.snapshot(A).streamState)
    }

    @Test
    fun aVersionBelongsOnlyToTheServerWhereDiscoveryRan() {
        val runtime = SyncRuntimeDiagnostics()
        runtime.activate(A)
        runtime.rememberVersion(A.serverUrl!!, "1.2.3")
        assertEquals("1.2.3", runtime.snapshot(A).serverVersion)
        runtime.activate(B)
        assertNull(runtime.snapshot(B).serverVersion)
    }

    @Test
    fun aNewProcessStartsWithNoHistoryAndIdentityNeverPrintsTheAddress() {
        val runtime = SyncRuntimeDiagnostics()
        runtime.activate(A)
        runtime.completed(runtime.activate(A), SUMMARY)
        assertTrue(SyncRuntimeDiagnostics().snapshot(A).rounds.isEmpty())
        assertFalse(A.toString().contains("secret.example"))
        assertFalse(runtime.activate(A).toString().contains("secret.example"))
    }

    private companion object {
        val A = SyncDiagnosticProfile("https://secret.example/path", "a", "device", "node", 1, true)
        val B = A.copy(serverUrl = "https://other.example", accountId = "b")
        val REPORT = SyncReport(1, 2, 3, 4, 5, 6, 7)
        val SUMMARY = SyncRoundSummary(SyncRoundReason.SYNC_NOW, 1, REPORT)
    }
}
