// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.PooledConnection
import androidx.room3.Query
import androidx.room3.Upsert
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * `sync_state` singleton access (02 Sync bookkeeping). The capture-side methods
 * (`withApplying`, `raiseHlc`, `clampHlc`, `setClockOffset`) and the link/unlink writers are the
 * engine's storage contract. `sync_state` is never observed by a `Flow` (the triggers write it on
 * every capture, 02).
 */
@Dao
abstract class SyncStateDao(
    private val db: NeutrodyneDatabase,
) {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    abstract suspend fun get(): SyncStateEntity?

    /** MS0's link/first-link writes and tests' linked fixtures — the only row writer for now. */
    @Upsert
    abstract suspend fun upsert(row: SyncStateEntity)

    /**
     * Runs [block] with `applying = 1`, so the capture triggers stay silent. The caller owns the
     * writer transaction, including cancellation and rollback; the flag is restored even when the
     * block is cancelled.
     */
    suspend fun <T> withApplying(block: suspend () -> T): T =
        db.syncWriter { connection ->
            val previous = connection.scalar("SELECT applying FROM sync_state WHERE id = 0")
            try {
                connection.execute("UPDATE sync_state SET applying = 1 WHERE id = 0")
                block()
            } finally {
                // A cancelled caller must still restore the flag before its transaction unwinds.
                withContext(NonCancellable) {
                    connection.execute("UPDATE sync_state SET applying = ? WHERE id = 0", previous)
                }
            }
        }

    @Query("UPDATE sync_state SET hlc = max(hlc, :remote) WHERE id = 0")
    abstract suspend fun raiseHlc(remote: Long)

    @Query("UPDATE sync_state SET hlc = min(hlc, :max) WHERE id = 0")
    abstract suspend fun clampHlc(max: Long)

    @Query("UPDATE sync_state SET clockOffsetMs = :ms WHERE id = 0")
    abstract suspend fun setClockOffset(ms: Long)

    /**
     * Empties the four `sync_*` tables and resets `sync_state` except `serverUrl`, in one
     * transaction (02 Sync tables); the library is untouched.
     *
     * Relink contract: the first pull after a re-link must run to completion *before* capture is
     * re-enabled, and the applier must re-seed `sync_clock` from server records — otherwise a fresh
     * wall-clock HLC beats the server's stored clocks and LWW-clobbers peer changes made while this
     * device was unlinked.
     */
    suspend fun unlink() {
        db.withWriteTransaction {
            deleteOutbox()
            deleteClocks()
            deleteParked()
            deleteHeld()
            resetState()
        }
    }

    @Query("DELETE FROM sync_outbox")
    protected abstract suspend fun deleteOutbox()

    @Query("DELETE FROM sync_clock")
    protected abstract suspend fun deleteClocks()

    @Query("DELETE FROM sync_parked")
    protected abstract suspend fun deleteParked()

    @Query("DELETE FROM sync_held")
    protected abstract suspend fun deleteHeld()

    @Query(
        "UPDATE sync_state SET enabled = 0, applying = 0, accountId = NULL, deviceId = NULL," +
            " cursor = NULL, hlc = 0, nodeId = NULL, clockOffsetMs = 0, protocol = NULL," +
            " linkedAt = NULL, lastSyncAt = NULL, lastError = NULL WHERE id = 0",
    )
    protected abstract suspend fun resetState()

    /** True while [withApplying] runs — tests' "capture suppressed" probe. */
    suspend fun isApplying(): Boolean = get()?.applying == true

}

/**
 * `sync_outbox` (02): one coalesced row per `(coll, rid, field)`. Primitive payloads keep the
 * protocol dependency out of Room; `:sync:impl` owns the JSON shapes.
 */
