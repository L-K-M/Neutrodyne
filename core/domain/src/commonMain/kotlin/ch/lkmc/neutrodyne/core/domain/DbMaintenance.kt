// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * One `db-maintenance` pass (02 db-maintenance worker): retention, orphan and import-session
 * sweeps, parked-row expiry, artwork garbage collection, `PRAGMA optimize`/`quick_check`,
 * guarded `VACUUM` and the diagnostics stats record. Implemented by `:core:data`'s
 * `DbMaintenanceImpl`; scheduled by Android's `DbMaintenanceWorker` (WorkManager
 * `db-maintenance`) and the desktop runner's `maintenance` lane.
 */
interface DbMaintenance {
    /**
     * Runs 02's steps in order and records their outcome. [deadlineElapsedMs] is a
     * `Clock.elapsedRealtime` soft deadline checked between steps and between retention
     * batches — the work simply stops, the next run continues (Android's 8 min budget);
     * `null` means no deadline (the desktop lane).
     *
     * [vacuumBlocker] is consulted only when the file-side `VACUUM` guards already pass and
     * returns a human-readable reason vacuum must not run (the desktop lane probes the job
     * runner for an active download or sync lane); `null` means no extra guard.
     */
    suspend fun run(
        now: Long,
        deadlineElapsedMs: Long? = null,
        vacuumBlocker: (suspend () -> String?)? = null,
    )
}
