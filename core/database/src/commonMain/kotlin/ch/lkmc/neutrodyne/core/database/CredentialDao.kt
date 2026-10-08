// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * `credential` (02 credential): the in-memory `SecretStore` map of 03 is fed by [observeAll]; feed
 * credentials are deleted by the unsubscribe cascade and the maintenance sweep, never by callers.
 */
@Dao
interface CredentialDao {
    @Insert
    suspend fun insert(row: CredentialEntity): Long

    /** Feeds 03's in-memory `SecretStore` map — a cascade-deleted row also leaves the map (02). */
    @Query("SELECT * FROM credential")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credential WHERE origin = :origin")
    suspend fun byOrigin(origin: String): List<CredentialEntity>

    /**
     * 03 `SecretStore.remove` — e.g. the `sync:<host>` token on unlink (10). Feed credentials go
     * through the unsubscribe cascade's last-reference delete instead.
     */
    @Query("DELETE FROM credential WHERE id = :id")
    suspend fun delete(id: Long)
}
