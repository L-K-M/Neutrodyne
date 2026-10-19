// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Embedded
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FilterFlagBits
import ch.lkmc.neutrodyne.core.model.GroupCounts
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import kotlinx.coroutines.flow.Flow

/**
 * Feed pages (02 Feed pages, D30): [page] runs a [FeedQueryBuilder]-built raw query. M1a delivers
 * the All and Podcast sources; Group and Ungrouped reuse the same predicates from M2.
 *
 * `@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)` must sit on the
 * `@Dao`-annotated interface — without it the paging converter is ignored and non-Android targets
 * reject the blocking signature (spike S2, 2026-10-06).
 */
@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface FeedDao {
    /**
     * The observed entity set is the union of every table [FeedQueryBuilder] reads, so a page
     * reloads on any relevant write (episode, podcast, state, download, artwork, membership).
     */
    @RawQuery(
        observedEntities = [
            EpisodeEntity::class,
            PodcastEntity::class,
            PodcastGroupMemberEntity::class,
            EpisodeStateEntity::class,
            DownloadEntity::class,
            ArtworkEntity::class,
        ],
    )
    fun page(query: RoomRawQuery): PagingSource<Int, EpisodeRowProjection>

    @RawQuery(
        observedEntities = [
            PodcastGroupEntity::class, PodcastGroupMemberEntity::class, PodcastEntity::class,
            EpisodeEntity::class, EpisodeStateEntity::class,
        ],
    )
    fun observeGroupCounts(query: RoomRawQuery): Flow<List<GroupCountRow>>

    @RawQuery(
        observedEntities = [
            PodcastGroupMemberEntity::class, PodcastEntity::class, EpisodeEntity::class, EpisodeStateEntity::class,
        ],
    )
    fun observeVirtualCounts(query: RoomRawQuery): Flow<FeedCounts>

    @RawQuery
    suspend fun countUnplayed(query: RoomRawQuery): Int

    @RawQuery
    suspend fun unplayedIds(query: RoomRawQuery): List<Long>

    /**
     * One-shot context tail/start rows (02 Play context) built by [FeedQueryBuilder.contextTail]:
     * up to `k` eligible episodes strictly after the anchor. The current item is not excluded in
     * SQL, callers fetch `k + 1` and drop it (06 Queue and play context).
     */
    @RawQuery
    suspend fun contextTail(query: RoomRawQuery): List<ContextItem>

    /** Browse shares context eligibility but includes queued episodes and has no anchor. */
    @RawQuery
    suspend fun contextList(query: RoomRawQuery): List<ContextItem>
}

/** Empty groups have a row with zero counts, including groups whose members have no episodes. */
data class GroupCountRow(
    val groupId: Long,
    @Embedded val counts: FeedCounts,
)

/** One context-tail row (02 Play context): the keyset identity plus the podcast for media joins. */
data class ContextItem(
    val episodeId: Long,
    val sortDate: Long,
    val podcastId: Long,
)

/**
 * Builds feed-page raw queries (02 Feed pages). SQL is assembled only from the enumerated
 * fragments below — every value is bound, never concatenated.
 */
object FeedQueryBuilder {
    /** v1 implementation of the PO-9 defaults (02 Key queries); hidden = Short without SHORTS. */
    const val VISIBLE =
        "NOT (e.isShort = 1 AND (p.youtubeVariants & 2) = 0) " +
            "AND e.availability NOT IN ('UPCOMING', 'LIVE', 'MEMBERS_ONLY')"

    /** SELECT list of the feed-page row, matching `EpisodeRowProjection` (02 `ROW_COLUMNS`). */
    const val ROW_COLUMNS =
        "e.id, e.podcastId, e.title, e.sortDate, e.pubDate," +
            " COALESCE(s.measuredDurationMs, e.durationMs) AS durationMs," +
            " e.isVideo, e.isShort, e.availability, e.episodeType, e.episodeDisplay," +
            " e.externalMediaId, e.isNew, e.firstSeenAt," +
            " COALESCE(p.customTitle, p.title) AS podcastTitle, p.sourceType," +
            " COALESCE(e.artworkKey, p.artworkKey) AS artworkKey," +
            " COALESCE(e.imageUrl, p.artworkUrl) AS artworkUrl," +
            " COALESCE(a.version, 0) AS artworkVersion, a.avgArgb AS artworkAvgArgb," +
            " p.artworkKey AS podcastArtworkKey, p.artworkUrl AS podcastArtworkUrl," +
            " COALESCE(pa.version, 0) AS podcastArtworkVersion, pa.avgArgb AS podcastArtworkAvgArgb," +
            " s.playedAt, s.startedAt, COALESCE(s.isFavorite, 0) AS isFavorite, d.state AS downloadState"

