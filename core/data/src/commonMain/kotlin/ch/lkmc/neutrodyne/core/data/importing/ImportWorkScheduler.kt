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
    /**
     * §6 step 4's canonical enqueue — [ImportWorkPolicy.KEEP]. Left as a one-arg call because
     * the commit path is the only caller that must never disturb parked work.
     */
    suspend fun schedule(sessionId: Long) = schedule(sessionId, ImportWorkPolicy.KEEP)

    /**
     * Enqueue the session's fetch work under [policy] (05 §7's platform column). Best effort —
     * a failure must not undo the commit/fix-up writes that triggered it.
     */
    suspend fun schedule(
        sessionId: Long,
        policy: ImportWorkPolicy,
    )

    /**
     * §9's "cancel stops its host": Android cancels unique work `import-{sessionId}`; the
     * desktop lane stops at the next item and skips `CANCELLED` sessions itself. One-way and
     * best effort — the `CANCELLED` write is the durable half.
     */
    suspend fun cancel(sessionId: Long)
}

/** How an enqueue positions itself against a possibly-running fetch worker (05 §7). */
internal enum class ImportWorkPolicy {
    /**
     * The commit's enqueue (05 §6 step 4): WorkManager `KEEP` — an already-scheduled or
     * finishing `import-{sessionId}` request is undisturbed; the lane poke is unchanged.
     */
    KEEP,

    /**
     * A §8 fix-up's enqueue: WorkManager `APPEND_OR_REPLACE`, so a request landing while a
     * worker finishes runs after it instead of being dropped (the extra run ends at once
     * when nothing is pending). The desktop lane treats both policies as a poke.
     */
    APPEND_OR_REPLACE,
}
