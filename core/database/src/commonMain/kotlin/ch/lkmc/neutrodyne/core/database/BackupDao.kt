// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.withReadTransaction
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceType
import kotlinx.coroutines.flow.Flow

/**
 * `BackupDao` (M3: 02 Backup export; archive format and merge rules owned by 05 Full backup and
 * restore). Export side only in M3 — every export read runs inside ONE read transaction
 * ([backupReadTransaction]) so the archive is a point-in-time snapshot: the keyset chunk loop
 * must never see rows a concurrent writer commits mid-export (WAL reader isolation).
 * [observeLibraryShape] is the exception: 05's snapshot library watcher collects it outside any
 * export.
 */
@Dao
interface BackupDao {
    /**
     * 05 Auto Backup's library watcher: counts only. The watcher pipes this through
     * `distinctUntilChanged().drop(1)`, so refresh writes to `podcast` (which re-emit an
     * identical shape) and position/played writes (which emit nothing) never request a snapshot;
     * subscribe, unsubscribe and membership changes do.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM podcast) AS podcasts," +
            " (SELECT COUNT(*) FROM podcast_group_member) AS memberships",
    )
    fun observeLibraryShape(): Flow<LibraryShape>

    /**
     * `library.json`'s podcast rows (05 `PodcastV1`): the identity (`feedKey`, real-or-derived
     * `podcastGuid`, `syncId`), YouTube fields, the user columns and `credentialOrigin` — the
     * owning credential's `origin`, so a restore can flag `needsCredentials` even when passwords
     * are not exported. `id` stays internal: it joins aliases, settings and ambiguous GUIDs.
     */
    @Query(
        "SELECT p.id, p.feedKey, p.feedUrl, p.sourceType, p.podcastGuid, p.podcastGuidDerived," +
            " p.youtubeChannelId, p.youtubeVariants, p.title, p.customTitle, p.artworkUrl, p.link," +
            " p.subscribedAt, p.includeInAll, p.episodeOrder, p.syncId," +
            " c.origin AS credentialOrigin" +
            " FROM podcast p LEFT JOIN credential c ON c.id = p.credentialId ORDER BY p.id",
    )
    suspend fun libraryPodcasts(): List<BackupPodcastRow>

    /** `PodcastV1.aliases`; the writer groups the rows by `podcastId`. */
    @Query("SELECT podcastId, url FROM podcast_url_alias ORDER BY podcastId, url")
    suspend fun libraryAliases(): List<BackupAliasRow>

    /** `PodcastV1.settings`: backups carry every override field (05 OverridesV1). */
    @Query("SELECT * FROM podcast_settings")
    suspend fun libraryPodcastSettings(): List<PodcastSettingsEntity>

    /**
     * `PodcastV1.guidAmbiguous` (D98): each feed's `KNOWN_AMBIGUOUS` canonical GUIDs — the only
     * provenance a backup carries; `KNOWN_INDEPENDENT` and `OBSERVED` rows never export.
     */
    @Query(
        "SELECT podcastId, guid FROM episode_guid_provenance WHERE knowledge = 'KNOWN_AMBIGUOUS'" +
            " ORDER BY podcastId, guid",
    )
    suspend fun libraryAmbiguousGuids(): List<BackupAmbiguousGuidRow>

    /** `library.json`'s groups in list order — `sortOrder` is each group's rank in this order. */
    @Query("SELECT * FROM podcast_group ORDER BY orderKey, id")
    suspend fun libraryGroups(): List<PodcastGroupEntity>

    /** `GroupV1.settings` (`podcast_group_settings` rows keyed by `groupId`). */
    @Query("SELECT * FROM podcast_group_settings")
    suspend fun libraryGroupSettings(): List<PodcastGroupSettingsEntity>

    /** `GroupV1.members` (`podcast_group_member` joined to `podcast.feedKey` → `MemberV1`). */
    @Query(
        "SELECT m.groupId, m.orderKey, m.addedAt, p.feedKey" +
            " FROM podcast_group_member m JOIN podcast p ON p.id = m.podcastId" +
            " ORDER BY m.groupId, m.orderKey, m.podcastId",
    )
    suspend fun libraryMemberships(): List<BackupMemberRow>

