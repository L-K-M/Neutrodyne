// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import dev.zacsweers.metro.Inject

/**
 * 05 GroupMerger: merges one group into another in one write transaction — memberships unite
 * (`INSERT OR IGNORE`, the loser's `orderKey`s ride along and the survivor's rows win on overlap),
 * the survivor's own settings row is kept (the loser's dies by FK cascade with its row), a GROUP
 * play context on the loser moves to the survivor, and the loser row goes. After the commit
 * `GroupChannelSync.sync()` sweeps the loser's channel. The callers 05 assigns it — the
 * `RestoreMerger` restore path and 10's `SyncApplier` — arrive with M3/M10, so for now nothing
 * calls this; it exists so their merge rule is implemented and tested once.
 */
@Inject
internal class GroupMerger(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val channelSync: GroupChannelSync,
) {
    suspend fun merge(
        loserId: Long,
        survivorId: Long,
    ) {
        require(loserId != survivorId) { "Cannot merge a group into itself" }
        val dao = db.groupDao()
        db.withWriteTransaction {
            requireNotNull(dao.groupById(loserId)) { "Losing group does not exist" }
            requireNotNull(dao.groupById(survivorId)) { "Surviving group does not exist" }
            dao.absorbMembers(survivorId, loserId)
            dao.moveGroupContext(loserId, survivorId)
            dao.deleteGroup(loserId)
            dao.touchUpdatedAt(setOf(survivorId), clock.now())
        }

        // The merge committed; a channel-sweep failure only defers the loser's channel delete to
        // the next sync() — it never turns a committed merge into a reported failure.
        suspendRunCatching { channelSync.sync() }
            .onFailure { Log.w(TAG, it) { "Group channel sweep failed after merge of $loserId" } }
    }

    private companion object {
        const val TAG = "Groups"
    }
}
