// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import ch.lkmc.neutrodyne.core.model.Availability
import kotlinx.coroutines.flow.Flow

private const val NOTIFICATION_BIND_CHUNK = 500

/**
 * `episode` row access outside the ingest pipeline (02 Ingestion support): lookups by id and
 * identity key, the restore/stub insert path and `setAvailability` (the only `episode` write
 * besides refresh, restore stubs and retention — 02).
 */
@Dao
interface EpisodeDao {
    /**
     * Restore's stub insertion (05 `Stubs and matching`, 02's `INSERT OR IGNORE`): `inFeed = 0`
     * rows a later refresh matches by `identityKey`. OR IGNORE because a `(podcastId,
     * identityKey)` collision — a repeated line or a row refresh just committed — is not an
     * error: the caller resolves a -1 return by `byIdentityKey` and treats it as matched.
     * Ingest's own inserts go through `IngestDao.insertEpisodes` (descending `feedOrder` there).
     */
    @Insert(onConflict = androidx.room3.OnConflictStrategy.IGNORE)
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

    /** One run-level read, bounded for the SQLite 3.18 fallback's variable limit (02). */
    suspend fun notificationRows(episodeIds: Collection<Long>): List<NewEpisodeRow> =
        episodeIds
            .distinct()
            .chunked(NOTIFICATION_BIND_CHUNK)
            .flatMap { notificationRowsChunk(it) }
            .sortedWith(compareByDescending<NewEpisodeRow> { it.sortDate }.thenByDescending { it.episodeId })

    /** Room primitive; use [notificationRows] to retain global ordering across bind-sized chunks. */
    @Query(
        "SELECT e.id AS episodeId, e.podcastId, e.sortDate, e.title AS episodeTitle," +
            " COALESCE(p.customTitle, p.title) AS podcastTitle," +
            " COALESCE(e.artworkKey, p.artworkKey) AS artworkKey," +
            " g.id AS groupId, g.uuid AS groupUuid, g.name AS groupName, g.orderKey AS groupOrderKey," +
            " gs.notifyNewEpisodes AS groupNotify" +
            " FROM episode e JOIN podcast p ON p.id = e.podcastId" +
            " LEFT JOIN episode_state s ON s.episodeId = e.id" +
            " LEFT JOIN podcast_group_member m ON m.podcastId = p.id" +
            " LEFT JOIN podcast_group g ON g.id = m.groupId" +
            " LEFT JOIN podcast_group_settings gs ON gs.groupId = g.id" +
            " WHERE e.id IN (:episodeIds) AND s.playedAt IS NULL AND " + FeedQueryBuilder.VISIBLE +
            " ORDER BY e.sortDate DESC, e.id DESC, g.orderKey, g.id",
    )
    suspend fun notificationRowsChunk(episodeIds: Collection<Long>): List<NewEpisodeRow>

    // --- Episode detail and show notes (03, added 2026-10-07) -------------------------------------

    /** `EpisodeRepository.observeEpisode` (03): the episode detail screen's joined row. */
    @Query(
        "SELECT e.id, e.podcastId, COALESCE(p.customTitle, p.title) AS podcastTitle, e.title," +
            " e.pubDate, COALESCE(s.measuredDurationMs, e.durationMs) AS durationMs," +
            " COALESCE(e.artworkKey, p.artworkKey) AS artworkKey," +
            " COALESCE(e.imageUrl, p.artworkUrl) AS artworkUrl," +
            " COALESCE(a.version, 0) AS artworkVersion," +
            " e.isVideo, p.sourceType, e.externalMediaId, e.availability, e.episodeDisplay, e.link," +
            " s.playedAt, COALESCE(s.isFavorite, 0) AS isFavorite, d.state AS downloadState," +
            " p.feedUrl" +
            " FROM episode e JOIN podcast p ON p.id = e.podcastId" +
            " LEFT JOIN episode_state s ON s.episodeId = e.id" +
            " LEFT JOIN download d ON d.episodeId = e.id" +
            " LEFT JOIN artwork a ON a.key = COALESCE(e.artworkKey, p.artworkKey)" +
            " WHERE e.id = :id",
    )
    fun observeDetail(id: Long): Flow<EpisodeDetailRow?>

    /** The stored description blob (`EpisodeDescriptionCodec` decodes it in `:core:data`). */
    @Query("SELECT html FROM episode_description WHERE episodeId = :episodeId")
    fun observeDescription(episodeId: Long): Flow<ByteArray?>

    /**
     * `markFeedPlayed`'s id selection (02 Bulk "Mark all as played"): one-shot query built by
     * `FeedQueryBuilder.unplayedIds` so confirmation count and marked rows share a predicate.
     */
    @RawQuery(observedEntities = [EpisodeEntity::class, PodcastEntity::class, EpisodeStateEntity::class])
    suspend fun unplayedIds(query: RoomRawQuery): List<Long>
}

/** One visible, unplayed episode per membership (or one null group for an ungrouped podcast). */
data class NewEpisodeRow(
    val episodeId: Long,
    val podcastId: Long,
    val sortDate: Long,
    val episodeTitle: String,
    val podcastTitle: String,
    val artworkKey: String?,
    val groupId: Long?,
    val groupUuid: String?,
    val groupName: String?,
    val groupOrderKey: String?,
    val groupNotify: Boolean?,
)
