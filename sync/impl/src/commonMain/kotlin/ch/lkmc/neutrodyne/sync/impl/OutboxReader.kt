// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits

/** 05's undo window: a group delete is held back this long so an undo never leaves the device. */
const val SYNC_UNDO_WINDOW_MS = 10_000L

/**
 * The push side of 10 Outbox and coalescing: reads outbox rows in `(hlc, nodeId)` order, collects
 * up to [ProtocolLimits.MAX_CHANGES] distinct records with all their rows, and holds a group
 * deletion and its member removals for the undo window.
 */
class OutboxReader(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
) {
    suspend fun next(limit: Int = ProtocolLimits.MAX_CHANGES): List<SyncOutboxEntity> {
        val rows = db.syncOutboxDao().rows()
        val now = clock.now()
        val heldGroups =
            rows
                .filter {
                    it.coll == "group" && it.field == "deleted" && it.value == "true" &&
                        now - (it.hlc ushr 16) < SYNC_UNDO_WINDOW_MS
                }.map { it.rid }
                .toSet()

        val result = mutableListOf<SyncOutboxEntity>()
        val records = mutableSetOf<Pair<String, String>>()
        for (row in rows) {
            if (row.coll == "group" && row.rid in heldGroups) continue
            if (row.coll == "member" && heldGroups.any { row.rid.startsWith(it) }) continue
            val key = row.coll to row.rid
            if (key !in records) {
                if (records.size >= limit) break
                records += key
            }
            result += row
        }
        return result
    }
}
