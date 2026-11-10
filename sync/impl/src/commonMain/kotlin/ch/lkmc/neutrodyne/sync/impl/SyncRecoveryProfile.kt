// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.SyncStateEntity

/** Cursor/clock progress may change during a pull; the owning link may not. */
internal fun requireSyncProfile(
    expected: SyncStateEntity,
    current: SyncStateEntity?,
) {
    check(
        current != null && current.enabled == expected.enabled &&
            current.serverUrl == expected.serverUrl && current.accountId == expected.accountId &&
            current.deviceId == expected.deviceId && current.nodeId == expected.nodeId &&
            current.linkedAt == expected.linkedAt && current.lastError != SYNC_ERROR_REVOKED,
    ) { "Sync profile changed during recovery" }
}
