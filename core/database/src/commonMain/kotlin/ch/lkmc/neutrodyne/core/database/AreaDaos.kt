// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

// The remaining one-DAO-per-area surface (02 "New names introduced here"). M1a adds only the row
// writers and lookups its own paths and fixtures need; each later milestone extends its DAO.

/** `podcast_group` + memberships (M2: lists, mosaics, ordering). */
@Dao
interface GroupDao {
    @Insert
    suspend fun insert(row: PodcastGroupEntity): Long

    /** `orderKey` is supplied on every insert — `OrderKey.after(last)` in file/order (02). */
    @Insert
    suspend fun insertMember(row: PodcastGroupMemberEntity)

    @Query("SELECT * FROM podcast_group WHERE id = :id")
    suspend fun groupById(id: Long): PodcastGroupEntity?

    @Query("SELECT * FROM podcast_group_member WHERE groupId = :groupId ORDER BY orderKey, podcastId")
    suspend fun membersOf(groupId: Long): List<PodcastGroupMemberEntity>
}

/** `podcast_settings` + `podcast_group_settings` (M2: resolution reads; deletes on all-null). */
@Dao
interface ScopeSettingsDao {
    @Upsert
    suspend fun upsertPodcast(row: PodcastSettingsEntity)

    @Upsert
    suspend fun upsertGroup(row: PodcastGroupSettingsEntity)

    @Query("SELECT * FROM podcast_settings WHERE podcastId = :podcastId")
    suspend fun forPodcast(podcastId: Long): PodcastSettingsEntity?

    @Query("SELECT * FROM podcast_group_settings WHERE groupId = :groupId")
    suspend fun forGroup(groupId: Long): PodcastGroupSettingsEntity?
}

/** `episode_state` (M4: the user-state write chains of 02 User-state writes). */
@Dao
interface EpisodeStateDao {
    /** Lazy-row primitive (02 DAO rule 2): `INSERT OR IGNORE` then targeted UPDATEs by writers. */
    @Insert(onConflict = androidx.room3.OnConflictStrategy.IGNORE)
    suspend fun ensure(row: EpisodeStateEntity): Long

    @Upsert
    suspend fun upsert(row: EpisodeStateEntity)

    @Query("SELECT * FROM episode_state WHERE episodeId = :episodeId")
    suspend fun byEpisode(episodeId: Long): EpisodeStateEntity?
}

/** `episode_position` (M4: position saves and `applyRemote`). */
@Dao
interface PositionDao {
    @Upsert
    suspend fun upsert(row: EpisodePositionEntity)

    @Query("SELECT * FROM episode_position WHERE episodeId = :episodeId")
    suspend fun byEpisode(episodeId: Long): EpisodePositionEntity?
}

/** `queue_entry` (M4: Up next ordering, adds and moves). */
@Dao
interface QueueDao {
    @Insert(onConflict = androidx.room3.OnConflictStrategy.IGNORE)
    suspend fun insert(row: QueueEntryEntity): Long

    @Query("SELECT * FROM queue_entry ORDER BY orderKey, id")
    suspend fun entries(): List<QueueEntryEntity>

    @Query("SELECT orderKey FROM queue_entry ORDER BY orderKey DESC, id DESC LIMIT 1")
    suspend fun lastOrderKey(): String?
}

/** `download` (M6: the claim/state machine of 07). */
@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(row: DownloadEntity)

    @Query("SELECT * FROM download WHERE episodeId = :episodeId")
    suspend fun byEpisode(episodeId: Long): DownloadEntity?
}

/** `artwork` (M4: references and garbage collection; `@Upsert`-able single-writer table). */
@Dao
interface ArtworkDao {
    @Upsert
    suspend fun upsert(row: ArtworkEntity)

    @Query("SELECT * FROM artwork WHERE `key` = :key")
    suspend fun byKey(key: String): ArtworkEntity?
}

/** `import_session`/`import_item` (M3: the OPML import pipeline). */
@Dao
interface ImportDao {
    @Insert
    suspend fun insertSession(row: ImportSessionEntity): Long

    @Insert
    suspend fun insertItems(rows: List<ImportItemEntity>)

    @Query("SELECT * FROM import_item WHERE sessionId = :sessionId ORDER BY ordinal")
    suspend fun itemsOf(sessionId: Long): List<ImportItemEntity>
}

/** `BackupDao` (M3: `observeLibraryShape`, the backup export reads). */
@Dao
interface BackupDao

/** `MaintenanceDao` (M11b: the `db-maintenance` lane's sweeps and `PRAGMA`s). */
@Dao
interface MaintenanceDao
