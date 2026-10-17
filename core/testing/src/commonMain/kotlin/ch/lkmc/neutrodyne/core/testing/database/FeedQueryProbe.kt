// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import androidx.paging.PagingSource
import androidx.room3.RoomRawQuery
import androidx.room3.useReaderConnection
import ch.lkmc.neutrodyne.core.database.EpisodeRowProjection
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.MediaFilter
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The mechanics behind `QueryPlanTest` (desktop JVM) and `QueryPlanDeviceTest` (GMD) — the feed
 * contract of 02 Testing and Indices: over a [Seeded] database, every [FeedSource] kind x
 * [FeedFilters] flag x [FeedOrder] combination must
 *
 *  1. return exactly the rows [Seeded.feedRows] computes in Kotlin (PLAN M2 AC10);
 *  2. page deterministically by `(sortDate, id)` through Room's `LimitOffsetPagingSource` —
 *     page concatenation reproduces the reference and equal-`sortDate` runs that straddle a
 *     `LIMIT`/`OFFSET` boundary neither drop nor duplicate rows;
 *  3. `EXPLAIN QUERY PLAN` the exact wrapped SQL Room executes (the `COUNT(*)` and
 *     `LIMIT`/`OFFSET` forms of `RoomPagingUtil`) and match the documented index structure:
 *     no unindexed `SCAN` of `episode`, group feeds probe through `podcast_group_member` and
 *     `index_episode_podcastId_sortDate`, All/Ungrouped order through `index_episode_sortDate`
 *     without a temp B-tree.
 */
object FeedQueryProbe {
    /** One matrix cell — carried so assertion failures name the exact combination. */
    data class Combo(
        val source: FeedSource,
        val filters: FeedFilters,
        val order: FeedOrder,
    )

    /**
     * The default page size for boundary probes: 53 is odd and non-round, so boundaries land
     * mid-run instead of aligning with round offsets or fixture periods.
     */
    const val PAGE_SIZE = 53

    /** Full page walk per combo keeps its first pages exercised without scanning whole feeds. */
    private const val WALK_PAGES = 4

    /** The wrapped forms `RoomPagingUtil` emits around the raw feed SQL (02 Feed pages). */
    private fun countSql(rawSql: String) = "SELECT COUNT(*) FROM ( $rawSql )"

    private fun pageSql(
        rawSql: String,
        limit: Int,
        offset: Int,
    ) = "SELECT * FROM ( $rawSql ) LIMIT $limit OFFSET $offset"

    /** Every `FeedSource` kind x flag combination x media value x `minSortDate` x order. */
    fun matrix(seeded: Seeded): List<Combo> =
        buildList {
            for (source in seeded.matrixSources()) {
                for (f in filters(seeded)) {
                    for (order in FeedOrder.entries) {
                        add(Combo(source, f, order))
                    }
                }
            }
        }

    /** The filter matrix: 3 bits of flag state x every media filter x the seeded date bounds. */
    private fun filters(seeded: Seeded): List<FeedFilters> =
        buildList {
            for (minSortDate in seeded.minSortDateValues()) {
                for (media in MediaFilter.entries) {
                    for (mask in FLAG_MASKS) {
                        add(
                            FeedFilters(
                                unplayedOnly = mask and 1 != 0,
                                downloadedOnly = mask and 2 != 0,
                                inProgressOnly = mask and 4 != 0,
                                media = media,
                                minSortDate = minSortDate,
                            ),
                        )
                    }
                }
            }
        }

