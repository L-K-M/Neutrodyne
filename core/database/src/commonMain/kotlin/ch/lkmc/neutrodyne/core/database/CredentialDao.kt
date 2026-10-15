// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.withWriteTransaction
import kotlinx.coroutines.flow.Flow

/**
 * `credential` (02 credential): the in-memory `SecretStore` map of 03 is fed by [observeAll]; feed
 * credentials are deleted by the unsubscribe cascade and the maintenance sweep, never by callers.
 * An abstract class because [putByOrigin] is a check-then-write inside one
 * `withWriteTransaction` ([db] is the same database instance Room builds this DAO for).
 */
@Dao
abstract class CredentialDao(
    private val db: NeutrodyneDatabase,
) {
    @Insert
    abstract suspend fun insert(row: CredentialEntity): Long

    /** Feeds 03's in-memory `SecretStore` map — a cascade-deleted row also leaves the map (02). */
    @Query("SELECT * FROM credential")
    abstract fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credential WHERE origin = :origin")
    abstract suspend fun byOrigin(origin: String): List<CredentialEntity>

    /**
     * 03 `SecretStore.remove` — e.g. the `sync:<host>` token on unlink (10). Feed credentials go
     * through the unsubscribe cascade's last-reference delete instead.
     */
    @Query("DELETE FROM credential WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /**
     * `SecretStore.put`'s row write (03 "one credential per origin"): `credential.origin` has no
     * unique index, so the replace is a read-then-write inside one transaction that keeps an
     * existing origin's row ID — `podcast.credentialId` references survive it (02 forbids
     * `OnConflictStrategy.REPLACE` on this FK parent table). When several rows carry the origin
     * (a contract violation the schema cannot exclude), the lowest ID wins: it is the row any
     * existing references point at. Returns the row ID.
     */
    suspend fun putByOrigin(row: CredentialEntity): Long =
        db.withWriteTransaction {
            val existing = byOrigin(row.origin).minByOrNull { it.id }
            if (existing == null) {
                insert(row)
            } else {
                update(row.copy(id = existing.id))
                existing.id
            }
        }

    /** How many podcasts reference credential [id] — `SecretStore`'s failed-`put` cleanup (03). */
    @Query("SELECT COUNT(*) FROM podcast WHERE credentialId = :id")
    abstract suspend fun referenceCount(id: Long): Int

    @Query("SELECT * FROM credential WHERE id IN (:ids)")
    abstract suspend fun byIds(ids: List<Long>): List<CredentialEntity>

    /**
     * Key-loss recovery (03 Basic auth and CredentialStore): mark every podcast referencing
     * [rows]' ids `needsCredentials = 1`, then delete those rows. `podcast.credentialId` is an
     * `ON DELETE SET NULL` FK, so the mark must precede the delete inside this one transaction —
     * deleting first would null the reference set before it can be read. The rows are re-read
     * inside the transaction: a row rewritten or deleted since [rows] was observed is left alone
     * (the observed content is stale, not the store's current secret).
     */
    suspend fun dropLostRows(rows: List<CredentialEntity>) {
        if (rows.isEmpty()) return
        db.withWriteTransaction {
            val live = byIds(rows.map { it.id }).associateBy { it.id }
            val unchanged = rows.filter { it == live[it.id] }.map { it.id }
            if (unchanged.isEmpty()) return@withWriteTransaction
            markCredentialsLost(unchanged)
            deleteIds(unchanged)
        }
    }

    @Query("UPDATE podcast SET needsCredentials = 1 WHERE credentialId IN (:ids)")
    protected abstract suspend fun markCredentialsLost(ids: List<Long>)

    @Query("DELETE FROM credential WHERE id IN (:ids)")
    protected abstract suspend fun deleteIds(ids: List<Long>)

    @Update
    protected abstract suspend fun update(row: CredentialEntity)
}
