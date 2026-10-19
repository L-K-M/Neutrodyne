// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing.sync

import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState

/** Test-only single-account seam. W10 supplies the real writer/SQLite adapter and owns its close.
 * expiry deliberately accepts an unsafe raw rollback for the identity-authority regression;
 * normal restore must carry the current redirect ledger and canonicalize restored content.
 */
interface SyncServerAdapter {
    fun push(
        address: SyncAddress,
        state: RecordState,
    )

    fun rekey(
        from: RecordId.Episode,
        to: RecordId.Episode,
    )

    fun page(
        cursor: String?,
        limit: Int,
    ): SyncPage

    fun snapshot(): Map<SyncAddress, RecordState>

    fun expire(restore: Map<SyncAddress, RecordState>? = null)
}
