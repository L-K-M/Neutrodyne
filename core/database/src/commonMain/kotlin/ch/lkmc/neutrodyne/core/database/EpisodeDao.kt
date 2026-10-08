// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import ch.lkmc.neutrodyne.core.model.Availability

/**
 * `episode` row access outside the ingest pipeline (02 Ingestion support): lookups by id and
 * identity key, the restore/stub insert path and `setAvailability` (the only `episode` write
 * besides refresh, restore stubs and retention — 02).
 */
@Dao
interface EpisodeDao {
    /**
     * Restore's stub insertion (05): `inFeed = 0` rows a later refresh matches by `identityKey`.
     * Ingest's own inserts go through `IngestDao.insertEpisodes` (descending `feedOrder` there).
     */
    @Insert
    suspend fun insertStub(row: EpisodeEntity): Long

    @Query("SELECT * FROM episode WHERE id = :id")
    suspend fun byId(id: Long): EpisodeEntity?

    @Query("SELECT * FROM episode WHERE podcastId = :podcastId AND identityKey = :identityKey")
    suspend fun byIdentityKey(
        podcastId: Long,
        identityKey: String,
    ): EpisodeEntity?

    /**
     * 04's `YouTubeAvailabilityRecorder` write (M9a): the value-differs predicate keeps a no-change
     * write from firing Room invalidation (02 DAO rule 2).
     */
    @Query("UPDATE episode SET availability = :availability WHERE id = :id AND availability <> :availability")
    suspend fun setAvailability(
        id: Long,
        availability: Availability,
    )
}
