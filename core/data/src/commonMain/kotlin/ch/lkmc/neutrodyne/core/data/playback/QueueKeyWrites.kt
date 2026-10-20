// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.playback

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.OrderedRow
import ch.lkmc.neutrodyne.core.domain.planOrderKeyWrites

/** Existing row identity and addedAt survive moves; inserts are in requested episode order. */
internal data class QueueKeyWrites(
    val updates: List<Pair<Long, String>>,
    val inserts: List<Pair<Long, String>>,
)

/** Reuse the shared reorder planner, then fit new entries between the retained existing keys. */
internal fun planQueueWrites(
    current: List<QueueEntryEntity>,
    desired: List<Long>,
    orderKeys: OrderKeys,
): QueueKeyWrites {
    val byEpisode = current.associateBy { it.episodeId }
    val existingIndices = desired.indices.filter { desired[it] in byEpisode }
    val reordered =
        checkNotNull(
            planOrderKeyWrites(
                current.map { OrderedRow(it.episodeId, it.orderKey) },
                existingIndices.map { desired[it] },
                orderKeys,
            ),
        )
    require(desired.distinct().size == desired.size)
    val keys = current.associate { it.episodeId to it.orderKey } + reordered
    val updates = reordered.map { (id, key) -> byEpisode.getValue(id).id to key }
    val inserts = mutableListOf<Pair<Long, String>>()
    var lower: String? = null
    var nextExisting = 0
    for (id in desired) {
        if (id in byEpisode) {
            lower = keys.getValue(id)
            nextExisting++
            continue
        }
        val upper = existingIndices.getOrNull(nextExisting)?.let { keys.getValue(desired[it]) }
        if (lower != null && upper != null && lower >= upper) return rewriteQueue(current, desired, orderKeys)
        val key = orderKeys.between(lower, upper)
        if (key.length > OrderKeys.MAX_LENGTH) return rewriteQueue(current, desired, orderKeys)
        inserts += id to key
        lower = key
    }
    return QueueKeyWrites(updates, inserts)
}

internal fun rewriteQueue(
    current: List<QueueEntryEntity>,
    desired: List<Long>,
    orderKeys: OrderKeys,
): QueueKeyWrites {
    val byEpisode = current.associateBy { it.episodeId }
    val keys = orderKeys.rewrite(desired.size)
    val updates = mutableListOf<Pair<Long, String>>()
    val inserts = mutableListOf<Pair<Long, String>>()
    desired.forEachIndexed { index, id ->
        val row = byEpisode[id]
        if (row == null) {
            inserts += id to keys[index]
        } else if (row.orderKey != keys[index]) {
            updates += row.id to keys[index]
        }
    }
    return QueueKeyWrites(updates, inserts)
}

/** Apply only inside the caller's write transaction, preserving existing row identity and age. */
internal suspend fun NeutrodyneDatabase.applyQueueWrites(
    writes: QueueKeyWrites,
    now: Long,
) {
    for ((entryId, key) in writes.updates) queueDao().updateOrderKey(entryId, key)
    for ((episodeId, key) in writes.inserts) {
        queueDao().insert(QueueEntryEntity(episodeId = episodeId, orderKey = key, addedAt = now))
    }
}
