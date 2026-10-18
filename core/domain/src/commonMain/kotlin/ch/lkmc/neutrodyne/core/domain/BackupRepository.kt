// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import ch.lkmc.neutrodyne.core.model.SnapshotStatus
import kotlinx.coroutines.flow.Flow

/**
 * Full backup and restore (05). Backup files arrive through the import pipeline as
 * `NEUTRODYNE_BACKUP` sessions; [inspect] validates one and [restore] runs it.
 * Implemented by `:core:data` in M3.
 */
interface BackupRepository {
    suspend fun createBackup(
        destinationUri: String,
        includePasswords: Boolean,
    ): Outcome<BackupSummary, BackupError>

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

    /** The R1.9 warning counts before [createBackup] (05 Writing). */
    suspend fun preflight(includePasswords: Boolean): PrivacyCounts

    /** Android: the foreign snapshot → a restore session id; desktop: Unsupported (05). */
    suspend fun restoreAndroidBackup(): Outcome<Long, BackupError>

    /** Android: renames the pending foreign snapshot to `restored-{ts}.zip` (05). */
    suspend fun discardAndroidBackup()

    /** Android only (SnapshotNowReceiver, the debug row); desktop: Unsupported (05). */
    suspend fun writeSnapshotNow(): Outcome<SnapshotStatus, BackupError>
}

/**
 * Restore lifecycle for the UI (05). In `:core:domain`, not `:core:model`, because
 * [Failed] carries a [BackupError] and `:core:model` cannot see domain types
 * (2026-10-17 M3 note in 05).
 */
sealed interface RestoreProgress {
    data class Running(
        val phase: String,
        val done: Int,
        val total: Int,
    ) : RestoreProgress

    data class Finished(
        val sessionId: Long,
        val auto: Boolean,
    ) : RestoreProgress

    data class Failed(
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

    /** An Android-only member was called on the desktop (05). */
    data object Unsupported : BackupError
}
