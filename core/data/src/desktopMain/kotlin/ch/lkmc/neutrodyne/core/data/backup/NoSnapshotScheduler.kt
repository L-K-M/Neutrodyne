// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler

/**
 * The desktop's [SnapshotScheduler] (05 No Auto Backup on computers, D34): the desktop has no
 * Auto Backup and no snapshot file, so the debounced "write soon" request and the
 * "turned off" cancel are documented no-ops — nothing schedules, retries, cancels or invents
 * a recovery backup. `BackupRepository`'s supported-ness comes from the bound implementation
 * itself.
 */
internal object NoSnapshotScheduler : SnapshotScheduler {
    override suspend fun requestSoon() = Unit

    override suspend fun cancelScheduled() = Unit
}
