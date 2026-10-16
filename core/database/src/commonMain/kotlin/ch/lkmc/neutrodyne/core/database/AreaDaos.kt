// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import kotlinx.coroutines.flow.Flow

// The remaining one-DAO-per-area surface (02 "New names introduced here"). M1a adds only the row
// writers and lookups its own paths and fixtures need; each later milestone extends its DAO.

/** `podcast_group` + memberships (M2: lists, mosaics, ordering). */
@Dao
interface GroupDao {
    @Insert
    suspend fun insert(row: PodcastGroupEntity): Long

    @Query(
        "SELECT g.*, (SELECT COUNT(*) FROM podcast_group_member m WHERE m.groupId = g.id) AS memberCount" +
            " FROM podcast_group g ORDER BY g.orderKey, g.id",
    )
    fun observeGroups(): Flow<List<GroupRow>>

    @Query(
        "SELECT g.*, (SELECT COUNT(*) FROM podcast_group_member m WHERE m.groupId = g.id) AS memberCount" +
            " FROM podcast_group g WHERE g.id = :groupId",
    )
    fun observeGroup(groupId: Long): Flow<GroupRow?>

    @Query(
        "SELECT g.*, (SELECT COUNT(*) FROM podcast_group_member m WHERE m.groupId = g.id) AS memberCount" +
            " FROM podcast_group g JOIN podcast_group_member own ON own.groupId = g.id" +
            " WHERE own.podcastId = :podcastId ORDER BY g.orderKey, g.id",
    )
    fun observeGroupsOf(podcastId: Long): Flow<List<GroupRow>>

    @Query("SELECT podcastId FROM podcast_group_member WHERE groupId = :groupId ORDER BY orderKey, podcastId")
    fun observeMemberIds(groupId: Long): Flow<List<Long>>

    @Query("SELECT * FROM podcast_group WHERE nameKey = :nameKey")
    suspend fun groupByNameKey(nameKey: String): PodcastGroupEntity?

    @Query("SELECT orderKey FROM podcast_group ORDER BY orderKey DESC, id DESC LIMIT 1")
    suspend fun lastGroupOrderKey(): String?

    @Query("SELECT colorArgb FROM podcast_group")
    suspend fun groupColors(): List<Int?>

    @Query(
        "UPDATE podcast_group SET name = :name, nameKey = :nameKey, updatedAt = :now WHERE id = :groupId" +
            " AND (name <> :name OR nameKey <> :nameKey)",
    )
    suspend fun rename(
        groupId: Long,
        name: String,
        nameKey: String,
        now: Long,
    )

    @Query(
        "UPDATE podcast_group SET colorArgb = :colorArgb, iconKey = :iconKey, updatedAt = :now WHERE id = :groupId" +
            " AND (colorArgb IS NOT :colorArgb OR iconKey IS NOT :iconKey)",
    )
    suspend fun setLook(
        groupId: Long,
        colorArgb: Int?,
        iconKey: String?,
        now: Long,
    )

    @Query(
        "UPDATE podcast_group SET feedOrder = :feedOrder, playOrder = :playOrder, updatedAt = :now" +
            " WHERE id = :groupId" +
            " AND (feedOrder <> :feedOrder OR playOrder <> :playOrder)",
    )
    suspend fun setOrders(
        groupId: Long,
        feedOrder: FeedOrder,
        playOrder: FeedOrder,
        now: Long,
    )

    @Query(
        "UPDATE podcast_group SET filterFlags = :filterFlags, mediaFilter = :mediaFilter," +
            " hideOlderThanDays = :hideOlderThanDays, updatedAt = :now WHERE id = :groupId" +
            " AND (filterFlags <> :filterFlags OR mediaFilter <> :mediaFilter" +
            " OR hideOlderThanDays IS NOT :hideOlderThanDays)",
    )
    suspend fun setView(
        groupId: Long,
        filterFlags: Int,
        mediaFilter: MediaFilter,
        hideOlderThanDays: Int?,
        now: Long,
    )

    @Query("UPDATE podcast_group SET showAsTab = :show, updatedAt = :now WHERE id = :groupId AND showAsTab <> :show")
    suspend fun setShowAsTab(
        groupId: Long,
        show: Boolean,
        now: Long,
    )

    /**
     * `orderKey` is supplied on every insert — `OrderKey.after(last)` in file/order (02).
     * `INSERT OR IGNORE` because subscribe re-inserts an existing membership harmlessly (03).
     */
    @Insert(onConflict = androidx.room3.OnConflictStrategy.IGNORE)
    suspend fun insertMember(row: PodcastGroupMemberEntity)

    @Query("SELECT * FROM podcast_group WHERE id = :id")
    suspend fun groupById(id: Long): PodcastGroupEntity?

    @Query("SELECT * FROM podcast_group_member WHERE groupId = :groupId ORDER BY orderKey, podcastId")
    suspend fun membersOf(groupId: Long): List<PodcastGroupMemberEntity>

    /** The last member key of one group — `OrderKey.after(last)`'s input (03 Subscribe transaction). */
    @Query(
        "SELECT orderKey FROM podcast_group_member WHERE groupId = :groupId" +
            " ORDER BY orderKey DESC, podcastId DESC LIMIT 1",
    )
    suspend fun lastMemberOrderKey(groupId: Long): String?

    /** The group's feed order (05 `FeedRepository.setFeedOrder`, `FeedSource.Group`). */
    @Query("UPDATE podcast_group SET feedOrder = :order WHERE id = :groupId")
    suspend fun setFeedOrder(
        groupId: Long,
        order: FeedOrder,
    )
}

