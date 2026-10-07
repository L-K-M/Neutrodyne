// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import kotlinx.coroutines.flow.Flow

/**
 * `podcast` and its dependents (02 Key queries). An abstract class because [deleteCascade] runs
 * several statements inside one `withWriteTransaction` ([db] is the same database instance Room
 * builds this DAO for).
 */
@Dao
abstract class PodcastDao(
    private val db: NeutrodyneDatabase,
) {
    /**
     * Every insert path — subscribe (03), import commit (05), restore (05), sync apply (10) —
     * supplies a fresh `syncId` (UUIDv4, lowercase) on the entity (02 podcast.syncId).
     */
    @Insert
    abstract suspend fun insertPodcast(row: PodcastEntity): Long

    @Insert
    abstract suspend fun insertAlias(row: PodcastUrlAliasEntity)

    @Insert
    abstract suspend fun insertAliases(rows: List<PodcastUrlAliasEntity>)

    @Query("SELECT * FROM podcast WHERE id = :id")
    abstract suspend fun byId(id: Long): PodcastEntity?

    @Query("SELECT * FROM podcast WHERE feedKey = :feedKey")
    abstract suspend fun byFeedKey(feedKey: String): PodcastEntity?

    @Delete
    abstract suspend fun deleteAliases(rows: List<PodcastUrlAliasEntity>)

    // --- Add-flow dedupe and repository reads (03, added 2026-10-07) -----------------------------

    /** The podcast owning the alias `url` (`feedKey` is checked separately by the caller). */
    @Query("SELECT podcastId FROM podcast_url_alias WHERE url = :url")
    abstract suspend fun aliasOwner(url: String): Long?

    /** Subscribe-time soft dedupe (03): real `podcastGuid`s only, never derived ones. */
    @Query("SELECT * FROM podcast WHERE podcastGuid = :guid AND podcastGuidDerived = 0")
    abstract suspend fun byRealGuid(guid: String): List<PodcastEntity>

    @Query("SELECT * FROM podcast WHERE id = :id")
    abstract fun observeById(id: Long): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcast_url_alias WHERE podcastId = :podcastId")
    abstract fun observeAliases(podcastId: Long): Flow<List<PodcastUrlAliasEntity>>

    /** The suspend variant of [observeAliases] for in-transaction lookups (03 new-feed-url rule 1). */
    @Query("SELECT * FROM podcast_url_alias WHERE podcastId = :podcastId")
    abstract suspend fun aliases(podcastId: Long): List<PodcastUrlAliasEntity>

    @Query("SELECT COUNT(*) FROM episode WHERE podcastId = :podcastId")
    abstract fun observeEpisodeCount(podcastId: Long): Flow<Int>

    /** The suspend variant for 03's paging-session item cap (5,000 episodes). */
    @Query("SELECT COUNT(*) FROM episode WHERE podcastId = :podcastId")
    abstract suspend fun episodeCount(podcastId: Long): Int

    /** `observeCategoryCounts`'s source rows (03): every podcast's stored categories. */
    @Query("SELECT id, categoriesJson FROM podcast WHERE categoriesJson IS NOT NULL")
    abstract fun observeCategoryRows(): Flow<List<PodcastCategories>>

    // --- Refresh rebase and user actions (03, added 2026-10-07) -----------------------------------

    /**
     * `NextRefreshRebaser`'s candidate set: healthy feeds only (failing feeds keep their backoff,
     * 03 Periodic tick step 2). The `PodcastFetchState` projection carries exactly the columns the
     * batched rebase write touches.
     */
    @Query(
        "SELECT id, lastAttemptAt, lastSuccessAt, nextRefreshAt, failureCount, lastErrorKind," +
            " lastErrorDetail, gone, needsCredentials, etag, lastModified, lastFullFetchAt," +
            " lastParseOk, subscribedAt FROM podcast" +
            " WHERE failureCount = 0 AND gone = 0 AND needsCredentials = 0",
    )
    abstract suspend fun rebaseCandidates(): List<RebaseCandidate>

    /** "Try again" (03 Per-feed states): clears the gone/credentials block and the failure count. */
    @Query(
        "UPDATE podcast SET gone = 0, needsCredentials = 0, failureCount = 0," +
            " lastErrorKind = NULL, lastErrorDetail = NULL WHERE id = :id",
    )
    abstract suspend fun clearRefreshBlock(id: Long)

    /** `Ungrouped`'s member set (03 `refreshFeed` scope mapping). */
    @Query(
        "SELECT id FROM podcast WHERE NOT EXISTS" +
            " (SELECT 1 FROM podcast_group_member m WHERE m.podcastId = podcast.id)",
    )
    abstract suspend fun ungroupedIds(): List<Long>

    /** "Include in All" (02 user columns; never written by feed-derived paths). */
    @Query("UPDATE podcast SET includeInAll = :include WHERE id = :podcastId")
    abstract suspend fun setIncludeInAll(
        podcastId: Long,
        include: Boolean,
    )

    @Query("UPDATE podcast SET customTitle = :title WHERE id = :podcastId")
    abstract suspend fun setCustomTitle(
        podcastId: Long,
        title: String?,
    )

    // --- Refresh selection and fetch-state writes (02; 03 Refresh scheduling) --------------------

    /**
     * 03's "force" step, persisted so a continuation needs no IDs. `scopeAll = true` is All;
     * otherwise `ids` is the explicit podcast set. An empty `ids` list binds the [NO_ID] sentinel —
     * `IN ()` never matches and Room cannot expand an empty collection.
     */
    suspend fun forceDue(
        scopeAll: Boolean,
        ids: List<Long> = emptyList(),
    ) {
        ids.ifEmpty { listOf(NO_ID) }.chunked(BIND_CHUNK).forEach { forceDueChunk(scopeAll, it) }
    }

    /** The Group variant: the same statement with the membership subquery for `id IN (:ids)`. */
    @Query(
        "UPDATE podcast SET nextRefreshAt = 0 WHERE gone = 0 AND needsCredentials = 0" +
            " AND id IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = :groupId)" +
            " AND nextRefreshAt IS NOT 0",
    )
    abstract suspend fun forceDueGroup(groupId: Long)

    @Query(
        "UPDATE podcast SET nextRefreshAt = 0 WHERE gone = 0 AND needsCredentials = 0" +
            " AND (:scopeAll = 1 OR id IN (:ids)) AND nextRefreshAt IS NOT 0",
    )
    protected abstract suspend fun forceDueChunk(
        scopeAll: Boolean,
        ids: List<Long>,
    )

    /** `dueForRefresh(dueBefore = now + slack)` — pending first fetches first, then stalest. */
    suspend fun dueForRefresh(
        dueBefore: Long,
        scopeAll: Boolean,
        ids: List<Long> = emptyList(),
    ): List<DueFeed> =
        ids
            .ifEmpty { listOf(NO_ID) }
            .distinct()
            .chunked(BIND_CHUNK)
            .flatMap { dueForRefreshChunk(dueBefore, scopeAll, it) }
            // Chunk-local ORDER BY does not merge: reapply the global keys over the union.
            .distinctBy { it.id }
            .sortedWith(
                compareBy(
                    { row: DueFeed -> row.status != PodcastStatus.PENDING_FIRST_FETCH },
                    { it.lastSuccessAt ?: 0L },
                    { it.id },
                ),
            )

    @Query(
        "$DUE_COLUMNS FROM podcast WHERE gone = 0 AND needsCredentials = 0" +
            " AND (nextRefreshAt IS NULL OR nextRefreshAt <= :dueBefore)" +
            " AND (:scopeAll = 1 OR id IN (:ids))" +
            " ORDER BY (status = 'PENDING_FIRST_FETCH') DESC, COALESCE(lastSuccessAt, 0) ASC, id ASC",
    )
    protected abstract suspend fun dueForRefreshChunk(
        dueBefore: Long,
        scopeAll: Boolean,
        ids: List<Long>,
    ): List<DueFeed>

    @Query(
        "$DUE_COLUMNS FROM podcast WHERE gone = 0 AND needsCredentials = 0" +
            " AND (nextRefreshAt IS NULL OR nextRefreshAt <= :dueBefore)" +
            " AND id IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = :groupId)" +
            " ORDER BY (status = 'PENDING_FIRST_FETCH') DESC, COALESCE(lastSuccessAt, 0) ASC, id ASC",
    )
    abstract suspend fun dueForRefreshGroup(
        dueBefore: Long,
        groupId: Long,
    ): List<DueFeed>

    /** `pagingPending(scope)` — 03's pagesOnly runs and background paging. */
    suspend fun pagingPending(
        scopeAll: Boolean,
        ids: List<Long> = emptyList(),
    ): List<DueFeed> =
        ids
            .ifEmpty { listOf(NO_ID) }
            .distinct()
            .chunked(BIND_CHUNK)
            .flatMap { pagingPendingChunk(scopeAll, it) }
            .distinctBy { it.id }
            .sortedBy { it.id }

    @Query(
        "$DUE_COLUMNS FROM podcast WHERE gone = 0 AND needsCredentials = 0" +
            " AND pagingComplete = 0 AND pagingNextUrl IS NOT NULL" +
            " AND (:scopeAll = 1 OR id IN (:ids)) ORDER BY id",
    )
    protected abstract suspend fun pagingPendingChunk(
        scopeAll: Boolean,
        ids: List<Long>,
    ): List<DueFeed>

    @Query(
        "$DUE_COLUMNS FROM podcast WHERE gone = 0 AND needsCredentials = 0" +
            " AND pagingComplete = 0 AND pagingNextUrl IS NOT NULL" +
            " AND id IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = :groupId) ORDER BY id",
    )
    abstract suspend fun pagingPendingGroup(groupId: Long): List<DueFeed>

    /** The next-page re-read of a paging session (03): `pagingNextUrl` moved inside the ingest. */
    @Query("$DUE_COLUMNS FROM podcast WHERE id = :id")
    abstract suspend fun dueFeedById(id: Long): DueFeed?

    /**
     * The paging-session stop of 03 RFC 5005 paging: budget, loop, no-new-keys and the 5,000-item
     * cap set "older pages exist, not wanted" while keeping `pagingNextUrl` so "Load older
     * episodes" can resume.
     */
    @Query("UPDATE podcast SET pagingComplete = 1 WHERE id = :id")
    abstract suspend fun markPagingComplete(id: Long)

    /**
     * "Load older episodes" (03 `loadOlderEpisodes`): a feed holding an older-page link returns to
     * "background paging pending" (`pagingComplete = 0`); a feed without one stays put.
     */
    @Query("UPDATE podcast SET pagingComplete = 0 WHERE id = :id AND pagingNextUrl IS NOT NULL")
    abstract suspend fun reopenPaging(id: Long)

    /**
     * Batched refresh outcomes (02): scheduling, error and validator columns only — the partial
     * class carries no feed-data or user column. Flushed by `FetchStateBatcher` in ≤ 20-row
     * batches; a repeated write with identical values still fires Room invalidation, so 03 passes
     * only changed rows.
     */
    @Update(entity = PodcastEntity::class)
    abstract suspend fun updateFetchStates(rows: List<PodcastFetchState>)

    // --- Library tiles and mosaics (02; M1: groupId = null → all) ---------------------------------

    @Query(
        "SELECT p.id, COALESCE(p.customTitle, p.title) AS title, p.sourceType, p.status," +
            " p.artworkKey, p.artworkUrl, COALESCE(a.version, 0) AS artworkVersion," +
            " a.avgArgb AS artworkAvgArgb," +
            " p.gone, p.needsCredentials, p.failureCount, p.lastErrorKind, p.lastSuccessAt," +
            " p.latestEpisodeAt, p.subscribedAt," +
            " (SELECT COUNT(*) FROM episode e LEFT JOIN episode_state s ON s.episodeId = e.id" +
            "  WHERE e.podcastId = p.id AND s.playedAt IS NULL AND e.sortDate >= :sinceMs" +
            "    AND ${FeedQueryBuilder.VISIBLE} AND e.availability = 'AVAILABLE') AS unplayedCount" +
            " FROM podcast p LEFT JOIN artwork a ON a.key = p.artworkKey" +
            " WHERE :groupId IS NULL OR p.id IN" +
            " (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = :groupId)",
    )
    abstract fun observeLibraryTiles(
        sinceMs: Long,
        groupId: Long?,
    ): Flow<List<LibraryTileRow>>

    // --- Unsubscribe (02 Unsubscribe and merge, D24) ----------------------------------------------

    /**
     * `PodcastDao.deleteCascade(podcastId)`: person/funding rows, the cleared `PODCAST` context,
     * the unshared credential, the parked records and clock rows, then the podcast itself — the
     * FK cascades delete `episode` (+ its children, state, position, Up next, download),
     * `podcast_url_alias`, `podcast_settings` and `podcast_group_member`; `SET NULL` clears
     * `play_session.currentEpisodeId` and `import_item.podcastId`. While linked (MS0+) the caller
     * wraps this in `SyncStateDao.withApplying` for a sync-origin unsubscribe.
     */
    suspend fun deleteCascade(
        podcastId: Long,
        now: Long,
    ) {
        db.withWriteTransaction {
            deletePersonsOfPodcast(podcastId)
            deleteFundingOfPodcast(podcastId)
            clearPodcastContext(podcastId, now)
            deleteUnsharedCredential(podcastId)
            deleteSyncParkedOfPodcast(podcastId)
            deleteSyncClocksOfPodcast(podcastId)
            deletePodcastRow(podcastId)
        }
    }

    /** `person` rows owned by the podcast itself or by any of its episodes. */
    @Query(
        "DELETE FROM person WHERE (ownerType = 'PODCAST' AND ownerId = :podcastId)" +
            " OR (ownerType = 'EPISODE' AND ownerId IN (SELECT id FROM episode WHERE podcastId = :podcastId))",
    )
    protected abstract suspend fun deletePersonsOfPodcast(podcastId: Long)

    /** `funding` rows owned by the podcast itself or by any of its episodes. */
    @Query(
        "DELETE FROM funding WHERE (ownerType = 'PODCAST' AND ownerId = :podcastId)" +
            " OR (ownerType = 'EPISODE' AND ownerId IN (SELECT id FROM episode WHERE podcastId = :podcastId))",
    )
    protected abstract suspend fun deleteFundingOfPodcast(podcastId: Long)

    @Query(
        "UPDATE play_session SET contextType = NULL, contextId = NULL, contextAnchorEpisodeId = NULL," +
            " contextAnchorSortDate = NULL, generation = generation + 1, updatedAt = :now" +
            " WHERE contextType = 'PODCAST' AND contextId = :podcastId",
    )
    protected abstract suspend fun clearPodcastContext(
        podcastId: Long,
        now: Long,
    )

    /**
     * The last-reference credential delete (02): feed origins only (`podcastindex` and `sync:*`
     * survive), and only when no other podcast references the row.
     */
    @Query(
        "DELETE FROM credential WHERE origin <> 'podcastindex' AND origin NOT LIKE 'sync:%'" +
            " AND id = (SELECT credentialId FROM podcast WHERE id = :podcastId)" +
            " AND NOT EXISTS (SELECT 1 FROM podcast o WHERE o.credentialId = credential.id AND o.id <> :podcastId)",
    )
    protected abstract suspend fun deleteUnsharedCredential(podcastId: Long)

    @Query(
        "DELETE FROM sync_parked WHERE podcastSyncId = (SELECT syncId FROM podcast WHERE id = :podcastId)",
    )
    protected abstract suspend fun deleteSyncParkedOfPodcast(podcastId: Long)

    /** The episode, Up next and member clock rows of this podcast; its own row stays as tombstone. */
    @Query(
        "DELETE FROM sync_clock WHERE" +
            " (coll IN ('episode', 'upnext') AND" +
            "  substr(rid, 1, 36) = (SELECT syncId FROM podcast WHERE id = :podcastId))" +
            " OR (coll = 'member' AND" +
            "  substr(rid, 37, 36) = (SELECT syncId FROM podcast WHERE id = :podcastId))",
    )
    protected abstract suspend fun deleteSyncClocksOfPodcast(podcastId: Long)

    @Query("DELETE FROM podcast WHERE id = :podcastId")
    protected abstract suspend fun deletePodcastRow(podcastId: Long)

    private companion object {
        /** 02 Transactions and threading: `IN (:ids)` lists are chunked at 500 bound variables. */
        const val BIND_CHUNK = 500

        /** Bound for `id IN (:ids)` when the caller's list is empty: podcast ids are always > 0. */
        const val NO_ID = -1L

        private const val DUE_COLUMNS =
            "SELECT id, feedUrl, sourceType, youtubeChannelId, youtubeVariants, channelMetadataAt," +
                " etag, lastModified, contentSha256, parserVersion, lastParseOk, credentialId," +
                " failureCount, initialFetch, status, lastSuccessAt, lastFullFetchAt, pendingNewFeedUrl," +
                " pagingNextUrl, pagingComplete, complete, ttlMinutes, latestEpisodeAt, subscribedAt," +
                " lastAttemptAt, lastErrorKind"
    }
}
