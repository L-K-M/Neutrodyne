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

    /**
     * Whether the host's restore work is enqueued, blocked or running — the auto-snapshot's
     * "restore owns the lane" guard (05 Snapshot production step 2). Android asks WorkManager
     * for unique work `backup-restore`; the desktop asks the `import-backup` lane. Callers
     * treat a thrown error as "cannot prove idle" — fail closed, never best-guess overwrite.
     */
    suspend fun hasRestoreWork(): Boolean

    /**
     * Enqueues the durable restore request of [sessionId] — Android's unique work
     * `backup-restore` under `KEEP`, the desktop's `import-backup` lane poke. Unlike fetch
     * [schedule], the caller must know whether the request actually landed: WorkManager
     * `KEEP` silently ignores a new request while a finishing worker still holds the name,
     * so the answer distinguishes [RestoreEnqueue.ENQUEUED] from
     * [RestoreEnqueue.ALREADY_RUNNING]. [auto] is the session's `fromAndroidBackup` flag —
     * the worker needs it to pick the first-launch restore's notification wording.
     *
     * Implementation contract (native, C2/C3): report [RestoreEnqueue.ALREADY_RUNNING] only
     * when the platform *definitively* kept the existing request — an ambiguous answer must
     * surface as a thrown failure instead, because the caller then keeps the durable intent
     * for start-up reconciliation: a native host must re-scan `PREVIEW` backup sessions for
     * `restoreRequestedAt` at start-up and enqueue whatever the last claim could not.
     */
    suspend fun scheduleRestore(
        sessionId: Long,
        auto: Boolean,
    ): RestoreEnqueue
}

/**
 * What the restore enqueue did (05 Restore algorithm): [ENQUEUED] means the new request is
 * or will be running; [ALREADY_RUNNING] means the existing request definitively kept the
 * work name — the caller's just-written durable intent is provably unused and is rolled
 * back, answering the requester `RestoreRunning` like any second request.
 */
internal enum class RestoreEnqueue {
    ENQUEUED,
    ALREADY_RUNNING,
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