/** Group and membership count in one Room read, so edits and membership changes invalidate together. */
data class GroupRow(
    @Embedded val group: PodcastGroupEntity,
    val memberCount: Int,
)

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

    /** The mark-played chain's bulk variant (02 User-state writes): creates missing rows first. */
    @Query(
        "INSERT OR IGNORE INTO episode_state(episodeId, playCount, isFavorite, updatedAt)" +
            " SELECT id, 0, 0, :now FROM episode WHERE id IN (:ids)",
    )
    suspend fun ensureAll(
        ids: List<Long>,
        now: Long,
    )

    /** Every path to "played" (02): `playCount` counts transitions, `startedAt` clears. */
    @Query(
        "UPDATE episode_state SET playedAt = :now, playCount = playCount + 1, startedAt = NULL," +
            " updatedAt = :now WHERE episodeId IN (:ids) AND playedAt IS NULL",
    )
    suspend fun markPlayed(
        ids: List<Long>,
        now: Long,
    ): Int

    /** Fully unplayed (02, 06): clears both marks. */
    @Query(
        "UPDATE episode_state SET playedAt = NULL, startedAt = NULL, updatedAt = :now" +
            " WHERE episodeId IN (:ids) AND (playedAt IS NOT NULL OR startedAt IS NOT NULL)",
    )
    suspend fun markUnplayed(
        ids: List<Long>,
        now: Long,
    ): Int

    /** Caller must [ensure] a state row first (like the mark-played chain) — else this updates nothing. */
    @Query(
        "UPDATE episode_state SET isFavorite = :favorite, updatedAt = :now" +
            " WHERE episodeId = :id AND isFavorite <> :favorite",
    )
    suspend fun setFavorite(
        id: Long,
        favorite: Boolean,
        now: Long,
    )

    @Upsert
    suspend fun upsert(row: EpisodeStateEntity)

    @Query("SELECT * FROM episode_state WHERE episodeId = :episodeId")
    suspend fun byEpisode(episodeId: Long): EpisodeStateEntity?

    /**
     * 02 Live row state: the `episode_state` `IN` query behind `EpisodeLiveStateSource`; callers
     * chunk at 200 ids. `playedAt`/`startedAt`/`isFavorite` travel along but stay paged fields.
     */
    @Query(
        "SELECT episodeId, playedAt, startedAt, isFavorite, measuredDurationMs" +
            " FROM episode_state WHERE episodeId IN (:ids)",
    )
    fun observeFor(ids: List<Long>): Flow<List<EpisodeStateLiveRow>>
}

/** `episode_position` (M4: position saves and `applyRemote`). */
@Dao
interface PositionDao {
    @Upsert
    suspend fun upsert(row: EpisodePositionEntity)

    /** Only for explicit reset and the mark-played/mark-unplayed chains (02). */
    @Query("UPDATE episode_position SET positionMs = 0, updatedAt = :now WHERE episodeId IN (:ids) AND positionMs <> 0")
    suspend fun reset(
        ids: List<Long>,
        now: Long,
    ): Int

    @Query("SELECT * FROM episode_position WHERE episodeId = :episodeId")
    suspend fun byEpisode(episodeId: Long): EpisodePositionEntity?

    /**
     * 02 Live row state: the high-churn `episode_position` `IN` query; callers chunk at 200 ids.
     * Observes only `episode_position`, so position saves never invalidate a paged feed.
     */
    @Query(
        "SELECT episodeId, positionMs, durationMs, positionSource, updatedAt" +
            " FROM episode_position WHERE episodeId IN (:ids)",
    )
    fun observeFor(ids: List<Long>): Flow<List<EpisodePositionEntity>>
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

    /** The mark-played chain's Up-next removal (02 User-state writes). */
    @Query("DELETE FROM queue_entry WHERE episodeId IN (:ids)")
    suspend fun removeEpisodes(ids: List<Long>)
}

/** `download` (M6: the claim/state machine of 07). */
@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(row: DownloadEntity)

    @Query("SELECT * FROM download WHERE episodeId = :episodeId")
    suspend fun byEpisode(episodeId: Long): DownloadEntity?

    /** 07's file-deletion set of an unsubscribe/merge (03 PodcastRepository). */
    @Query(
        "SELECT d.episodeId FROM download d JOIN episode e ON e.id = d.episodeId" +
            " WHERE e.podcastId IN (:podcastIds)",
    )
    suspend fun downloadedEpisodeIds(podcastIds: List<Long>): List<Long>

    /**
     * 02 Live row state: the `download` transition columns `RowLive` merges; callers chunk at
     * 200 ids. `downloadedBytes` is persisted only on transitions (D17), never progress cadence.
     */
    @Query(
        "SELECT episodeId, state, waitReason, downloadedBytes, totalBytes, estimatedBytes," +
            " nextAttemptAt, lastError FROM download WHERE episodeId IN (:ids)",
    )
    fun observeLiveFor(ids: List<Long>): Flow<List<DownloadLiveRow>>
}

/** `artwork` (M4: references and garbage collection; `@Upsert`-able single-writer table). */
@Dao
interface ArtworkDao {
    /** Pin requests must not overwrite metadata or the descriptor of existing stored bytes. */
    @Insert(onConflict = androidx.room3.OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: ArtworkEntity)

    @Upsert
    suspend fun upsert(row: ArtworkEntity)

    @Query("SELECT * FROM artwork WHERE `key` = :key")
    suspend fun byKey(key: String): ArtworkEntity?

    /** The index load of 08's `ArtworkStore` (key → localPath for every pinned file). */
    @Query("SELECT `key`, localPath FROM artwork WHERE localPath IS NOT NULL")
    suspend fun pinnedRows(): List<ArtworkPinRow>
}

/** `key` → `localPath` projection for [ArtworkDao.pinnedRows]. */
data class ArtworkPinRow(
    val key: String,
    val localPath: String,
)

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
