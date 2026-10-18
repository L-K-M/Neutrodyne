// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.PlaySessionInfo
import ch.lkmc.neutrodyne.core.model.QueueRemoval
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import kotlinx.coroutines.flow.Flow

/**
 * Main-safe durable Up next edits (06). Writes serialize in storage; success returns after commit.
 * Reads emit committed snapshots, never partial transactions. Storage failures propagate, including
 * through flows; cancellation is not swallowed. Up next edits never increment session generation;
 * clearing context does.
 * Playback rules/projectors, not this repository, skip unplayable rows or cap the player window.
 */
interface QueueRepository {
    fun observeUpNext(): Flow<List<UpNextItem>>

    fun observeSession(): Flow<PlaySessionInfo?>

    /** k >= 0; coherent current + complete Up next + at most k eligible context items. */
    fun observeVirtualQueue(k: Int): Flow<VirtualQueue>

    /** Distinct IDs in input order; move duplicates to the front/back, reject current/external. */
    suspend fun addNext(episodeIds: List<Long>): AddResult

    suspend fun addLast(episodeIds: List<Long>): AddResult

    /** Zero-based final index, clamped to list bounds. Missing episode is a no-op. */
    suspend fun move(
        episodeId: Long,
        toIndex: Int,
    )

    suspend fun remove(episodeIds: List<Long>)

    suspend fun clearUpNext()

    /**
     * [remove]/[clearUpNext] returning the undo token: the ids actually deleted (a subset of the
     * request, in queue order) and the stored order before deletion, captured in the same
     * transaction as the delete.
     */
    suspend fun removeWithUndo(episodeIds: List<Long>): QueueRemoval

    suspend fun clearWithUndo(): QueueRemoval

    /**
     * Restores [removal]'s rows in one transaction. Only ids still missing from Up next, still
     * existing, unplayed and not current are re-inserted; membership is restored rather than
     * re-added, so the add path's availability/YouTube gates do not apply. In original removed
     * order each id goes before its nearest surviving original successor, else after its closest
     * surviving original predecessor, else at its old index clamped to the list. Placement never
     * precedes a row restored earlier in this batch, even when surviving anchors were reversed.
     * Existing entries
     * keep their relative order — an already requeued id is left exactly where it is. Never
     * touches `play_session`.
     */
    suspend fun undoRemoval(removal: QueueRemoval)

    /** Stop after Up next: retain current/queue; clear context/anchor and increment generation. */
    suspend fun clearContext()
}
