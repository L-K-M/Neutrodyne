// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.PooledConnection
import androidx.room3.Query
import androidx.room3.Upsert
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Singleton link state. Never observed by list queries or flows. */
@Dao
abstract class SyncStateDao(
    private val db: NeutrodyneDatabase,
) {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    abstract suspend fun get(): SyncStateEntity?

    /** MS0's link/first-link writes and tests' linked fixtures — the only row writer for now. */
    @Upsert
    abstract suspend fun upsert(row: SyncStateEntity)

    /** The caller owns the writer transaction, including cancellation and rollback. */
    suspend fun <T> withApplying(block: suspend () -> T): T =
        db.syncWriter { connection ->
            val previous = connection.scalar("SELECT applying FROM sync_state WHERE id = 0")
            connection.execute("UPDATE sync_state SET applying = 1 WHERE id = 0")
            try {
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
     * Relink contract for MS0: the first pull after a re-link must run to completion *before*
     * capture is re-enabled, and the applier must re-seed `sync_clock` from server records —
     * otherwise a fresh wall-clock HLC beats the server's stored clocks and LWW-clobbers peer
     * changes made while this device was unlinked.
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
}

/** Coalescing capture bookkeeping. Primitive payloads keep the protocol dependency out of Room. */
@Dao
abstract class SyncOutboxDao(
    private val db: NeutrodyneDatabase,
) {
    @Upsert
    abstract suspend fun upsert(row: SyncOutboxEntity)

    @Query("SELECT * FROM sync_outbox ORDER BY hlc, nodeId, coll, rid, field")
    abstract suspend fun rows(): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    abstract suspend fun count(): Int

    suspend fun captureLiteral(
        coll: String,
        rid: String,
        field: String,
        json: String,
    ) {
        capture(coll, rid, field, json, CaptureGuard.EXPLICIT)
    }

    suspend fun captureIntent(
        coll: String,
        rid: String,
        field: String,
        json: String,
    ) {
        capture(coll, rid, field, json, CaptureGuard.INTENT)
    }

    suspend fun captureAll(
        coll: String,
        rid: String,
    ) {
        capture(coll, rid, "*", null, CaptureGuard.EXPLICIT)
    }

    private suspend fun capture(
        coll: String,
        rid: String,
        field: String,
        literal: String?,
        guard: CaptureGuard,
    ) {
        db.syncWriter { connection ->
            val predicate = "enabled = 1" + if (guard == CaptureGuard.INTENT) " AND applying = 0" else ""
            connection.execute(SYNC_TICK + " AND $predicate")
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

    suspend fun captureAt(
        coll: String,
        rid: String,
        field: String,
        atMs: Long,
        literal: String? = null,
    ) {
        db.syncWriter { connection ->
            if (connection.scalar("SELECT enabled FROM sync_state WHERE id = 0") == 0L) return@syncWriter
            val now = connection.scalar("SELECT " + SYNC_WALL_MS)
            val stamp = atMs.coerceIn(MIN_CAPTURE_MS, maxOf(MIN_CAPTURE_MS, now)) shl HLC_COUNTER_BITS
            val node = checkNotNull(db.syncStateDao().get()?.nodeId)
            putIfNewer(SyncOutboxEntity(coll, rid, field, stamp, node, value = literal))
        }
    }

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

    @Query("SELECT * FROM sync_outbox WHERE coll = :coll AND rid = :rid AND field = :field")
    abstract suspend fun get(
        coll: String,
        rid: String,
        field: String,
    ): SyncOutboxEntity?

    private suspend fun putIfNewer(row: SyncOutboxEntity) {
        val old = get(row.coll, row.rid, row.field)
        if (old == null || compareClock(row, old) > 0) {
            upsert(row)
            return
        }
        require(
            compareClock(row, old) != 0 || row.value == old.value,
        ) { "Conflicting payloads at the same complete clock" }
    }

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
                    it.captureKind == SyncCaptureKind.LOCAL && it.nodeId == localNodeId &&
                        it.hlc > max
                }
            for (group in eligible.groupBy { it.hlc }.values) {
                connection.execute(SYNC_TICK + " AND enabled = 1")
                val stamp = connection.scalar("SELECT hlc FROM sync_state WHERE id = 0")
                group.forEach { upsert(it.copy(hlc = stamp)) }
            }
        }
    }

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

    @Query("SELECT * FROM sync_outbox WHERE coll = :coll AND rid IN (:rids)")
    abstract suspend fun rowsFor(
        coll: String,
        rids: List<String>,
    ): List<SyncOutboxEntity>

    private enum class CaptureGuard { EXPLICIT, INTENT }
}

private fun compareClock(
    a: SyncOutboxEntity,
    b: SyncOutboxEntity,
): Int = a.hlc.compareTo(b.hlc).takeIf { it != 0 } ?: a.nodeId.compareTo(b.nodeId)

// Shared verbatim with the trigger renderer. SQLite 3.18 has no unixepoch('subsec').
internal const val SYNC_WALL_MS = "CAST(((julianday('now') - 2440587.5) * 86400000) AS INTEGER)"
internal const val SYNC_TICK =
    "UPDATE sync_state SET hlc = max(hlc + 1," +
        " CAST(((julianday('now') - 2440587.5) * 86400000 + clockOffsetMs) AS INTEGER) << 16) WHERE id = 0"
private const val MIN_CAPTURE_MS = 1_577_836_800_000L
private const val HLC_COUNTER_BITS = 16

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
            if (from != to) connection.execute("DELETE FROM sync_clock WHERE coll = ? AND rid = ?", coll, from)
        }
    }
}

/** `sync_parked` (02): received records awaiting their podcast or episode. */
@Dao
interface SyncParkedDao {
    @Insert
    suspend fun park(row: SyncParkedEntity): Long

    /** 10's `SyncParkedStateApplier` load after each ingest. */
    @Query("SELECT * FROM sync_parked WHERE podcastSyncId = :podcastSyncId")
    suspend fun forPodcast(podcastSyncId: String): List<SyncParkedEntity>

    @Query("SELECT COUNT(*) FROM sync_parked")
    suspend fun count(): Int
}

/** `sync_held` (02): removals staged or held by 10's mass-change guard. */
@Dao
interface SyncHeldDao {
    @Insert
    suspend fun insert(row: SyncHeldEntity): Long

    /** 10 observes the table for the held-changes prompt (low churn, never joined). */
    @Query("SELECT * FROM sync_held ORDER BY heldAt, id")
    fun observeAll(): Flow<List<SyncHeldEntity>>

    @Query("SELECT COUNT(*) FROM sync_held")
    suspend fun count(): Int
}
