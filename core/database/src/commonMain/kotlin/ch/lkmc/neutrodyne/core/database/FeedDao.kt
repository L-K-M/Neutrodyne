// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Embedded
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.GroupCounts
import ch.lkmc.neutrodyne.core.model.MediaFilter
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
}

/** Empty groups have a row with zero counts, including groups whose members have no episodes. */
data class GroupCountRow(
    val groupId: Long,
    @Embedded val counts: FeedCounts,
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
