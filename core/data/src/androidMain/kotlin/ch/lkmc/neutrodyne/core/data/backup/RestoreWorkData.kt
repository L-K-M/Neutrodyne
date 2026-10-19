// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.work.Data
import androidx.work.workDataOf

/** `backup-restore` work input and the backup lane's shared names/tags (05 Restore, Auto Backup). */
internal object RestoreWorkData {
    private const val KEY_SESSION_ID = "sessionId"
    private const val KEY_AUTO = "auto"

    const val WORK_RESTORE = "backup-restore"
    const val WORK_SNAPSHOT = "backup-auto-snapshot"
    const val WORK_SNAPSHOT_NOW = "backup-auto-snapshot-now"
    const val TAG_BACKUP = "backup"
    const val RESTORE_BACKOFF_MS = 30_000L

    /**
     * The per-session admission tag (05's typed KEEP acknowledgment): `WorkInfo` exposes tags,
     * not input data, so the owning session rides a tag the scheduler can prove against.
     */
    fun sessionTag(sessionId: Long) = "restore-session-$sessionId"

    fun dataOf(
        sessionId: Long,
        auto: Boolean,
    ): Data = workDataOf(KEY_SESSION_ID to sessionId, KEY_AUTO to auto)

    fun sessionId(data: Data): Long? = data.getLong(KEY_SESSION_ID, -1L).takeIf { it >= 0L }

    fun auto(data: Data): Boolean = data.getBoolean(KEY_AUTO, false)
}
