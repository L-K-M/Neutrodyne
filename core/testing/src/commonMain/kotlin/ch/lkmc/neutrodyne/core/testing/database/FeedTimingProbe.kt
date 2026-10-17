// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import androidx.paging.PagingSource
import ch.lkmc.neutrodyne.core.database.EpisodeRowProjection
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/** R2.9/PB6-PB8 recording only. Budgets apply on the reference phone, never CI hosts/GMD. */
object FeedTimingProbe {
    private const val RUNS = 20
    private const val FIRST_PAGE_SIZE = 80
    private const val LATER_PAGE_SIZE = 40
    private val LATER_OFFSETS = listOf(FIRST_PAGE_SIZE, 800)

    /** Seeds the full 300/50k/20 fixture outside every timed region. Returns one JSON record. */
    suspend fun record(db: NeutrodyneDatabase): String {
        val seeded = SeedDatabase().install(db)
        val group = seeded.matrixSources().filterIsInstance<FeedSource.Group>().first()
        val results = mutableListOf<String>()
        for ((name, feed) in listOf("group" to group, "all" to FeedSource.All)) {
            val expected = seeded.feedRows(feed, FeedFilters(), FeedOrder.NEWEST_FIRST)
            assertTrue(expected.size >= LATER_OFFSETS.last() + LATER_PAGE_SIZE, "fixture must fill later pages")
            val firstSamples = mutableListOf<Double>()
            val laterSamples = LATER_OFFSETS.associateWith { mutableListOf<Double>() }
            val query = FeedQueryBuilder.page(feed, FeedFilters(), FeedOrder.NEWEST_FIRST)
            repeat(RUNS) {
                // Every refresh gets a new source, so Room cannot reuse its cached item count.
                // Appends use that same source and measure only the later LIMIT/OFFSET page.
                val source = db.feedDao().page(query)
                try {
                    val first =
                        measureTimedValue {
                            source.load(
                                PagingSource.LoadParams.Refresh<Int>(null, FIRST_PAGE_SIZE, false),
                            )
                        }
                    firstSamples += first.duration.inWholeNanoseconds / 1_000_000.0
                    assertPage(first.value, expected, offset = 0, size = FIRST_PAGE_SIZE)
                    for (offset in LATER_OFFSETS) {
                        val later =
                            measureTimedValue {
                                source.load(
                                    PagingSource.LoadParams.Append(offset, LATER_PAGE_SIZE, false),
                                )
                            }
                        laterSamples.getValue(offset) += later.duration.inWholeNanoseconds / 1_000_000.0
                        assertPage(later.value, expected, offset, LATER_PAGE_SIZE)
                    }
                } finally {
                    source.invalidate()
                }
            }
            results +=
                metric("${name}_first", firstSamples, FIRST_PAGE_SIZE, 0, if (feed is FeedSource.Group) 60 else 100)
            for ((offset, samples) in laterSamples) {
                results += metric("${name}_later_$offset", samples, LATER_PAGE_SIZE, offset, 20)
            }
        }
        return """{"benchmark":"FeedQueryTimingTest","recordOnly":true,"driver":"bundled","storage":"file-wal","seed":42,"podcasts":300,"episodes":50000,"groups":20,"groupId":${group.groupId},"runs":$RUNS,"metrics":[${results.joinToString(
            ",",
        )}]}"""
    }

    private fun assertPage(
        result: PagingSource.LoadResult<Int, EpisodeRowProjection>,
        expected: List<EpisodeRowProjection>,
        offset: Int,
        size: Int,
    ) {
        val page = assertIs<PagingSource.LoadResult.Page<Int, EpisodeRowProjection>>(result)
        assertEquals(expected.drop(offset).take(size), page.data, "page at offset $offset")
        assertNotNull(page.nextKey, "fixture must allow a real subsequent load")
    }

    private fun metric(
        name: String,
        samples: List<Double>,
        rows: Int,
        offset: Int,
        budgetMs: Int,
    ): String {
        assertEquals(RUNS, samples.size)
        val ordered = samples.sorted()
        val median = (ordered[RUNS / 2 - 1] + ordered[RUNS / 2]) / 2
        return """{"name":"$name","rows":$rows,"offset":$offset,"budgetMs":$budgetMs,"medianMs":$median,"samplesMs":[${samples.joinToString(
            ",",
        )}]}"""
    }
}