@Dao
abstract class SyncOutboxDao(
    private val db: NeutrodyneDatabase,
) {
    @Upsert
    abstract suspend fun upsert(row: SyncOutboxEntity)

    /** Push order: `(hlc, nodeId)` (02 sync_outbox index). */
    @Query("SELECT * FROM sync_outbox ORDER BY hlc, nodeId, coll, rid, field")
    abstract suspend fun rows(): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    abstract suspend fun count(): Int

    @Query("SELECT * FROM sync_outbox WHERE coll = :coll AND rid = :rid AND field = :field")
    abstract suspend fun get(
        coll: String,
        rid: String,
        field: String,
    ): SyncOutboxEntity?

    @Query("SELECT * FROM sync_outbox WHERE coll = :coll AND rid IN (:rids)")
    abstract suspend fun rowsFor(
        coll: String,
        rids: List<String>,
    ): List<SyncOutboxEntity>

    /** A literal capture (local merges, "Keep mine"): always captured, even while applying. */
    suspend fun captureLiteral(
        coll: String,
        rid: String,
        field: String,
        json: String,
    ) {
        capture(coll, rid, field, json, guarded = false)
    }

    /**
     * An intent capture (the explicit mark-unplayed and position-reset chains): guarded like the
     * triggers, so it never echoes a remote derivation.
     */
    suspend fun captureIntent(
        coll: String,
        rid: String,
        field: String,
        json: String,
    ) {
        capture(coll, rid, field, json, guarded = true)
    }

    /** A `*` row: every field of the record with this clock. */
    suspend fun captureAll(
        coll: String,
        rid: String,
    ) {
        capture(coll, rid, "*", null, guarded = false)
    }

    private suspend fun capture(
        coll: String,
        rid: String,
        field: String,
        literal: String?,
        guarded: Boolean,
    ) {
        db.syncWriter { connection ->
            val predicate = if (guarded) "enabled = 1 AND applying = 0" else "enabled = 1"
            connection.execute(SyncTriggers.SYNC_TICK + " AND $predicate")
            connection.execute(
                "INSERT OR REPLACE INTO sync_outbox(coll, rid, field, hlc, nodeId, captureKind, value)" +
                    " SELECT ?, ?, ?, hlc, nodeId, 'LOCAL', ? FROM sync_state WHERE id = 0 AND $predicate",
                coll,
                rid,
                field,
                literal,
            )
        }
    }

    /**
     * A historical stamp for the first link and a backup restore: `atMs << 16` on this node, written
     * as "insert, or raise only if older", so an older restored stamp never replaces a newer pending
     * change. [literal] freezes the value for played, position, session and setting fields.
     */
    suspend fun captureAt(
        coll: String,
        rid: String,
        field: String,
        atMs: Long,
        literal: String? = null,
    ) {
        db.syncWriter { connection ->
            if (connection.scalar("SELECT enabled FROM sync_state WHERE id = 0") == 0L) return@syncWriter
            val now = connection.scalar("SELECT " + SyncTriggers.SYNC_WALL_MS)
            val stamp =
                atMs.coerceIn(SyncTriggers.MIN_CAPTURE_MS, maxOf(SyncTriggers.MIN_CAPTURE_MS, now)) shl
                    SyncTriggers.HLC_COUNTER_BITS
            val node = checkNotNull(db.syncStateDao().get()?.nodeId)
            putIfNewer(SyncOutboxEntity(coll, rid, field, stamp, node, value = literal))
        }
    }

    /** Full resync: an accepted value and its complete original clock, counter and origin node. */
    suspend fun captureStored(
        coll: String,
        rid: String,
        field: String,
        hlc: Long,
        nodeId: String,
        literal: String,
    ) {
        db.syncWriter { connection ->
            if (connection.scalar("SELECT enabled FROM sync_state WHERE id = 0") == 0L) return@syncWriter
            putIfNewer(SyncOutboxEntity(coll, rid, field, hlc, nodeId, SyncCaptureKind.REPLAY, literal))
        }
    }

    /** Deletes a row only when its complete clock is at most the acknowledged one. */
    suspend fun deleteAcked(
        coll: String,
        rid: String,
        field: String,
        hlc: Long,
        nodeId: String,
    ) {
        db.syncWriter { connection ->
            connection.execute(
                "DELETE FROM sync_outbox WHERE coll = ? AND rid = ? AND field = ?" +
                    " AND (hlc < ? OR (hlc = ? AND nodeId <= ?))",
                coll,
                rid,
                field,
                hlc,
                hlc,
                nodeId,
            )
        }
    }

    /** Deletes one field row regardless of its clock (a rekey marker). */
    suspend fun deleteField(
        coll: String,
        rid: String,
        field: String,
    ) {
        db.syncWriter { connection ->
            connection.execute("DELETE FROM sync_outbox WHERE coll = ? AND rid = ? AND field = ?", coll, rid, field)
        }
    }

    suspend fun deleteIfUnchanged(
        coll: String,
        rid: String,
        field: String,
        hlc: Long,
        nodeId: String,
    ) {
        db.syncWriter { connection ->
            connection.execute(
                "DELETE FROM sync_outbox WHERE coll = ? AND rid = ? AND field = ? AND hlc = ? AND nodeId = ?",
                coll,
                rid,
                field,
                hlc,
                nodeId,
            )
        }
    }

    /** The caller first seeds the singleton from corrected time and the greatest accepted clock. */
    suspend fun restampAbove(
        max: Long,
        localNodeId: String,
    ) {
        db.syncWriter { connection ->
            if (connection.scalar("SELECT enabled FROM sync_state WHERE id = 0") == 0L) return@syncWriter
            val eligible =
                rows().filter {
                    it.captureKind == SyncCaptureKind.LOCAL && it.nodeId == localNodeId && it.hlc > max
                }
            for (group in eligible.groupBy { it.hlc }.values) {
                connection.execute(SyncTriggers.SYNC_TICK + " AND enabled = 1")
                val stamp = connection.scalar("SELECT hlc FROM sync_state WHERE id = 0")
                group.forEach { upsert(it.copy(hlc = stamp)) }
            }
        }
    }

    /** Moves one record's rows to another `rid`, keeping the larger complete clock on a collision. */
    suspend fun moveRecord(
        coll: String,
        from: String,
        to: String,
    ) {
        db.syncWriter { connection ->
            if (from == to) return@syncWriter
            val source = rowsFor(coll, listOf(from))
            // Check every collision before changing anything, even if a caller catches failure.
            source.forEach { row ->
                val target = get(coll, to, row.field)
                require(target == null || compareClock(row, target) != 0 || row.value == target.value) {
                    "Conflicting payloads at the same complete clock"
                }
            }
            source.forEach { putIfNewer(it.copy(rid = to)) }
            connection.execute("DELETE FROM sync_outbox WHERE coll = ? AND rid = ?", coll, from)
        }
    }

    private suspend fun putIfNewer(row: SyncOutboxEntity) {
        val old = get(row.coll, row.rid, row.field)
        if (old == null || compareClock(row, old) > 0) {
            upsert(row)
            return
        }
        require(compareClock(row, old) != 0 || row.value == old.value) {
            "Conflicting payloads at the same complete clock"
        }
    }
}

