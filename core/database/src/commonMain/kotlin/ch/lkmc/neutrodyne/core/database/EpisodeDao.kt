// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import ch.lkmc.neutrodyne.core.model.Availability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

private const val NOTIFICATION_BIND_CHUNK = 500

/**
 * The [MediaLookupRow] SELECT list and joins (02 Media lookup, M4): `episode`, `podcast` and the
 * low-churn LEFT joins (`episode_state`, `download`, both artwork lookups), never
 * `episode_position`. M5 adds the batched alternate relation to the projection.
 */
private const val MEDIA_INFO_SELECT =
    "SELECT e.id, e.podcastId, e.title, e.sortDate, e.enclosureUrl, e.enclosureType, e.enclosureLength," +
        " e.externalMediaId, e.isVideo, e.pubDate, e.availability, e.chaptersUrl, e.chaptersType," +
        " COALESCE(s.measuredDurationMs, e.durationMs) AS durationMs, s.measuredDurationMs," +
        " s.playedAt, d.state AS downloadState," +
        " COALESCE(p.customTitle, p.title) AS podcastTitle, p.author, p.sourceType, p.credentialId," +
        " COALESCE(e.artworkKey, p.artworkKey) AS artworkKey," +
        " COALESCE(e.imageUrl, p.artworkUrl) AS artworkUrl," +
        " COALESCE(a.version, 0) AS artworkVersion," +
        " p.artworkKey AS podcastArtworkKey, p.artworkUrl AS podcastArtworkUrl," +
        " COALESCE(pa.version, 0) AS podcastArtworkVersion" +
        " FROM episode e JOIN podcast p ON p.id = e.podcastId" +
        " LEFT JOIN episode_state s ON s.episodeId = e.id" +
        " LEFT JOIN download d ON d.episodeId = e.id" +
        " LEFT JOIN artwork a ON a.key = COALESCE(e.artworkKey, p.artworkKey)" +
        " LEFT JOIN artwork pa ON pa.key = p.artworkKey"

/**
 * `episode` row access outside the ingest pipeline (02 Ingestion support): lookups by id and
 * identity key, the restore/stub insert path and `setAvailability` (the only `episode` write
 * besides refresh, restore stubs and retention — 02).
 */
@Dao
interface EpisodeDao : DownloadSourceQueries {
    override suspend fun downloadSources(ids: List<Long>): List<DownloadSourceRow> =
        ids.distinct().chunked(DOWNLOAD_BIND_CHUNK).flatMap { downloadSourcesChunk(it) }.map { row ->
            val ranked =
                row.audioAlternates
                    .filter { it.type.startsWith("audio/", ignoreCase = true) }
                    .sortedWith(compareByDescending<EpisodeAltEnclosureEntity> { it.isDefault }.thenBy { it.ordinal })
            row.copy(audioAlternates = ranked)
        }

    @Transaction
    @Query(
        "SELECT e.id AS episodeId, e.podcastId, e.title, e.sortDate, e.isVideo, e.availability," +
            " COALESCE(s.measuredDurationMs, e.durationMs) AS durationMs, p.sourceType, p.youtubeChannelId," +
            " COALESCE(p.customTitle, p.title) AS podcastTitle, e.enclosureUrl, e.enclosureType," +
            " e.enclosureLength, e.externalMediaId FROM episode e JOIN podcast p ON p.id = e.podcastId" +
            " LEFT JOIN episode_state s ON s.episodeId = e.id WHERE e.id IN (:ids)",
    )
    suspend fun downloadSourcesChunk(ids: List<Long>): List<DownloadSourceRow>

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

    /** Filter announcements after ingest; hidden Shorts and unavailable rows are never announced. */
    @Query(
        "SELECT e.id FROM episode e JOIN podcast p ON p.id = e.podcastId" +
            " WHERE e.podcastId = :podcastId AND e.id IN (:ids)" +
            " AND e.isNew = 1 AND e.availability = 'AVAILABLE' AND " + FeedQueryBuilder.VISIBLE,
    )
    suspend fun youTubeAnnounceable(
        podcastId: Long,
        ids: List<Long>,
    ): List<Long>

    @Query("SELECT * FROM episode WHERE podcastId = :podcastId AND externalMediaId = :videoId LIMIT 1")
    suspend fun youTubeByVideoId(
        podcastId: Long,
        videoId: String,
    ): EpisodeEntity?

    @Query("SELECT html FROM episode_description WHERE episodeId = :episodeId")
    suspend fun description(episodeId: Long): ByteArray?