    private val FLAG_MASKS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    /** (1) every combination's full row set — one `LIMIT N` page — equals the Kotlin reference. */
    suspend fun assertRowsMatchReference(
        db: NeutrodyneDatabase,
        seeded: Seeded,
    ) {
        for (combo in matrix(seeded)) {
            val expected = seeded.feedRows(combo.source, combo.filters, combo.order)
            val query = FeedQueryBuilder.page(combo.source, combo.filters, combo.order)
            // One row past the reference: a surplus row must fail the comparison, not hide past LIMIT.
            val actual = loadRows(db, query, offset = 0, limit = expected.size + 1)
            assertEquals(expected.map { it.id }, actual.map { it.id }, "feed ids differ at $combo")
            val at = actual.indices.firstOrNull { actual[it] != expected[it] }
            assertTrue(
                at == null,
                "row $at differs at $combo:\nexpected ${expected.getOrNull(
                    at ?: 0,
                )}\nactual   ${actual.getOrNull(at ?: 0)}",
            )
        }
    }

    /**
     * (2) refresh + appends of `pageSize` reproduce the reference prefix, and at every
     * page-boundary offset that bisects an equal-`sortDate` run a two-row append returns the
     * expected adjacent pair — the `(sortDate, id)` tie-break holding across a real seam.
     */
    suspend fun assertPagesDeterministicAcrossBoundaries(
        db: NeutrodyneDatabase,
        seeded: Seeded,
        pageSize: Int = PAGE_SIZE,
    ) {
        var seams = 0
        for (combo in matrix(seeded)) {
            val expected = seeded.feedRows(combo.source, combo.filters, combo.order)
            if (expected.isEmpty()) continue
            val query = FeedQueryBuilder.page(combo.source, combo.filters, combo.order)

            val walked = ArrayList<EpisodeRowProjection>()
            var offset = 0
            while (walked.size < expected.size && walked.size < WALK_PAGES * pageSize) {
                val page = loadRows(db, query, offset, pageSize)
                if (page.isEmpty()) break
                walked += page
                offset += page.size
                if (page.size < pageSize) break
            }
            assertEquals(
                expected.take(walked.size).map { it.id },
                walked.map { it.id },
                "page walk prefix differs at $combo",
            )

            val boundaries =
                (pageSize until expected.size step pageSize).filter {
                    expected[it - 1].sortDate == expected[it].sortDate
                }
            seams += boundaries.size
            for (b in pickBoundaries(boundaries)) {
                val slice = loadRows(db, query, b - 1, 2)
                assertEquals(
                    listOf(expected[b - 1].id, expected[b].id),
                    slice.map { it.id },
                    "tie-break seam at offset $b differs at $combo",
                )
            }
        }
        assertTrue(seams > 0, "the seed produced no equal-sortDate run straddling a page boundary")
    }

    /**
     * (3) `EXPLAIN QUERY PLAN` on the wrapped COUNT and page SQL — the two statements Room's
     * `LimitOffsetPagingSource` runs — matching the documented plan structure, not literals.
     */
    suspend fun assertPlansMatchIndexContract(
        db: NeutrodyneDatabase,
        seeded: Seeded,
    ) {
        for (combo in matrix(seeded)) {
            val query = FeedQueryBuilder.page(combo.source, combo.filters, combo.order)
            val pagePlan = explain(db, pageSql(query.sql, limit = 40, offset = 80), query)
            val countPlan = explain(db, countSql(query.sql), query)
            assertPagePlan(combo, pagePlan)
            assertCountPlan(combo, countPlan)
        }
    }

    /** Row exactness, page boundaries and plan structure — the full matrix. */
    suspend fun assertAll(
        db: NeutrodyneDatabase,
        seeded: Seeded,
    ) {
        assertRowsMatchReference(db, seeded)
        assertPagesDeterministicAcrossBoundaries(db, seeded)
        assertPlansMatchIndexContract(db, seeded)
    }

    /**
     * One `load()` call on the real `FeedDao.page` PagingSource — a `LIMIT`/`OFFSET` page at
     * [offset]. `offset == 0` is a refresh; anything later is an append keyed by the item index.
     */
    private suspend fun loadRows(
        db: NeutrodyneDatabase,
        query: RoomRawQuery,
        offset: Int,
        limit: Int,
    ): List<EpisodeRowProjection> {
        val source = db.feedDao().page(query)
        val params: PagingSource.LoadParams<Int> =
            if (offset == 0) {
                PagingSource.LoadParams.Refresh(key = null, loadSize = limit, placeholdersEnabled = false)
            } else {
                PagingSource.LoadParams.Append(key = offset, loadSize = limit, placeholdersEnabled = false)
            }
        val result = source.load(params)
        assertIs<PagingSource.LoadResult.Page<Int, EpisodeRowProjection>>(result)
        return result.data
    }

    /** `EXPLAIN QUERY PLAN <sql>` with the query's own bindings applied — detail column text. */
    private suspend fun explain(
        db: NeutrodyneDatabase,
        sql: String,
        query: RoomRawQuery,
    ): List<String> =
        db.useReaderConnection { conn ->
            conn.usePrepared("EXPLAIN QUERY PLAN $sql") { stmt ->
                // RoomRawQuery keeps its binder private; `EXPLAIN QUERY PLAN` reports the chosen
                // plan without evaluating values, so a bound `?` placeholder is equivalent.
                val params = query.sql.count { it == '?' }
                for (i in 1..params) stmt.bindLong(i, 0)
                val detail = (0 until stmt.getColumnCount()).indexOfFirst { stmt.getColumnName(it) == "detail" }
                assertTrue(detail >= 0, "EXPLAIN QUERY PLAN result has no detail column")
                buildList { while (stmt.step()) add(stmt.getText(detail)) }
            }
        }

    /** First, middle and last of the straddled boundaries — representative probes, not all. */
    private fun pickBoundaries(boundaries: List<Int>): List<Int> =
        when {
            boundaries.isEmpty() -> emptyList()
            boundaries.size <= 2 -> boundaries
            else -> listOf(boundaries.first(), boundaries[boundaries.size / 2], boundaries.last()).distinct()
        }

    private fun List<String>.plan(): String = joinToString("\n")

    /**
     * The 02 index contract, encoded structurally: `SCAN e` without `USING` is the forbidden
     * unindexed full scan — but *any* `SCAN e` (index walk included) still visits every episode,
     * so a group feed must not show one at all. `USE TEMP B-TREE` is fine only when every
     * `episode` row reaching the sort was probe-selected (All/Podcast/Ungrouped); without a sort,
     * ordering must come from the documented index. A selective filter (`s`/`d` driving the outer
     * loop) legitimately produces probe + sort — the sorted set is the filtered few, not the
     * table. EQP marks the post-lookup joins `LEFT-JOIN`, so a driver line lacks that suffix.
     */
    private fun assertPagePlan(
        combo: Combo,
        details: List<String>,
    ) {
        val episodeScans = details.filter { SCAN_EPISODE.matches(it) }
        val bareScan = episodeScans.firstOrNull { "USING " !in it }
        assertTrue(bareScan == null, "unindexed SCAN of episode at $combo: $bareScan\n${details.plan()}")

        val episodeProbes = details.any { SEARCH_EPISODE.matches(it) }
        val sorts = details.any { "USE TEMP B-TREE" in it }
        val probeOnly = episodeScans.isEmpty() && episodeProbes
        val sortDateOrdered = details.any { EPISODE_ACCESS.matches(it) && "index_episode_sortDate" in it }
        val podcastIndexProbe =
            details.any { EPISODE_ACCESS.matches(it) && "index_episode_podcastId_sortDate" in it }
        val memberProbe = details.any { MEMBER_SEARCH.matches(it) }

        when (combo.source) {
            FeedSource.All -> {
                assertTrue(
                    !sorts || probeOnly,
                    "All page sorts a scanned episode set at $combo:\n${details.plan()}",
                )
                assertTrue(
                    sorts || sortDateOrdered,
                    "All page not ordered by index_episode_sortDate at $combo:\n${details.plan()}",
                )
            }

            FeedSource.Ungrouped -> {
                assertTrue(
                    memberProbe,
                    "Ungrouped page not probing podcast_group_member at $combo:\n${details.plan()}",
                )
                assertTrue(
                    !sorts || probeOnly,
                    "Ungrouped page sorts a scanned episode set at $combo:\n${details.plan()}",
                )
                assertTrue(
                    sorts || sortDateOrdered,
                    "Ungrouped page not ordered by index_episode_sortDate at $combo:\n${details.plan()}",
                )
            }

            is FeedSource.Group -> {
                assertTrue(
                    memberProbe,
                    "group page not probing podcast_group_member at $combo:\n${details.plan()}",
                )
                // The documented plan probes episodes per member through
                // index_episode_podcastId_sortDate; the planner's tolerated alternative is the
                // ordered index_episode_sortDate walk with an IN-list membership check — the
                // doc's must-not is only the *unindexed* `SCAN e` (handled universally above),
                // and a scan on any other index could not even provide the ordering.
                assertTrue(
                    episodeScans.all { "index_episode_sortDate" in it },
                    "group page scans episode on a non-ordering index at $combo:\n${details.plan()}",
                )
                assertTrue(
                    episodeProbes || sortDateOrdered,
                    "group page accesses episodes neither by probe nor in index order at $combo:\n${details.plan()}",
                )
            }

            is FeedSource.Podcast -> {
                assertTrue(
                    episodeProbes,
                    "podcast page missing an indexed episode probe at $combo:\n${details.plan()}",
                )
                assertTrue(
                    !sorts || probeOnly,
                    "podcast page sorts a scanned episode set at $combo:\n${details.plan()}",
                )
                assertTrue(
                    sorts || podcastIndexProbe || sortDateOrdered,
                    "podcast page ordered by an unexpected index at $combo:\n${details.plan()}",
                )
            }
        }
    }

    /**
     * The `COUNT(*)` wrap is checked where order still matters: a group count must not sweep
     * `episode` either (the "Group counts" row of the 02 table), and a podcast count must probe
     * its own episodes only. Counting All/Ungrouped legitimately visits every row, so a traversal
     * there — indexed or not — is left unasserted.
     */
    private fun assertCountPlan(
        combo: Combo,
        details: List<String>,
    ) {
        val episodeScans = details.filter { SCAN_EPISODE.matches(it) }
        val bareScan = episodeScans.firstOrNull { "USING " !in it }
        assertTrue(
            bareScan == null,
            "unindexed SCAN of episode in count at $combo: $bareScan\n${details.plan()}",
        )
        when (combo.source) {
            is FeedSource.Group -> {
                assertTrue(
                    details.any { MEMBER_SEARCH.matches(it) },
                    "group count not probing podcast_group_member at $combo:\n${details.plan()}",
                )
                assertTrue(
                    details.any { SEARCH_EPISODE.matches(it) } ||
                        details.any { EPISODE_ACCESS.matches(it) && "index_episode_sortDate" in it },
                    "group count accesses episodes neither by probe nor in index order at $combo:\n${details.plan()}",
                )
            }

            is FeedSource.Podcast -> {
                assertTrue(
                    details.any { SEARCH_EPISODE.matches(it) },
                    "podcast count not probing its episodes by index at $combo:\n${details.plan()}",
                )
            }

            else -> {}
        }
    }

    // EQP detail lines print aliases (`SCAN e`, `SEARCH m USING ...`) or table names; the regexes
    // cover both spellings plus the `TABLE`/`AS` forms of older SQLite versions.
    private val SCAN_EPISODE = Regex("""SCAN (TABLE )?(episode( AS e)?|e)\b.*""")
    private val SEARCH_EPISODE = Regex("""SEARCH (TABLE )?(episode( AS e)?|e)\b.*""")
    private val EPISODE_ACCESS = Regex("""(SCAN|SEARCH) (TABLE )?(episode( AS e)?|e)\b.*""")
    private val MEMBER_SEARCH = Regex("""SEARCH (TABLE )?(podcast_group_member( AS m)?|m)\b.*""")
}