private fun compareClock(
    a: SyncOutboxEntity,
    b: SyncOutboxEntity,
): Int = a.hlc.compareTo(b.hlc).takeIf { it != 0 } ?: a.nodeId.compareTo(b.nodeId)

private suspend fun <T> NeutrodyneDatabase.syncWriter(block: suspend (PooledConnection) -> T): T =
    useWriterConnection { connection ->
        check(connection.inTransaction()) { "Sync bookkeeping requires an active writer transaction" }
        block(connection)
    }

private suspend fun PooledConnection.execute(
    sql: String,
    vararg values: Any?,
) {
    usePrepared(sql) { statement ->
        values.forEachIndexed { i, value ->
            when (value) {
                null -> statement.bindNull(i + 1)
                is String -> statement.bindText(i + 1, value)
                is Long -> statement.bindLong(i + 1, value)
                else -> error("Unsupported SQLite binding")
            }
        }
        statement.step()
    }
}

private suspend fun PooledConnection.scalar(sql: String): Long =
    usePrepared(sql) {
        check(it.step()) { "Missing sync singleton" }
        it.getLong(0)
    }

/** `sync_clock` (02): one row per synced record; `clocks` is opaque JSON owned by 10. */
@Dao
abstract class SyncClockDao(
    private val db: NeutrodyneDatabase,
) {
    @Upsert
    abstract suspend fun upsert(row: SyncClockEntity)

    @Query("SELECT * FROM sync_clock WHERE coll = :coll AND rid = :rid")
    abstract suspend fun get(
        coll: String,
        rid: String,
    ): SyncClockEntity?

    @Query("SELECT COUNT(*) FROM sync_clock")
    abstract suspend fun count(): Int

    /** The sync adapter merges raw values and pending effects; SQL never interprets clocks. */
    suspend fun moveRecord(
        coll: String,
        from: String,
        to: String,
        mergedClocks: String,
    ) {
        db.syncWriter { connection ->
            upsert(SyncClockEntity(coll, to, mergedClocks))
            if (from != to) {
                connection.execute("DELETE FROM sync_clock WHERE coll = ? AND rid = ?", coll, from)
            }
        }
    }

    /** 02: retention drops the deleted episodes' `episode`/`upnext` clock rows. */
    @Query("DELETE FROM sync_clock WHERE coll = :coll AND rid IN (:rids)")
    abstract suspend fun deleteRids(
        coll: String,
        rids: List<String>,
    ): Int
}