    /** Refresh never scans an unbounded history or individually fetches every unknown video. */
    @Query(
        "SELECT e.id, e.externalMediaId, e.availability, e.isShort FROM episode e" +
            " JOIN podcast p ON p.id = e.podcastId WHERE e.podcastId = :podcastId" +
            " AND p.sourceType = 'YOUTUBE_CHANNEL' AND e.externalMediaId IS NOT NULL AND (" +
            " (e.availability = 'AVAILABLE' AND (e.durationMs IS NULL OR e.durationMs <= 0)" +
            " AND e.firstSeenAt > :now - 604800000 AND " + FeedQueryBuilder.VISIBLE + ") OR" +
            " (e.availability IN ('LIVE', 'UPCOMING') AND e.firstSeenAt > :now - 2592000000))" +
            " ORDER BY e.sortDate DESC, e.id DESC LIMIT 90",
    )
    suspend fun youTubeCandidates(
        podcastId: Long,
        now: Long,
    ): List<YouTubeCandidate>

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
     * 02 User-state writes' "played except newest" selection (D66; 05 §7 runs the mark-played
     * chain over these IDs when the import option asks): every non-new episode of the podcast
     * except its newest row. `isNew = 0` keeps an episode a non-initial refresh found meanwhile
     * unplayed.
     */
    @Query(
        "SELECT e.id FROM episode e WHERE e.podcastId = :podcastId AND e.isNew = 0" +
            " AND e.id <> (SELECT id FROM episode WHERE podcastId = :podcastId" +
            " ORDER BY sortDate DESC, id DESC LIMIT 1)",
    )
    suspend fun playedExceptNewestIds(podcastId: Long): List<Long>

    /**
     * `markFeedPlayed`'s id selection (02 Bulk "Mark all as played"): one-shot query built by
     * `FeedQueryBuilder.unplayedIds` so confirmation count and marked rows share a predicate.
     */
    @RawQuery(observedEntities = [EpisodeEntity::class, PodcastEntity::class, EpisodeStateEntity::class])
    suspend fun unplayedIds(query: RoomRawQuery): List<Long>

    /** Caller escapes LIKE wildcards; values are bound, never interpolated into SQL. */
    @Query(
        "SELECT e.id FROM episode e JOIN podcast p ON p.id = e.podcastId" +
            " WHERE e.title LIKE :pattern ESCAPE '\\' AND " + FeedQueryBuilder.VISIBLE +
            " AND e.availability = 'AVAILABLE' AND (:youtubePlayable = 1 OR p.sourceType = 'RSS')" +
            " ORDER BY e.sortDate DESC, e.id DESC LIMIT :limit",
    )
    suspend fun searchTitles(
        pattern: String,
        limit: Int,
        youtubePlayable: Boolean,
    ): List<Long>

    // --- Playback media lookup (02 Media lookup, M4) -------------------------------------------------

    /**
     * One-shot `MediaLookupRow` batch, 06's loader-thread miss path and `PlaybackMediaLookup.get`.
     * Room primitive [mediaInfoChunk] binds one ≤500-ID `IN` list; callers must use [mediaInfo]
     * for larger sets (02 Transactions and threading).
     */
    suspend fun mediaInfo(ids: List<Long>): List<MediaLookupRow> =
        ids.distinct().chunked(NOTIFICATION_BIND_CHUNK).flatMap { mediaInfoChunk(it) }

    @Transaction
    @Query("$MEDIA_INFO_SELECT WHERE e.id IN (:ids)")
    suspend fun mediaInfoChunk(ids: List<Long>): List<MediaLookupRow>

    /**
     * The observed variant (06's projector): Room tracks exactly the queried low-churn tables:
     * `episode`, `podcast`, `episode_state`, `download`, `artwork`, `episode_alt_enclosure`, so position ticks never
     * re-emit. Batch reads chunk like [mediaInfo]; an empty set emits one empty list.
     */
    fun observeMediaInfo(ids: List<Long>): Flow<List<MediaLookupRow>> =
        ids
            .distinct()
            .chunked(NOTIFICATION_BIND_CHUNK)
            .map { observeMediaInfoChunk(it) }
            .let { chunks ->
                when {
                    chunks.isEmpty() -> flowOf(emptyList())
                    chunks.size == 1 -> chunks.single()
                    else -> combine(chunks) { parts -> parts.asList().flatten() }
                }
            }

    @Transaction
    @Query("$MEDIA_INFO_SELECT WHERE e.id IN (:ids)")
    fun observeMediaInfoChunk(ids: List<Long>): Flow<List<MediaLookupRow>>

    /**
     * The queue-add validation batch (06 Up next operations, Reject row): existence, availability
     * and source type per requested id. Missing ids are simply absent from the result.
     */
    suspend fun queueEligibility(ids: List<Long>): List<QueueEligibilityRow> =
        ids.distinct().chunked(NOTIFICATION_BIND_CHUNK).flatMap { queueEligibilityChunk(it) }

    @Query(
        "SELECT e.id, e.availability, p.sourceType, e.externalMediaId" +
            " FROM episode e JOIN podcast p ON p.id = e.podcastId WHERE e.id IN (:ids)",
    )
    suspend fun queueEligibilityChunk(ids: List<Long>): List<QueueEligibilityRow>
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

/** Narrow refresh selection; identity, Atom hash and user state are intentionally absent. */
data class YouTubeCandidate(
    val id: Long,
    val externalMediaId: String,
    val availability: Availability,
    val isShort: Boolean,
)