    /** `queue.json`'s Up next refs in list order → `UpNextRefV1` (`p`, `k`, `ok`). */
    @Query(
        "SELECT p.feedKey, e.identityKey, q.orderKey FROM queue_entry q" +
            " JOIN episode e ON e.id = q.episodeId JOIN podcast p ON p.id = e.podcastId" +
            " ORDER BY q.orderKey, q.id",
    )
    suspend fun queueRefs(): List<BackupQueueRow>

    /**
     * `queue.json`'s `SessionV1` (the `play_session` id = 0 singleton): the polymorphic
     * `contextId` resolves to the group `uuid` (GROUP) or the podcast `feedKey` (PODCAST), and
     * `currentEpisodeId`/the context anchor resolve to `(feedKey, identityKey)` refs.
     */
    @Query(
        "SELECT cur_p.feedKey AS currentFeedKey, cur_e.identityKey AS currentIdentityKey," +
            " s.contextType, g.uuid AS contextGroupUuid, ctx_p.feedKey AS contextPodcastKey," +
            " s.contextOrder, s.contextFilterFlags, s.contextMediaFilter, s.contextMinSortDate," +
            " anc_p.feedKey AS anchorFeedKey, anc_e.identityKey AS anchorIdentityKey," +
            " s.contextAnchorSortDate AS anchorSortDate" +
            " FROM play_session s" +
            " LEFT JOIN episode cur_e ON cur_e.id = s.currentEpisodeId" +
            " LEFT JOIN podcast cur_p ON cur_p.id = cur_e.podcastId" +
            " LEFT JOIN podcast_group g ON s.contextType = 'GROUP' AND g.id = s.contextId" +
            " LEFT JOIN podcast ctx_p ON s.contextType = 'PODCAST' AND ctx_p.id = s.contextId" +
            " LEFT JOIN episode anc_e ON anc_e.id = s.contextAnchorEpisodeId" +
            " LEFT JOIN podcast anc_p ON anc_p.id = anc_e.podcastId" +
            " WHERE s.id = 0",
    )
    suspend fun playSession(): BackupSessionRow?

    /**
     * `episodes.jsonl` source (02 Backup export, verbatim): every episode with user state,
     * a position, a download, an Up next entry or the session's current marker, in keyset chunks
     * ordered by `episode.id` (the rowid — the range/order needs no extra index). Columns map
     * 1:1 to `EpisodeLineV1` (`s.lastPlayedAt` → `lp`; `stateUpdatedAt` NULL → the `ts = 0`
     * no-state sentinel; `positionUpdatedAt` → `posAt`). `id` is the keyset cursor, never
     * exported; callers pass [EPISODE_CHUNK].
     */
    @Query(
        "SELECT p.feedKey, e.id, e.identityKey, e.guid, e.title, e.pubDate, e.enclosureUrl," +
            " e.enclosureType, e.durationMs, e.externalMediaId, e.link," +
            " s.playedAt, s.playCount, s.startedAt, s.lastPlayedAt, s.isFavorite," +
            " s.downloadDismissedAt, s.measuredDurationMs, s.updatedAt AS stateUpdatedAt," +
            " pos.positionMs, pos.positionSource, pos.updatedAt AS positionUpdatedAt," +
            " COALESCE(d.state = 'COMPLETED', 0) AS downloaded" +
            " FROM episode e" +
            " JOIN podcast p ON p.id = e.podcastId" +
            " LEFT JOIN episode_state s ON s.episodeId = e.id" +
            " LEFT JOIN episode_position pos ON pos.episodeId = e.id" +
            " LEFT JOIN download d ON d.episodeId = e.id" +
            " WHERE e.id > :afterId" +
            " AND (s.episodeId IS NOT NULL OR pos.positionMs > 0 OR d.episodeId IS NOT NULL" +
            " OR EXISTS (SELECT 1 FROM queue_entry q WHERE q.episodeId = e.id)" +
            " OR e.id = (SELECT currentEpisodeId FROM play_session WHERE id = 0))" +
            " ORDER BY e.id" +
            " LIMIT :limit",
    )
    suspend fun episodeChunk(
        afterId: Long,
        limit: Int,
    ): List<BackupEpisodeRow>

