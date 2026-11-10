// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.sync

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.api.SyncRoundSummary
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import kotlinx.coroutines.flow.StateFlow

/**
 * The driver side of `SyncController.submitPassword`.
 *
 * The controller launches its login asynchronously and clears the `CharArray` it receives in that
 * coroutine. The driver therefore transfers an independent copy through [array] and keeps its own
 * password for local cleanup; the transfer is never erased here while the controller may still
 * read it. [launched] clears only the driver-owned password, so local cleanup cannot corrupt what
 * the controller still consumes. [close] clears both and is used when no coroutine will (a
 * synchronous rejection, or the driver stops before the controller consumes the copy).
 */
class CrossDevicePasswordTransfer(
    password: CharArray,
) : AutoCloseable {
    private val transfer: CharArray = password.copyOf()
    private val driverPassword: CharArray = password

    /** The independent copy to pass to `submitPassword`; the controller owns it once passed. */
    val array: CharArray
        get() = transfer

    /**
     * The controller accepted the password for an asynchronous login: it owns the transfer and
     * will clear it. Only the driver-owned password is cleared here.
     */
    fun launched() {
        driverPassword.fill('\u0000')
    }

    /** Clears the transfer and the driver-owned password; used only when no coroutine will. */
    override fun close() {
        transfer.fill('\u0000')
        driverPassword.fill('\u0000')
    }
}

/**
 * True when the persisted `sync_state` carries link, clock, cursor, protocol, error or applying
 * residue. The default unlinked row is clean; DataStore preference flags are not part of
 * `sync_state` and are not residue.
 */
fun SyncStateEntity?.hasLinkOrSyncResidue(): Boolean =
    this != null && (
        enabled || applying || linkedAt != null || accountId != null || deviceId != null ||
            serverUrl != null || cursor != null || hlc != 0L || nodeId != null ||
            clockOffsetMs != 0L || protocol != null || lastSyncAt != null || lastError != null
    )

/**
 * The fixture's `nextRefreshAt`: an actual current-time future bound. `T_BASE + one year` is in the
 * past for any run after 2025-10-27 and would let the refresh scheduler run against the fixture
 * feed during the test. The production `RefreshPolicy.NEVER` sentinel is internal to `:core:data`,
 * so the driver uses a current-time bound instead.
 */
fun fixtureNextRefreshAt(nowMs: Long): Long = nowMs + REFRESH_FAR_FUTURE_MS

/**
 * A malformed `sync_clock` row silently removes a record from the canonical wire state the host
 * compares, so the dump must fail rather than report a partial result. The count stays in the
 * successful output for schema compatibility.
 */
fun skippedClockRowsFailure(skippedClockRows: Int): String? =
    if (skippedClockRows > 0) "malformed sync_clock rows: $skippedClockRows" else null

/**
 * Arms and waits for one `SyncController.syncNow()` round.
 *
 * The production diagnostics keep at most twenty round summaries per process and reuse the same
 * immutable `SyncRoundSummary` objects across reads (`SyncRuntimeDiagnostics.snapshot` and
 * `SyncDiagnosticsReader` pass the list through without copying), so the barrier captures an
 * identity baseline before arming and accepts only a summary that is not already in it. A list
 * size or a timestamp window would accept a stale `SYNC_NOW` round that completed before
 * `syncNow()` was called.
 *
 * [arm] waits for any prior round to finish (`controller.syncing == false`) and snapshots the
 * current rounds. [syncNowAndAwait] then refuses preexisting summaries and returns only a fresh
 * `SYNC_NOW` round whose completion left `syncing` false.
 */
