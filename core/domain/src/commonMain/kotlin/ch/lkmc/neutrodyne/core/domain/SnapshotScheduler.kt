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
}
