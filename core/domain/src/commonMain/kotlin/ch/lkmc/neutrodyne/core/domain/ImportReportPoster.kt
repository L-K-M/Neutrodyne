// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.ImportItemStatus

/**
 * The `import_backup` channel's notification port (05 §8, Restore): once a fetch session
 * reaches `DONE` the runner posts a single "N added, M need attention" notice (ID 3001)
 * whose click opens `neutrodyne://open/import/{sessionId}`; a rejected restore posts its
 * failure (ID 3003); the first-launch foreign snapshot posts a pending restore offer
 * (ID 3004, its caller is C5's first-launch path). Implemented by the Android
 * notification-channel poster and the desktop `DesktopNotifier` call site; each suppresses
 * or downgrades per its own rules (permission, visible screen). In `:core:domain` beside
 * the other platform ports.
 */
interface ImportReportPoster {
    /**
     * Called once, after the session's `DONE` commit. [counts] maps every `ImportItemStatus`
     * present on the session's items to its tally — the same shape `ImportSessionView.counts`
     * carries.
     */
    suspend fun postDone(
        sessionId: Long,
        counts: Map<ImportItemStatus, Int>,
    )

    /**
     * Called once the runner answered `Failed` — the session is `CANCELLED` and the click
     * opens its report. [auto] distinguishes the adopted auto-snapshot's first-launch
     * wording from a manual restore's.
     */
    suspend fun postRestoreFailed(
        sessionId: Long,
        auto: Boolean,
        error: BackupError,
    )

    /**
     * A foreign `auto-snapshot.zip` sits ready for the user to restore — posted by the
     * first-launch path that detects it, never by the snapshot producer.
     */
    suspend fun postForeignSnapshotPending()
}
