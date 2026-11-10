// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.SyncStateEntity

/** Admission health may change after a 401; cleanup still belongs to this exact link. */
internal fun sameSyncIdentity(
    expected: SyncStateEntity,
    current: SyncStateEntity?,
): Boolean =
    current != null && current.serverUrl == expected.serverUrl && current.accountId == expected.accountId &&
        current.deviceId == expected.deviceId && current.nodeId == expected.nodeId &&
        current.linkedAt == expected.linkedAt

/** Preserve the failing round's owner through the controller's later error cleanup. */
internal class SyncOwnedRevocationException(
    val profile: SyncStateEntity,
    failure: SyncTransportException,
) : SyncTransportException(failure.status, failure.code, failure.message.orEmpty(), failure.retryAfterSeconds) {
    init {
        initCause(failure)
    }
}

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
