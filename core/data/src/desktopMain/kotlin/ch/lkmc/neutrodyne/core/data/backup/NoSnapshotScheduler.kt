// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler

/**
 * The desktop's [SnapshotScheduler] (05 No Auto Backup on computers, D34): the desktop has no
 * Auto Backup and no snapshot file, so the debounced "write soon" request is a documented
 * no-op — nothing schedules, retries or invents a recovery backup. `BackupRepository`'s
 * supported-ness comes from the bound implementation itself.
 */
internal object NoSnapshotScheduler : SnapshotScheduler {
    override suspend fun requestSoon() = Unit
}
