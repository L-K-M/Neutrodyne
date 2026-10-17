// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.testing.database.FeedQueryProbe
import ch.lkmc.neutrodyne.core.testing.database.SeedDatabase
import ch.lkmc.neutrodyne.core.testing.database.Seeded
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

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
}