    /** The feed-page joins (02 `ROW_JOINS`): state, download and the two artwork lookups. */
    const val ROW_JOINS =
        "LEFT JOIN episode_state s ON s.episodeId = e.id" +
            " LEFT JOIN download d ON d.episodeId = e.id" +
            " LEFT JOIN artwork a ON a.key = COALESCE(e.artworkKey, p.artworkKey)" +
            " LEFT JOIN artwork pa ON pa.key = p.artworkKey"

    /**
     * `FeedDao.page` SQL for [source] with [f]ilters and [order]. `CROSS JOIN` on the All feed
     * forces the ordered scan of `index_episode_sortDate`; other sources use the plain join.
     */
    fun page(
        source: FeedSource,
        f: FeedFilters,
        order: FeedOrder,
    ): RoomRawQuery {
        val where = mutableListOf(VISIBLE)
        val args = mutableListOf<Long>()
        val from =
            if (source == FeedSource.All) {
                where += "p.id = e.podcastId"
                where += "p.includeInAll = 1"
                "episode e CROSS JOIN podcast p"
            } else {
                "episode e JOIN podcast p ON p.id = e.podcastId"
            }

        when (source) {
            FeedSource.All -> {}

            FeedSource.Ungrouped -> {
                where += "NOT EXISTS (SELECT 1 FROM podcast_group_member m WHERE m.podcastId = e.podcastId)"
            }

            is FeedSource.Group -> {
                where += "e.podcastId IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = ?)"
                args += source.groupId
            }

            is FeedSource.Podcast -> {
                where += "e.podcastId = ?"
                args += source.podcastId
            }
        }

        f.minSortDate?.let {
            where += "e.sortDate >= ?"
            args += it
        }
        if (f.unplayedOnly) where += "s.playedAt IS NULL"
        if (f.inProgressOnly) where += "s.startedAt IS NOT NULL AND s.playedAt IS NULL"
        if (f.downloadedOnly) where += "d.state = 'COMPLETED'"
        when (f.media) {
            MediaFilter.AUDIO -> where += "e.isVideo = 0"
            MediaFilter.VIDEO -> where += "e.isVideo = 1"
            MediaFilter.ALL -> Unit
        }

        val dir = if (order == FeedOrder.NEWEST_FIRST) "DESC" else "ASC"
        val sql =
            "SELECT $ROW_COLUMNS FROM $from $ROW_JOINS WHERE ${where.joinToString(" AND ")} " +
                "ORDER BY e.sortDate $dir, e.id $dir"
        return RoomRawQuery(sql) { st -> args.forEachIndexed { i, v -> st.bindLong(i + 1, v) } }
    }

    /** LEFT joins retain empty groups while the episode join uses the per-podcast date index. */
    fun groupCounts(
        sinceMs: Long,
        nowMs: Long,
    ): RoomRawQuery =
        boundQuery(
            "SELECT g.id AS groupId," +
                " COUNT(CASE WHEN s.playedAt IS NULL THEN e.id END) AS unplayed," +
                " COUNT(CASE WHEN s.playedAt IS NULL AND e.isNew = 1" +
                " AND e.firstSeenAt > COALESCE(g.lastViewedAt, g.createdAt) THEN e.id END) AS newSinceVisit" +
                " FROM podcast_group g" +
                " LEFT JOIN podcast_group_member m ON m.groupId = g.id" +
                " LEFT JOIN podcast p ON p.id = m.podcastId" +
                " LEFT JOIN episode e ON e.podcastId = m.podcastId" +
                " AND e.sortDate >= MAX(?, CASE WHEN g.hideOlderThanDays IS NULL THEN 0" +
                " ELSE ? - g.hideOlderThanDays * ${GroupCounts.DAY_MS} END)" +
                " AND $VISIBLE AND e.availability = 'AVAILABLE'" +
                " LEFT JOIN episode_state s ON s.episodeId = e.id GROUP BY g.id",
            listOf(sinceMs, nowMs),
        )