/** `sync_parked` (02): received records awaiting their podcast or episode. */
@Dao
interface SyncParkedDao {
    @Insert
    suspend fun park(row: SyncParkedEntity): Long

    /** 10's `SyncParkedStateApplier` load after each ingest. */
    @Query("SELECT * FROM sync_parked WHERE podcastSyncId = :podcastSyncId")
    suspend fun forPodcast(podcastSyncId: String): List<SyncParkedEntity>

    /** Applied rows leave the table (10). */
    @Query("DELETE FROM sync_parked WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>): Int

    /** 02 maintenance step 3a: rows parked longer than 180 days are dropped. */
    @Query("DELETE FROM sync_parked WHERE receivedAt < :before")
    suspend fun expire(before: Long): Int

    @Query("SELECT COUNT(*) FROM sync_parked")
    suspend fun count(): Int
}

/** `sync_held` (02): removals staged or held by 10's mass-change guard. */
@Dao
interface SyncHeldDao {
    @Insert
    suspend fun insert(row: SyncHeldEntity): Long

    @Upsert
    suspend fun upsert(row: SyncHeldEntity)

    /** 10 observes the table for the held-changes prompt (low churn, never joined). */
    @Query("SELECT * FROM sync_held ORDER BY heldAt, id")
    fun observeAll(): Flow<List<SyncHeldEntity>>

    @Query("SELECT * FROM sync_held ORDER BY heldAt, id")
    suspend fun all(): List<SyncHeldEntity>

    @Query("SELECT * FROM sync_held WHERE heldAt = :heldAt ORDER BY id")
    suspend fun forRound(heldAt: Long): List<SyncHeldEntity>

    @Query("SELECT * FROM sync_held WHERE id = :id")
    suspend fun byId(id: Long): SyncHeldEntity?

    @Query("DELETE FROM sync_held WHERE heldAt = :heldAt")
    suspend fun deleteRound(heldAt: Long)

    @Query("DELETE FROM sync_held WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM sync_held")
    suspend fun count(): Int
}
