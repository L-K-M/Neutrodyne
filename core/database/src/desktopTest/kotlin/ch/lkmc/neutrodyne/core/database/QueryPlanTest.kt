// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.RoomRawQuery
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FilterFlagBits
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.testing.database.FeedQueryProbe
import ch.lkmc.neutrodyne.core.testing.database.SeedDatabase
import ch.lkmc.neutrodyne.core.testing.database.Seeded
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `QueryPlanTest` (02 Testing, Indices; PLAN M2 AC1 and the row-exactness of AC10): on the seeded
 * library, every `FeedSource` kind x flag combination x media filter x `minSortDate` bound x order
 * must (a) return exactly the rows the Kotlin reference computes, (b) page deterministically by
 * `(sortDate, id)` across `LIMIT`/`OFFSET` boundaries, and (c) plan by the documented indices —
 * no unindexed `episode` scan, group feeds probing through `podcast_group_member` and
 * `index_episode_podcastId_sortDate`, All/Podcast ordering without a temp B-tree.
 *
 * The GMD run of the same assertions is authoritative (bundled driver); this desktop run uses the
 * same bundled driver through [TestDb]. The seed is sized so the matrix finishes quickly but still
 * leaves every predicate and page seam observable.
 */
class QueryPlanTest {
    @Test
    fun feedRowsEqualTheKotlinReferenceForEveryCombination() =
        runTest {
            val db = TestDb.inMemory()
            try {
                FeedQueryProbe.assertRowsMatchReference(db, seeded(db))
            } finally {
                db.close()
            }
        }

    @Test
    fun pagesKeepTheSortDateIdOrderAcrossBoundaries() =
        runTest {
            val db = TestDb.inMemory()
            try {
                FeedQueryProbe.assertPagesDeterministicAcrossBoundaries(db, seeded(db))
            } finally {
                db.close()
            }
        }

    @Test
    fun explainQueryPlanMatchesTheIndexContract() =
        runTest {
            val db = TestDb.inMemory()
            try {
                FeedQueryProbe.assertPlansMatchIndexContract(db, seeded(db))
            } finally {
                db.close()
            }
        }

    private suspend fun seeded(db: NeutrodyneDatabase): Seeded =
        SeedDatabase(
            podcasts = 60,
            episodes = 8_000,
            groups = 8,
            downloads = 160,
            inProgress = 60,
            favourites = 100,
            youtubeChannels = 8,
        ).install(db)