    fun virtualCounts(
        source: FeedSource,
        sinceMs: Long,
        lastViewedAt: Long,
    ): RoomRawQuery {
        require(source == FeedSource.All || source == FeedSource.Ungrouped) { "Not a virtual feed: $source" }
        val predicate = if (source == FeedSource.All) "p.includeInAll = 1" else UNGROUPED
        return boundQuery(
            "SELECT COUNT(CASE WHEN s.playedAt IS NULL THEN e.id END) AS unplayed," +
                " COUNT(CASE WHEN s.playedAt IS NULL AND e.isNew = 1 AND e.firstSeenAt > ?" +
                " THEN e.id END) AS newSinceVisit" +
                " FROM episode e CROSS JOIN podcast p LEFT JOIN episode_state s ON s.episodeId = e.id" +
                " WHERE p.id = e.podcastId AND $predicate AND e.sortDate >= ?" +
                " AND $VISIBLE AND e.availability = 'AVAILABLE'",
            listOf(lastViewedAt, sinceMs),
        )
    }

    fun countUnplayed(
        source: FeedSource,
        sortDateBefore: Long?,
    ): RoomRawQuery = unplayedSelection("COUNT(*)", source, sortDateBefore)

    fun unplayedIds(
        source: FeedSource,
        sortDateBefore: Long?,
    ): RoomRawQuery = unplayedSelection("e.id", source, sortDateBefore)

    /**
     * The context tail / start-item query (02 Play context; membership and start rules are 05's
     * "Playing a group"): unplayed, `AVAILABLE`, [VISIBLE], queue-free episodes ordered by
     * `(sortDate, id)` in [spec]'s order, `LIMIT ?` = [k]. Both anchor fields must be null or
     * non-null together. A null pair reads from the beginning of the order; a stored pair
     * keysets strictly after it even when the anchor row was deleted. The current item is *not*
     * excluded: callers fetch `k + 1` and drop it (06). `EXTERNAL` contexts have no tail and are
     * rejected.
     *
     * [boundBySubscription] adds 05's `e.sortDate >= p.subscribedAt` start bound, meaningful only
     * for `GROUP` contexts (the first `startItem` attempt of an oldest-first group) and ignored
     * for the others. `DOWNLOADS` is completed downloads across every podcast (`includeInAll`
     * ignored), matching the flag-driven `d.state = 'COMPLETED'` filter it subsumes.
     */
    fun contextTail(
        spec: PlayContextSpec,
        anchorEpisodeId: Long?,
        anchorSortDate: Long?,
        k: Int,
        youtubePlayable: Boolean,
        boundBySubscription: Boolean = false,
    ): RoomRawQuery =
        contextSelection(
            spec,
            anchorEpisodeId,
            anchorSortDate,
            k,
            youtubePlayable,
            boundBySubscription,
            ContextSelection.TAIL,
            null,
        )

    fun contextList(
        spec: PlayContextSpec,
        k: Int,
        youtubePlayable: Boolean,
        episodeId: Long? = null,
    ): RoomRawQuery = contextSelection(spec, null, null, k, youtubePlayable, false, ContextSelection.BROWSE, episodeId)

    private enum class ContextSelection { TAIL, BROWSE }

