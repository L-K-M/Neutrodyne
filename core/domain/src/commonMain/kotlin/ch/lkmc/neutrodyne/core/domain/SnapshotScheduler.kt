// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * The Auto Backup "write soon" port (05 Snapshot production): writers that change the
 * library poke it for the debounced 10-minute snapshot. Android's
 * `WorkManagerSnapshotScheduler` enqueues `backup-auto-snapshot-now`; the desktop's
 * `NoSnapshotScheduler` does nothing. In `:core:domain` beside the other ports (05's
 * module table files it under `:core:data` platform ports; 2026-10-17 M3 note in 05).
 */
interface SnapshotScheduler {
    suspend fun requestSoon()

    /**
     * Cancels both work names (`backup-auto-snapshot` and `backup-auto-snapshot-now`, 05
     * Snapshot production) — the "turned off" step of the toggle. One-way and best effort:
     * the persisted `backup.auto_snapshot_enabled` flag is the durable half, and a periodic
     * enqueue that survives cancellation still ends at the worker's enabled gate. Re-enabling
     * re-arms the one-time work through [requestSoon]; the periodic name returns with the
     * start-up initializer's next `UPDATE` enqueue (05's work table).
     */
    suspend fun cancelScheduled()
}
