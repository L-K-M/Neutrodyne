// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import androidx.room3.withWriteTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * `sync_state` singleton access (02 Sync bookkeeping). The capture-side methods
 * (`withApplying`, `enable`, `link`) land with MS0; [unlink] is complete now. `sync_state` is
 * never observed by a `Flow` (the triggers write it on every capture, 02).
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

    /**
     * 02's applying protocol: sets `applying = 1` for [block]'s duration and always clears it
     * afterwards (the crash case is covered by the open callback's reset). MS0's capture
     * triggers skip writes made under the flag; nothing is captured while they do not exist
     * yet, so [block] must itself guarantee "no outbox rows" — as retention does.
     */
    suspend fun <T> withApplying(block: suspend () -> T): T {
        try {
            // A cancellation can land after this UPDATE commits but before the block starts;
            // keeping it inside the try makes the finally clear the flag in that window too.
            setApplying(true)
            return block()
        } finally {
            // The suspend DAO call would be skipped on a cancelled caller; the open
            // callback's reset only covers a crash, so suppression must survive here.
            withContext(NonCancellable) { setApplying(false) }
        }
    }

    /** True while [withApplying] runs — tests' "capture suppressed" probe. */
    suspend fun isApplying(): Boolean = get()?.applying == true

    @Query("UPDATE sync_state SET applying = :value WHERE id = 0")
    protected abstract suspend fun setApplying(value: Boolean)
}

/**
 * `sync_outbox` (02): inert until a device links. The Kotlin-side captures of 10
 * (`captureLiteral`/`captureAll`/`captureAt`/`captureIntent`) arrive with MS0; this is the
 * read groundwork the tests and the push order need (per-row ack lands with the push loop).
 */
@Dao
interface SyncOutboxDao {
    @Upsert
    suspend fun upsert(row: SyncOutboxEntity)

    /** Push order: `(hlc, nodeId)` (02 sync_outbox index). */
    @Query("SELECT * FROM sync_outbox ORDER BY hlc, nodeId")
    suspend fun rows(): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int
}

/** `sync_clock` (02): one row per synced record; `clocks` is opaque JSON owned by 10. */
@Dao
interface SyncClockDao {
    @Upsert
    suspend fun upsert(row: SyncClockEntity)

    @Query("SELECT * FROM sync_clock WHERE coll = :coll AND rid = :rid")
    suspend fun get(
        coll: String,
        rid: String,
    ): SyncClockEntity?

    @Query("SELECT COUNT(*) FROM sync_clock")
    suspend fun count(): Int

    /** 02: retention drops the deleted episodes' `episode`/`upnext` clock rows. */
    @Query("DELETE FROM sync_clock WHERE coll = :coll AND rid IN (:rids)")
    suspend fun deleteRids(
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

    /** 10 observes the table for the held-changes prompt (low churn, never joined). */
    @Query("SELECT * FROM sync_held ORDER BY heldAt, id")
    fun observeAll(): Flow<List<SyncHeldEntity>>

    @Query("SELECT COUNT(*) FROM sync_held")
    suspend fun count(): Int
}
