// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

/**
 * The schedule seam of 05 §6 step 4 and the §8 fix-ups: after a session's commit (or a retry
 * that re-queues work) the host's import fetch work is enqueued. Android binds unique work
 * `import-{sessionId}` (`ImportFetchWorker`, `:core:data` `androidMain`, C2); the desktop pokes
 * the `import-backup` lane (`LaneImportWorkScheduler`, `desktopMain`, C3). B5 owns only this
 * port — implementations land with their platform rows, and [schedule] is a one-way request:
 * the runner re-reads `activeSessions()` and ignores sessions with nothing to do.
 */
internal interface ImportWorkScheduler {
    suspend fun schedule(sessionId: Long)
}
