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

    companion object {
        /** 02 Backup export: episodes stream in keyset chunks of 1,000. */
        const val EPISODE_CHUNK = 1000
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