    @Test
    fun contextTailsMatchTheExistingFeedReferenceAndKeepKeysetOrder() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val seed = seeded(db)
                val queued =
                    seed.episodes
                        .filterIndexed { i, _ -> i % 977 == 0 }
                        .map { it.id }
                        .toSet()
                for (id in queued) db.queueDao().insert(QueueEntryEntity(episodeId = id, orderKey = "a0", addedAt = 0))
                val podcasts = seed.podcasts.associateBy { it.id }
                val combinations =
                    FeedQueryProbe
                        .matrix(seed)
                        .map {
                            it.copy(
                                filters = it.filters.copy(unplayedOnly = true),
                                order =
                                    when (it.source) {
                                        FeedSource.All, FeedSource.Ungrouped -> FeedOrder.NEWEST_FIRST
                                        else -> it.order
                                    },
                            )
                        }.distinct()
                for ((source, filters, order) in combinations) {
                    val spec = contextSpec(source, filters, order)
                    val base =
                        seed.feedRows(source, filters, order).filter {
                            it.availability == Availability.AVAILABLE && it.id !in queued && it.sortDate >= 0
                        }
                    for (youtube in listOf(false, true)) {
                        for (bound in setOf(false, spec.boundStartBySubscription)) {
                            val expected =
                                base.filter {
                                    (youtube || it.sourceType == SourceType.RSS) &&
                                        (!bound || it.sortDate >= podcasts.getValue(it.podcastId).subscribedAt)
                                }
                            for (anchor in listOf(null, expected.getOrNull(expected.size / 2))) {
                                val after =
                                    if (anchor == null) expected else expected.dropWhile { it.id != anchor.id }.drop(1)
                                val query =
                                    FeedQueryBuilder.contextTail(spec, anchor?.id, anchor?.sortDate, 21, youtube, bound)
                                val rows = db.feedDao().contextTail(query)
                                assertEquals(
                                    after.take(21).map { it.id },
                                    rows.map { it.episodeId },
                                    "$spec anchor=$anchor",
                                )
                                assertEquals(after.take(21).map { it.sortDate }, rows.map { it.sortDate })
                                assertEquals(after.take(21).map { it.podcastId }, rows.map { it.podcastId })
                            }
                        }
                    }
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun contextPlansUseExistingIndicesAndSurviveDeletedAnchors() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val seed = seeded(db)
                for (source in seed.matrixSources()) {
                    for (order in FeedOrder.entries) {
                        val spec = contextSpec(source, FeedFilters(unplayedOnly = true), order)
                        val head = db.feedDao().contextTail(FeedQueryBuilder.contextTail(spec, null, null, 50, true))
                        for (anchor in listOf(null, head.getOrNull(head.size / 2))) {
                            val query =
                                FeedQueryBuilder.contextTail(
                                    spec,
                                    anchor?.episodeId,
                                    anchor?.sortDate,
                                    21,
                                    false,
                                )
                            val plan =
                                db.useReaderConnection { connection ->
                                    connection.usePrepared("EXPLAIN QUERY PLAN ${query.sql}") { statement ->
                                        for (index in 1..query.sql.count { it == '?' }) statement.bindLong(index, 0)
                                        buildList { while (statement.step()) add(statement.getText(3)) }
                                    }
                                }
                            assertTrue(
                                plan.none { Regex("SCAN (TABLE )?(e|episode)\\b.*").matches(it) && "USING " !in it },
                                "$spec: $plan",
                            )
                            val indices =
                                when (source) {
                                    FeedSource.All -> listOf("index_episode_sortDate")
                                    is FeedSource.Podcast -> listOf("index_episode_podcastId_sortDate")
                                    else -> listOf("index_episode_sortDate", "index_episode_podcastId_sortDate")
                                }
                            // LIMIT can favour the ordered date range over per-podcast probes.
                            assertTrue(plan.any { detail -> indices.any { it in detail } }, "$spec: $plan")
                            if (source is FeedSource.Group || source == FeedSource.Ungrouped) {
                                assertTrue(plan.any { it.startsWith("SEARCH m ") }, "$spec: $plan")
                            }
                            assertTrue(plan.any { "index_queue_entry_episodeId" in it }, "$spec: $plan")
                        }
                    }
                }
                val spec = contextSpec(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST)
                val head = db.feedDao().contextTail(FeedQueryBuilder.contextTail(spec, null, null, 50, true))
                val anchor = head[25]
                val query = FeedQueryBuilder.contextTail(spec, anchor.episodeId, anchor.sortDate, 50, true)
                val before = db.feedDao().contextTail(query)
                assertEquals(head.drop(26), before.take(24))
                db.useWriterConnection { connection ->
                    connection.usePrepared("DELETE FROM episode WHERE id = ?") { statement ->
                        statement.bindLong(1, anchor.episodeId)
                        statement.step()
                    }
                }
                assertEquals(before, db.feedDao().contextTail(query))
            } finally {
                db.close()
            }
        }

    @Test fun contextTailRejectsExternalSpecsAndHalfAnchors() {
        val spec = contextSpec(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST)
        assertFailsWith<IllegalArgumentException> {
            FeedQueryBuilder.contextTail(spec.copy(type = ContextType.EXTERNAL), null, null, 20, true)
        }
        assertFailsWith<IllegalArgumentException> { FeedQueryBuilder.contextTail(spec, 1, null, 20, true) }
        assertFailsWith<IllegalArgumentException> { FeedQueryBuilder.contextTail(spec, null, 1, 20, true) }
    }

    private fun contextSpec(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): PlayContextSpec {
        val (type, id) =
            when (source) {
                FeedSource.All -> ContextType.ALL to null
                FeedSource.Ungrouped -> ContextType.UNGROUPED to null
                is FeedSource.Group -> ContextType.GROUP to source.groupId
                is FeedSource.Podcast -> ContextType.PODCAST to source.podcastId
            }
        return PlayContextSpec(
            type,
            id,
            order,
            filters.toFlags(),
            filters.media,
            filters.minSortDate,
            type == ContextType.GROUP && order == FeedOrder.OLDEST_FIRST,
        )
    }
}
