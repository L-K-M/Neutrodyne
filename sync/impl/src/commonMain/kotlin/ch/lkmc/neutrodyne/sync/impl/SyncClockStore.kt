// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Reads and writes the `sync_clock.clocks` envelope (10 Client sync engine, Raw state). */
class SyncClockStore(
    private val db: NeutrodyneDatabase,
) {
    suspend fun get(
        coll: String,
        rid: String,
    ): RawSyncRecord? = db.syncClockDao().get(coll, rid)?.let { decodeRaw(it.clocks) }

    suspend fun put(
        coll: String,
        rid: String,
        raw: RawSyncRecord,
    ) {
        db.syncClockDao().upsert(
            ch.lkmc.neutrodyne.core.database
                .SyncClockEntity(coll, rid, encodeRaw(raw)),
        )
    }

    suspend fun delete(
        coll: String,
        rid: String,
    ) {
        db.syncClockDao().upsert(
            ch.lkmc.neutrodyne.core.database.SyncClockEntity(
                coll,
                rid,
                encodeRaw(RawSyncRecord(RecordState(SyncCollection.fromWire(coll)))),
            ),
        )
    }

    private fun decodeRaw(json: String): RawSyncRecord? =
        runCatching { SyncJson.decodeFromString<RawSyncRecord>(json) }.getOrNull()

    private fun encodeRaw(raw: RawSyncRecord): String = SyncJson.encodeToString(raw)
}

/**
 * The field-level merge of an incoming record against the stored raw state and this device's
 * pending outbox rows (10 Pull and apply step 2). [advanced] are the incoming fields that won;
 * [lost] are those the local side kept.
 */
data class RecordMerge(
    val record: RecordState,
    val advanced: Set<String>,
    val lost: Set<String>,
)

object SyncRecordMerger {
    fun merge(
        stored: RecordState?,
        pending: RecordState?,
        incoming: RecordState,
    ): RecordMerge {
        val base = pending?.let { RecordMerger.merge(stored, it).record } ?: stored
        val result = RecordMerger.merge(base, incoming)
        return RecordMerge(result.record, result.advanced, result.lost)
    }
}
