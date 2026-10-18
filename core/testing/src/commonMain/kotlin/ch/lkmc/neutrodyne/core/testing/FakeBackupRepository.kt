// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.PreparedBackup
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import ch.lkmc.neutrodyne.core.model.SnapshotStatus
import ch.lkmc.neutrodyne.core.model.SnapshotWrite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [BackupRepository] for ViewModel/UI tests. [snapshotStatus] and
 * [restoreProgress] are the observed state — tests seed them directly; the snapshot
 * members apply their documented effects (the enable switch flips `enabled`,
 * `restoreAndroidBackup`/`discardAndroidBackup` clear `foreignPending`, a successful
 * restore reports `Finished`), everything else records calls and returns fixtures.
 */
class FakeBackupRepository : BackupRepository {
    val snapshotStatus =
        MutableStateFlow(
            SnapshotStatus(
                supported = true,
                enabled = false,
                lastWrittenAt = null,
                bytes = null,
                level = 0,
                lastError = null,
                foreignPending = false,
            ),
        )
    val restoreProgress = MutableStateFlow<RestoreProgress?>(null)

    var createOutcome: Outcome<BackupSummary, BackupError> = Outcome.Success(BackupSummary(0, 0, 0))
    var prepareOutcome: Outcome<PreparedBackup, BackupError> = Outcome.Success(FakePreparedBackup())
    var savePreparedOutcome: Outcome<Unit, BackupError> = Outcome.Success(Unit)

    /** NotABackup by default so tests always seed the preview they mean. */
    var inspectOutcome: Outcome<BackupPreview, BackupError> = Outcome.Failure(BackupError.NotABackup)
    var restoreOutcome: Outcome<Unit, BackupError> = Outcome.Success(Unit)
    var preflightCounts = PrivacyCounts(0, 0)
    var androidBackupOutcome: Outcome<Long, BackupError> = Outcome.Success(0L)
    var snapshotNowError: BackupError? = null

    /** Runs between [writeSnapshotNow]'s before and after reads — seed the write's effects here. */
    var onWriteSnapshotNow: suspend () -> Unit = {}

    val calls = mutableListOf<String>()

    /** `(sessionId, request)` of every [restore] call. */
    val restoreRequests = mutableListOf<Pair<Long, RestoreRequest>>()

    /** `(prepared, destinationUri)` of every [savePreparedBackup] call. */
    val savePreparedCalls = mutableListOf<Pair<PreparedBackup, String>>()

    override suspend fun createBackup(
        destinationUri: String,
        includePasswords: Boolean,
    ): Outcome<BackupSummary, BackupError> {
        calls += "createBackup($destinationUri, $includePasswords)"
        return createOutcome
    }

    override suspend fun prepareBackup(includePasswords: Boolean): Outcome<PreparedBackup, BackupError> {
        calls += "prepareBackup($includePasswords)"
        return prepareOutcome
    }

    override suspend fun savePreparedBackup(
        prepared: PreparedBackup,
        destinationUri: String,
    ): Outcome<Unit, BackupError> {
        calls += "savePreparedBackup(${prepared.fileName}, $destinationUri)"
        savePreparedCalls += prepared to destinationUri
        return savePreparedOutcome
    }

    override suspend fun inspect(sessionId: Long): Outcome<BackupPreview, BackupError> {
        calls += "inspect($sessionId)"
        return inspectOutcome
    }

    override suspend fun restore(
        sessionId: Long,
        request: RestoreRequest,
    ): Outcome<Unit, BackupError> {
        calls += "restore($sessionId, ${request.mode})"
        restoreRequests += sessionId to request
        val outcome = restoreOutcome
        if (outcome is Outcome.Success) {
            restoreProgress.value = RestoreProgress.Finished(sessionId, auto = false)
        }
        return outcome
    }

    override fun observeRestore(): Flow<RestoreProgress?> = restoreProgress

    override fun observeSnapshotStatus(): Flow<SnapshotStatus> = snapshotStatus

    override suspend fun setSnapshotEnabled(enabled: Boolean) {
        calls += "setSnapshotEnabled($enabled)"
        snapshotStatus.update { it.copy(enabled = enabled) }
    }

    override suspend fun preflight(includePasswords: Boolean): PrivacyCounts {
        calls += "preflight($includePasswords)"
        return preflightCounts
    }

    /** Success adopts the foreign snapshot, so `foreignPending` clears (05). */
    override suspend fun restoreAndroidBackup(): Outcome<Long, BackupError> {
        calls += "restoreAndroidBackup()"
        val outcome = androidBackupOutcome
        if (outcome is Outcome.Success) {
            snapshotStatus.update { it.copy(foreignPending = false) }
        }
        return outcome
    }

    override suspend fun discardAndroidBackup() {
        calls += "discardAndroidBackup()"
        snapshotStatus.update { it.copy(foreignPending = false) }
    }

    override suspend fun writeSnapshotNow(): Outcome<SnapshotWrite, BackupError> {
        calls += "writeSnapshotNow()"
        snapshotNowError?.let { return Outcome.Failure(it) }
        val before = snapshotStatus.value
        onWriteSnapshotNow()
        return Outcome.Success(SnapshotWrite(before, snapshotStatus.value))
    }

    /** The fake's own descriptor — the real repository refuses it, which the seam requires. */
    private class FakePreparedBackup(
        override val fileName: String = "neutrodyne-backup-2026-01-01-0000.zip",
        override val summary: BackupSummary = BackupSummary(0, 0, 0),
        override val privacy: PrivacyCounts = PrivacyCounts(0, 0),
    ) : PreparedBackup
}