    // --- Restore matching (02 Restore matching; merge rules owned by 05) -----------------------

    /**
     * Podcast step 1 (02): the backup `feedKey` or any backup alias against local `feedKey`s
     * and `podcast_url_alias`es — a live `feedKey` beats alias evidence, ties by `id`.
     * [keys] = `[key]` + the backup's aliases. (02 names the sort column `rank`; `rank` is a
     * window-function keyword Room's parser rejects, so the column is `r` here. The outer
     * select keeps the result single-column.)
     */
    @Query(
        "SELECT id FROM (" +
            "SELECT id, 0 AS r FROM podcast WHERE feedKey = :key" +
            " UNION ALL SELECT id, 1 FROM podcast WHERE feedKey IN (:keys)" +
            " UNION ALL SELECT podcastId, 2 FROM podcast_url_alias WHERE url IN (:keys)" +
            " ORDER BY r, id LIMIT 1)",
    )
    suspend fun matchPodcastByKeys(
        key: String,
        keys: List<String>,
    ): Long?

    /**
     * Podcast step 2 (02): the real `podcast:guid` only — a derived GUID is never match
     * evidence (`podcastGuidDerived` rows can match on URL evidence or not at all).
     */
    @Query("SELECT id FROM podcast WHERE podcastGuid = :guid AND podcastGuidDerived = 0 ORDER BY id LIMIT 1")
    suspend fun matchPodcastByRealGuid(guid: String): Long?

    /** Group match, `uuid` step (02 Restore matching); the `nameKey` step is `GroupDao.groupByNameKey`. */
    @Query("SELECT id FROM podcast_group WHERE uuid = :uuid")
    suspend fun matchGroupByUuid(uuid: String): Long?

    /**
     * 02 Podcast syncId: a backup `syncId` is adopted for an inserted podcast only when no
     * local row uses it — otherwise the caller supplies a fresh UUIDv4.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM podcast WHERE syncId = :syncId)")
    suspend fun syncIdInUse(syncId: String): Boolean

    /**
     * `EpisodeMatcher`'s first resolution (02 Reuse by sync): the podcast a record's `syncId`
     * addresses. Redirects rewrite `syncId` in place, so even a stale record ID resolves.
     */
    @Query("SELECT id FROM podcast WHERE syncId = :syncId")
    suspend fun podcastIdBySyncId(syncId: String): Long?

    /**
     * One podcast's episode-match candidates (02 Restore matching): the columns
     * `EpisodeKeys.keyFor` needs when a line's `kv` differs from the stored version, plus
     * `guid`/`enclosureUrl`/`identityKey` for the ladder itself.
     */
    @Query(
        "SELECT id, identityKey, guid, enclosureUrl, title, pubDate, link" +
            " FROM episode WHERE podcastId = :podcastId",
    )
    suspend fun restoreEpisodeCandidates(podcastId: Long): List<RestoreEpisodeCandidate>

    /**
     * `episode_description` blobs of `h:`-key candidates — loaded only on demand: a row needs
     * its head only when some line's `kv` differs from the row's stored version AND the row's
     * own columns fall through to the `h:` recipe (02). Chunked at the bind limit; use this,
     * not the per-chunk primitive.
     */
    suspend fun descriptionBlobs(episodeIds: Collection<Long>): List<EpisodeDescriptionEntity> =
        episodeIds.distinct().chunked(BIND_CHUNK).flatMap { descriptionBlobsChunk(it) }