    private fun contextSelection(
        spec: PlayContextSpec,
        anchorEpisodeId: Long?,
        anchorSortDate: Long?,
        k: Int,
        youtubePlayable: Boolean,
        boundBySubscription: Boolean,
        selection: ContextSelection,
        episodeId: Long?,
    ): RoomRawQuery {
        require(k >= 0) { "context selection k must be >= 0: $k" }
        require(spec.type != ContextType.EXTERNAL) { "EXTERNAL contexts have no tail (05)" }
        require((anchorEpisodeId == null) == (anchorSortDate == null)) {
            "the keyset anchor is a (sortDate, id) pair: $anchorSortDate / $anchorEpisodeId"
        }

        val where = mutableListOf<String>()
        val args = mutableListOf<Long>()
        val from =
            if (spec.type == ContextType.ALL) {
                // As in page(): CROSS JOIN pins the ordered index_episode_sortDate scan.
                where += "p.id = e.podcastId"
                where += "p.includeInAll = 1"
                "episode e CROSS JOIN podcast p"
            } else {
                "episode e JOIN podcast p ON p.id = e.podcastId"
            }
        when (spec.type) {
            ContextType.ALL, ContextType.DOWNLOADS, ContextType.EXTERNAL -> {
                Unit
            }

            ContextType.UNGROUPED -> {
                where += UNGROUPED
            }

            ContextType.GROUP -> {
                where += "e.podcastId IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = ?)"
                args += requireNotNull(spec.contextId) { "GROUP context without contextId" }
            }

            ContextType.PODCAST -> {
                where += "e.podcastId = ?"
                args += requireNotNull(spec.contextId) { "PODCAST context without contextId" }
            }
        }
        if (spec.type == ContextType.DOWNLOADS || spec.filterFlags and FilterFlagBits.DOWNLOADED != 0) {
            where += "d.state = 'COMPLETED'"
        }
        if (spec.filterFlags and FilterFlagBits.IN_PROGRESS != 0) {
            where += "s.startedAt IS NOT NULL AND s.playedAt IS NULL"
        }
        when (spec.mediaFilter) {
            MediaFilter.AUDIO -> where += "e.isVideo = 0"
            MediaFilter.VIDEO -> where += "e.isVideo = 1"
            MediaFilter.ALL -> Unit
        }
        where += VISIBLE
        where += "e.availability = 'AVAILABLE'"
        where += "s.playedAt IS NULL"
        if (selection == ContextSelection.TAIL) {
            where += "NOT EXISTS (SELECT 1 FROM queue_entry q WHERE q.episodeId = e.id)"
        }
        if (episodeId != null) {
            where += "e.id = ?"
            args += episodeId
        }
        where += "(? = 1 OR p.sourceType = 'RSS')"
        args += if (youtubePlayable) 1L else 0L
        where += "e.sortDate >= ?"
        args += spec.minSortDate ?: 0
        if (boundBySubscription && spec.type == ContextType.GROUP) {
            where += "e.sortDate >= p.subscribedAt"
        }
        if (anchorSortDate != null && anchorEpisodeId != null) {
            val op = if (spec.order == FeedOrder.NEWEST_FIRST) "<" else ">"
            where += "(e.sortDate, e.id) $op (?, ?)"
            args += anchorSortDate
            args += anchorEpisodeId
        }
        val dir = if (spec.order == FeedOrder.NEWEST_FIRST) "DESC" else "ASC"
        args += k.toLong()
        return boundQuery(
            "SELECT e.id AS episodeId, e.sortDate, e.podcastId FROM $from" +
                " LEFT JOIN episode_state s ON s.episodeId = e.id" +
                " LEFT JOIN download d ON d.episodeId = e.id" +
                " WHERE ${where.joinToString(" AND ")}" +
                " ORDER BY e.sortDate $dir, e.id $dir LIMIT ?",
            args,
        )
    }

    // Only the projection differs: confirmation and the transactional write cannot drift.
    // Badge windows and AVAILABLE-only counting deliberately do not constrain this bulk action (02).
    private fun unplayedSelection(
        columns: String,
        source: FeedSource,
        sortDateBefore: Long?,
    ): RoomRawQuery {
        val where = mutableListOf(VISIBLE, "s.playedAt IS NULL")
        val args = mutableListOf<Long>()
        when (source) {
            FeedSource.All -> {
                where += "p.includeInAll = 1"
            }

            FeedSource.Ungrouped -> {
                where += UNGROUPED
            }

            is FeedSource.Group -> {
                where += "e.podcastId IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = ?)"
                args += source.groupId
            }

            is FeedSource.Podcast -> {
                where += "e.podcastId = ?"
                args += source.podcastId
            }
        }
        sortDateBefore?.let {
            where += "e.sortDate < ?"
            args += it
        }
        return boundQuery(
            "SELECT $columns FROM episode e JOIN podcast p ON p.id = e.podcastId" +
                " LEFT JOIN episode_state s ON s.episodeId = e.id WHERE ${where.joinToString(" AND ")}",
            args,
        )
    }

    private const val UNGROUPED =
        "NOT EXISTS (SELECT 1 FROM podcast_group_member m WHERE m.podcastId = e.podcastId)"

    private fun boundQuery(
        sql: String,
        args: List<Long>,
    ): RoomRawQuery = RoomRawQuery(sql) { st -> args.forEachIndexed { i, v -> st.bindLong(i + 1, v) } }
}
