// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.PreparedBackup
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import ch.lkmc.neutrodyne.core.model.SnapshotStatus
import ch.lkmc.neutrodyne.core.model.SnapshotWrite
import kotlinx.coroutines.flow.Flow

/**
 * Full backup and restore (05). Backup files arrive through the import pipeline as
 * `NEUTRODYNE_BACKUP` sessions; [inspect] validates one and [restore] runs it.
 * Implemented by `:core:data` in M3.
 */
interface BackupRepository {
    /**
     * Writes a manual backup straight to [destinationUri]. Non-interactive callers only:
     * the R1.9 warning cannot be checked against the saved bytes (05 Writing). The
     * interactive flow is [prepareBackup] → warning → [savePreparedBackup].
     */
    suspend fun createBackup(
        destinationUri: String,
        includePasswords: Boolean,
    ): Outcome<BackupSummary, BackupError>

    /**
     * Writes a manual backup once into a private cache leaf and returns its receipt
     * (05 Writing). The descriptor's summary and privacy counts are measured from that
     * same write, so the R1.9 warning always describes the bytes the user may save.
     */
    suspend fun prepareBackup(includePasswords: Boolean): Outcome<PreparedBackup, BackupError>

    /**
     * Copies a [prepareBackup] artifact to [destinationUri] without touching the library
     * or secrets again (05 Writing). The owned cache file is deleted on success; a failed
     * copy keeps it for a retry, and an expired or foreign descriptor fails explicitly —
     * the archive is never rebuilt silently.
     */
    suspend fun savePreparedBackup(
        prepared: PreparedBackup,
        destinationUri: String,
    ): Outcome<Unit, BackupError>

    /** Validates the session's archive and builds the restore preview (05). */
    suspend fun inspect(sessionId: Long): Outcome<BackupPreview, BackupError>

    /** Schedules the RestoreRunner host (05 Restore algorithm). */
    suspend fun restore(
        sessionId: Long,
        request: RestoreRequest,
    ): Outcome<Unit, BackupError>

    fun observeRestore(): Flow<RestoreProgress?>

    /** `SnapshotStatus.supported` is false on the desktop (05 Auto Backup). */
    fun observeSnapshotStatus(): Flow<SnapshotStatus>

    /** Android; a no-op on the desktop (05 Auto Backup). */
    suspend fun setSnapshotEnabled(enabled: Boolean)

    /**
     * Advisory R1.9 warning counts (05 Writing). These are read from a separate, earlier
     * snapshot than a later [createBackup], so library or credential changes in between
     * make them stale. The interactive flow warns from [PreparedBackup.privacy] instead;
     * this stays for callers that only need a preview.
     */
    suspend fun preflight(includePasswords: Boolean): PrivacyCounts

    /** Android: the foreign snapshot → a restore session id; desktop: Unsupported (05). */
    suspend fun restoreAndroidBackup(): Outcome<Long, BackupError>

    /** Android: renames the pending foreign snapshot to `restored-{ts}.zip` (05). */
    suspend fun discardAndroidBackup()

    /**
     * Android only (SnapshotNowReceiver, the debug row); desktop: Unsupported (05). The
     * pair is read under the write lock so the receiver's before/after verdict proves a
     * real publication, never an interleaved one.
     */
    suspend fun writeSnapshotNow(): Outcome<SnapshotWrite, BackupError>
}

/**
 * Restore lifecycle for the UI (05). In `:core:domain`, not `:core:model`, because
 * [Failed] carries a [BackupError] and `:core:model` cannot see domain types
 * (2026-10-17 M3 note in 05).
 */
sealed interface RestoreProgress {
    /** Every emission belongs to one restore, including retained terminal progress. */
    val sessionId: Long

    data class Running(
        override val sessionId: Long,
        val phase: String,
        val done: Int,
        val total: Int,
    ) : RestoreProgress

    data class Finished(
        override val sessionId: Long,
        val auto: Boolean,
    ) : RestoreProgress

    data class Failed(
        override val sessionId: Long,
        val error: BackupError,
    ) : RestoreProgress
}

/** Why a backup/restore call failed (05 Error handling and failure modes). */
sealed interface BackupError {
    data object NotABackup : BackupError

    /** `manifest.minReaderVersion` is newer than this build restores (05 Versioning). */
    data class NewerFormat(
        val minReaderVersion: Int,
    ) : BackupError

    /** The named entry is missing, truncated, or fails its SHA-256 (05 Validation). */
    data class Corrupt(
        val entry: String,
    ) : BackupError

    data object TooLarge : BackupError

    data object Io : BackupError

    data object Storage : BackupError

    /** A second restore request while one runs (05 Restore algorithm). */
    data object RestoreRunning : BackupError

    data object DestinationUnavailable : BackupError

    /**
     * `savePreparedBackup` cannot use the descriptor: it did not come from
     * `prepareBackup`, or its owned cache copy was already consumed by a save or swept
     * as abandoned (05 Writing). The archive is never rebuilt — a fresh `prepareBackup`
     * is the only recovery.
     */
    data object PreparedExpired : BackupError

    /**
     * The call is not supported here: an Android-only member on the desktop, or a linked
     * restore request — linked execution (unlink, capture, ALL_DEVICES) is MS2 (05).
     */
    data object Unsupported : BackupError
}