    /** Room primitive behind [descriptionBlobs]; callers must chunk ids at the bind limit. */
    @Query("SELECT * FROM episode_description WHERE episodeId IN (:ids)")
    suspend fun descriptionBlobsChunk(ids: List<Long>): List<EpisodeDescriptionEntity>

    // --- Restore state and position writes (02: `BackupDao`'s own statements) ------------------

    /**
     * 05 Merge, state fields: played OR (`playedAt`/`lastPlayedAt`/`playCount` = max),
     * favourite and download tombstone OR (tombstone time = max), `startedAt` = earliest
     * non-null (the played-clear is the caller's `PodcastMergeDao.clearPlayedStart`),
     * `measuredDurationMs` = local when non-null else the backup's. `updatedAt` moves to
     * `MAX(updatedAt, :ts)` — but only when a state field actually changes, so a row whose
     * local values all win keeps its own change time (02: a kept value leaves `updatedAt`
     * alone) while a backup-won value stamps the line's `ts`, never now. Callers run this
     * only for `ts != 0` lines and ensure the lazy state row first (`EpisodeStateDao.ensure`).
     */
    @Query(
        "UPDATE episode_state SET" +
            " startedAt = COALESCE(MIN(startedAt, :startedAt), startedAt, :startedAt)," +
            " playedAt = COALESCE(MAX(playedAt, :playedAt), playedAt, :playedAt)," +
            " playCount = MAX(playCount, :playCount)," +
            " lastPlayedAt = COALESCE(MAX(lastPlayedAt, :lastPlayedAt), lastPlayedAt, :lastPlayedAt)," +
            " isFavorite = MAX(isFavorite, :isFavorite)," +
            " downloadDismissedAt = COALESCE(MAX(downloadDismissedAt, :downloadDismissedAt)," +
            "  downloadDismissedAt, :downloadDismissedAt)," +
            " measuredDurationMs = COALESCE(measuredDurationMs, :measuredDurationMs)," +
            " updatedAt = MAX(updatedAt, :ts)" +
            " WHERE episodeId = :episodeId AND (" +
            " (:startedAt IS NOT NULL AND (startedAt IS NULL OR :startedAt < startedAt))" +
            " OR (:playedAt IS NOT NULL AND (playedAt IS NULL OR playedAt < :playedAt))" +
            " OR :playCount > playCount" +
            " OR (:lastPlayedAt IS NOT NULL AND (lastPlayedAt IS NULL OR lastPlayedAt < :lastPlayedAt))" +
            " OR :isFavorite > isFavorite" +
            " OR (:downloadDismissedAt IS NOT NULL AND" +
            "  (downloadDismissedAt IS NULL OR downloadDismissedAt < :downloadDismissedAt))" +
            " OR (measuredDurationMs IS NULL AND :measuredDurationMs IS NOT NULL))",
    )
    suspend fun mergeState(
        episodeId: Long,
        startedAt: Long?,
        playedAt: Long?,
        playCount: Int,
        lastPlayedAt: Long?,
        isFavorite: Boolean,
        downloadDismissedAt: Long?,
        measuredDurationMs: Long?,
        ts: Long,
    ): Int

    /**
     * 05 Merge, position step 1 (`INSERT OR IGNORE`): an absent local row adopts the backup's
     * position verbatim, `updatedAt = :posAt`.
     */
    @Query(
        "INSERT OR IGNORE INTO episode_position(episodeId, positionMs, durationMs, positionSource, updatedAt)" +
            " VALUES (:episodeId, :positionMs, :durationMs, :positionSource, :posAt)",
    )
    suspend fun ensurePositionForMerge(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long?,
        positionSource: PositionSource,
        posAt: Long,
    ): Long