class CrossDeviceSyncBarrier(
    private val controller: SyncController,
    private val timeoutMs: Long,
    private val pollMs: Long,
    private val nanoTime: () -> Long,
    private val delay: suspend (Long) -> Unit,
) {
    private var baseline: List<SyncRoundSummary> = emptyList()
    private var armed = false

    /**
     * Waits for any prior round to become idle and captures the identity baseline.
     *
     * @return null on success, or a safe failure reason when a prior round stayed busy or the
     *   diagnostics read failed.
     */
    suspend fun arm(): String? {
        val deadline = nanoTime() + timeoutMs * 1_000_000
        while (controller.syncing.value) {
            if (nanoTime() >= deadline) {
                return "a prior sync round is still running after ${timeoutMs}ms"
            }
            delay(pollMs)
        }
        val snapshot = controller.diagnostics()
        if (snapshot !is Outcome.Success) return "sync diagnostics are unavailable"
        baseline = snapshot.value.rounds
        armed = true
        return null
    }

    /**
     * Calls `syncNow()` and returns the first fresh `SYNC_NOW` summary that is not in the baseline
     * and that completed while `syncing` is false, or null on timeout.
     */
    suspend fun syncNowAndAwait(): SyncRoundSummary? {
        check(armed) { "the barrier must be armed before syncNow" }
        controller.syncNow()
        val deadline = nanoTime() + timeoutMs * 1_000_000
        while (true) {
            if (nanoTime() >= deadline) return null
            val fresh =
                currentRounds().lastOrNull { round ->
                    round.reason == SyncRoundReason.SYNC_NOW && baseline.none { it === round }
                }
            if (fresh != null && !controller.syncing.value) return fresh
            delay(pollMs)
        }
    }

    private suspend fun currentRounds(): List<SyncRoundSummary> =
        (controller.diagnostics() as? Outcome.Success)?.value?.rounds ?: emptyList()
}

/**
 * Waits boundedly for [SyncStatus.Linked]. `beginMerge` publishes `LinkState.Linked` before the
 * asynchronous `refreshStatus()`, so a driver that reads `status.value` immediately can fail a
 * link that actually succeeded.
 */
suspend fun awaitLinkedStatus(
    status: StateFlow<SyncStatus>,
    timeoutMs: Long,
    pollMs: Long,
    nanoTime: () -> Long,
    delay: suspend (Long) -> Unit,
): Boolean {
    val deadline = nanoTime() + timeoutMs * 1_000_000
    while (status.value !is SyncStatus.Linked) {
        if (nanoTime() >= deadline) return false
        delay(pollMs)
    }
    return true
}

/**
 * The safe failure reason for a finished sync round, or null when the round is acceptable.
 *
 * A round with rejected records is a failure even when the transport succeeded. A revoked or
 * clock-blocked link state is a failure even when the round summary is clean: the status is the
 * public check, and the persisted marker is the fallback for a state the status refresh has not
 * published yet.
 */
fun syncRoundFailure(
    round: SyncRoundSummary,
    status: SyncStatus,
    lastError: String?,
): String? {
    val failure = round.failure
    if (failure != null) {
        return "round failed: $failure (${round.protocolCode ?: "no code"})"
    }
    val report = round.report ?: return "round finished without a report"
    if (report.rejected > 0) return "round rejected ${report.rejected} record(s)"
    if (status is SyncStatus.Revoked) return "link is revoked"
    val problem = (status as? SyncStatus.Linked)?.problem
    if (problem == SyncProblem.Revoked || problem == SyncProblem.ClockSkew) {
        return "link is revoked or clock-skewed"
    }
    if (isBlockingPersistedError(lastError)) return "link is revoked or clock-skewed"
    return null
}

/**
 * The persisted `sync_state.lastError` markers that block a sync round. `Revoked` is the
 * revocation marker; `ClockSkew` and the `clock_skew` wire code are the clock markers the pending
 * clock-rejection work persists. The public [SyncStatus]/[SyncProblem] checks are the primary gate;
 * this covers a persisted marker the status refresh has not published.
 */
fun isBlockingPersistedError(lastError: String?): Boolean =
    lastError == SYNC_ERROR_REVOKED ||
        lastError == SYNC_ERROR_CLOCK_BLOCKED ||
        lastError == SyncErrorCode.CLOCK_SKEW.wire

private const val SYNC_ERROR_REVOKED = "Revoked"
private const val SYNC_ERROR_CLOCK_BLOCKED = "ClockSkew"