    /**
     * 05 Merge, position step 2: the newer of `posAt` and the local `updatedAt` wins — but a
     * backup 0 or null never replaces a local non-zero position (N1), so the update fires only
     * for a non-zero backup position against a strictly older local row (ties keep local).
     * `durationMs`/`positionSource` travel with the winning position; the played-reset and
     * in-progress proxy derivations are the caller's follow-up statements.
     */
    @Query(
        "UPDATE episode_position SET positionMs = :positionMs, durationMs = :durationMs," +
            " positionSource = :positionSource, updatedAt = :posAt" +
            " WHERE episodeId = :episodeId AND :positionMs > 0 AND updatedAt < :posAt",
    )
    suspend fun mergePosition(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long?,
        positionSource: PositionSource,
        posAt: Long,
    ): Int

    /**
     * 05 Replace: the backup's state row verbatim (`updatedAt = :ts`) — the Replace pre-step
     * already deleted the matched podcasts' rows, so this normally inserts; OR REPLACE keeps a
     * re-run idempotent. Callers run it only for `ts != 0` lines: a `ts = 0` line under Replace
     * leaves the row absent (= unplayed).
     */
    @Query(
        "INSERT OR REPLACE INTO episode_state(episodeId, startedAt, playedAt, playCount, lastPlayedAt," +
            " isFavorite, downloadDismissedAt, measuredDurationMs, updatedAt)" +
            " VALUES (:episodeId, :startedAt, :playedAt, :playCount, :lastPlayedAt, :isFavorite," +
            " :downloadDismissedAt, :measuredDurationMs, :ts)",
    )
    suspend fun replaceState(
        episodeId: Long,
        startedAt: Long?,
        playedAt: Long?,
        playCount: Int,
        lastPlayedAt: Long?,
        isFavorite: Boolean,
        downloadDismissedAt: Long?,
        measuredDurationMs: Long?,
        ts: Long,
    ): Long

    /** 05 Replace: the backup's position verbatim (`updatedAt = :posAt`); absent = none. */
    @Query(
        "INSERT OR REPLACE INTO episode_position(episodeId, positionMs, durationMs, positionSource, updatedAt)" +
            " VALUES (:episodeId, :positionMs, :durationMs, :positionSource, :posAt)",
    )
    suspend fun replacePosition(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long?,
        positionSource: PositionSource,
        posAt: Long,
    ): Long

    /**
     * 05 Replace pre-step (HISTORY, one transaction per podcast): the matched podcasts' local
     * `episode_state`/`episode_position` rows go before the backup's rows are written.
     */
    @Query("DELETE FROM episode_state WHERE episodeId IN (SELECT id FROM episode WHERE podcastId = :podcastId)")
    suspend fun deleteStateOfPodcast(podcastId: Long): Int

    @Query("DELETE FROM episode_position WHERE episodeId IN (SELECT id FROM episode WHERE podcastId = :podcastId)")
    suspend fun deletePositionsOfPodcast(podcastId: Long): Int

    companion object {
        /** 02 Backup export: episodes stream in keyset chunks of 1,000. */
        const val EPISODE_CHUNK = 1000

        /** 02 Transactions and threading: `IN (:ids)` lists are chunked at 500 bound variables. */
        private const val BIND_CHUNK = 500
    }
}

/**
 * 02 Backup export / 05 Writing step 2: the whole export's reads run inside ONE read transaction
 * so the archive is a point-in-time snapshot (WAL readers are isolated from concurrent writes).
 * Room confines the transaction's reader connection to this coroutine, so every `BackupDao`
 * suspend call inside [block] runs on it; [block] may also stream `episodes.jsonl` to the local
 * `target.tmp` (05: only local files are written inside the transaction — never a
 * `ContentResolver` or other platform I/O).
 */
suspend fun <T> NeutrodyneDatabase.backupReadTransaction(block: suspend BackupDao.() -> T): T =
    withReadTransaction { backupDao().block() }

/**
 * 05's library-shape watch (02 Backup export): subscription count and membership count. Equal
 * shapes re-emit on any `podcast`/`podcast_group_member` write; `distinctUntilChanged` in the
 * watcher drops them.
 */
data class LibraryShape(
    val podcasts: Int,
    val memberships: Int,
)

/** One `library.json` podcast line's columns (05 `PodcastV1`); `id` correlates, never exports. */
data class BackupPodcastRow(
    val id: Long,
    val feedKey: String,
    val feedUrl: String,
    val sourceType: SourceType,
    /** Real GUIDs only travel — the writer drops this when [podcastGuidDerived] is set. */
    val podcastGuid: String?,
    val podcastGuidDerived: Boolean,
    val youtubeChannelId: String?,
    val youtubeVariants: Int,
    val title: String,
    val customTitle: String?,
    val artworkUrl: String?,
    val link: String?,
    val subscribedAt: Long,
    val includeInAll: Boolean,
    val episodeOrder: FeedOrder?,
    val syncId: String,
    /** `credential.origin` of `podcast.credentialId`; NULL = no credential. */
    val credentialOrigin: String?,
)

/** One `podcast_url_alias` row as `PodcastV1.aliases` needs it. */
data class BackupAliasRow(
    val podcastId: Long,
    val url: String,
)

/** One `episode_guid_provenance` ambiguity fact → `PodcastV1.guidAmbiguous`. */
data class BackupAmbiguousGuidRow(
    val podcastId: Long,
    val guid: String,
)

/** One group membership → `MemberV1` (`groupId` groups the rows in Kotlin). */
data class BackupMemberRow(
    val groupId: Long,
    val orderKey: String,
    val addedAt: Long,
    val feedKey: String,
)

/** One Up next entry → `UpNextRefV1` (`p`, `k`, `ok`). */
data class BackupQueueRow(
    val feedKey: String,
    val identityKey: String,
    val orderKey: String,
)

/**
 * `queue.json`'s `SessionV1` columns: episode refs as `(feedKey, identityKey)` pairs (NULL pair =
 * absent ref) and the context resolved to its group `uuid` or podcast `feedKey`.
 */
data class BackupSessionRow(
    val currentFeedKey: String?,
    val currentIdentityKey: String?,
    val contextType: ContextType?,
    val contextGroupUuid: String?,
    val contextPodcastKey: String?,
    val contextOrder: FeedOrder,
    val contextFilterFlags: Int,
    val contextMediaFilter: MediaFilter,
    val contextMinSortDate: Long?,
    val anchorFeedKey: String?,
    val anchorIdentityKey: String?,
    val anchorSortDate: Long?,
)

/**
 * One `episodes.jsonl` line's columns (02 Backup export). LEFT-JOINed state/position columns stay
 * nullable: a queue-only, download-only or current-episode row has `stateUpdatedAt` NULL → the
 * writer emits `ts = 0`, the no-state-change sentinel.
 */
data class BackupEpisodeRow(
    val feedKey: String,
    /** Keyset cursor for the next chunk; never written to the archive. */
    val id: Long,
    val identityKey: String,
    val guid: String?,
    val title: String,
    val pubDate: Long?,
    val enclosureUrl: String?,
    val enclosureType: String?,
    val durationMs: Long?,
    val externalMediaId: String?,
    val link: String?,
    val playedAt: Long?,
    val playCount: Int?,
    val startedAt: Long?,
    val lastPlayedAt: Long?,
    val isFavorite: Boolean?,
    val downloadDismissedAt: Long?,
    val measuredDurationMs: Long?,
    val stateUpdatedAt: Long?,
    val positionMs: Long?,
    val positionSource: PositionSource?,
    val positionUpdatedAt: Long?,
    val downloaded: Boolean,
)

/**
 * One stored episode as a restore-match candidate (02 Restore matching): `identityKey`,
 * `guid` and `enclosureUrl` serve the ladder steps directly; `title`/`pubDate`/`link` feed
 * `EpisodeKeys.keyFor` when a line's `kv` differs from the stored version (an `h:` row then
 * also needs its `episode_description` head, loaded on demand by [BackupDao.descriptionBlobs]).
 */
data class RestoreEpisodeCandidate(
    val id: Long,
    val identityKey: String,
    val guid: String?,
    val enclosureUrl: String?,
    val title: String,
    val pubDate: Long?,
    val link: String?,
)
