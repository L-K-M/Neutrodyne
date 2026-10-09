// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.paging.PagingSource
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `FeedDao.page` for the M1a sources (02 Feed pages): All and Podcast, the visibility rule,
 * every filter flag, and both orderings. All values are bound, never concatenated.
 */
class FeedPageTest {
    @Test
    fun allFeedSkipsPodcastsExcludedFromAllAndOrdersNewestFirst() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val inAll = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-in"))
                val hidden =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-hidden") { copy(includeInAll = false) },
                    )
                val old = insert(db, inAll, "e-old", sortDate = 100)
                val mid = insert(db, inAll, "e-mid", sortDate = 200)
                val new = insert(db, inAll, "e-new", sortDate = 300)
                insert(db, hidden, "e-hidden", sortDate = 400)

                val page = load(db, FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST)
                assertEquals(listOf(new, mid, old), page.data.map { it.id })
            } finally {
                db.close()
            }
        }

    @Test
    fun podcastFeedReturnsOnlyThatPodcastOldestFirst() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val a = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-a"))
                val b = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-b"))
                val a1 = insert(db, a, "a1", sortDate = 10)
                val a2 = insert(db, a, "a2", sortDate = 20)
                insert(db, b, "b1", sortDate = 15)

                val page = load(db, FeedSource.Podcast(a), FeedFilters(), FeedOrder.OLDEST_FIRST)
                assertEquals(listOf(a1, a2), page.data.map { it.id })
            } finally {
                db.close()
            }
        }

    @Test
    fun filtersNarrowThePage() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val p = db.podcastDao().insertPodcast(podcastEntity())
                val played = insert(db, p, "played", sortDate = 10)
                val video = insert(db, p, "video", sortDate = 20) { copy(isVideo = true) }
                val inProgress = insert(db, p, "progress", sortDate = 30)
                val downloaded = insert(db, p, "dl", sortDate = 40)
                insert(db, p, "plain", sortDate = 50)

                db.episodeStateDao().upsert(episodeStateEntity(played, playedAt = 1))
                db.episodeStateDao().upsert(episodeStateEntity(inProgress) { copy(startedAt = 1) })
                db.downloadDao().insert(downloadEntity(downloaded, state = DownloadState.COMPLETED))

                suspend fun ids(f: FeedFilters) = load(db, FeedSource.All, f, FeedOrder.NEWEST_FIRST).data.map { it.id }

                assertEquals(listOf(downloaded), ids(FeedFilters(downloadedOnly = true)))
                assertEquals(listOf(video), ids(FeedFilters(media = MediaFilter.VIDEO)))
                assertEquals(
                    ids(FeedFilters()).filter { it != video },
                    ids(FeedFilters(media = MediaFilter.AUDIO)),
                    "AUDIO drops only the video row",
                )
                assertEquals(
                    listOf(inProgress),
                    ids(FeedFilters(inProgressOnly = true)),
                    "inProgressOnly = started, never finished",
                )
                assertEquals(
                    ids(FeedFilters()).filter { it != played },
                    ids(FeedFilters(unplayedOnly = true)),
                    "unplayedOnly drops only the played row",
                )
                assertEquals(
                    ids(FeedFilters()).filter { id -> id != played && id != video && id != inProgress },
                    ids(FeedFilters(minSortDate = 40)),
                    "minSortDate is a lower bound on sortDate",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun visibilityHidesShortsWithoutTheVariantAndUnavailableStates() =
        runTest {
            val db = TestDb.inMemory()
            try {
                // youtubeVariants = LONG_FORM only: a Short must be hidden (02 PO-9 default).
                val noShorts =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-noshort") { copy(youtubeVariants = 1) },
                    )
                // LONG_FORM | SHORTS: the same row is visible.
                val withShorts =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-shorts") { copy(youtubeVariants = 3) },
                    )
                insert(db, noShorts, "hidden-short", sortDate = 10) { copy(isShort = true) }
                val full = insert(db, noShorts, "full", sortDate = 20)
                val shortShown = insert(db, withShorts, "short", sortDate = 30) { copy(isShort = true) }
                insert(db, noShorts, "upcoming", sortDate = 40) { copy(availability = Availability.UPCOMING) }
                insert(db, noShorts, "live", sortDate = 50) { copy(availability = Availability.LIVE) }
                insert(db, noShorts, "member", sortDate = 60) { copy(availability = Availability.MEMBERS_ONLY) }

                val page = load(db, FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST)
                assertEquals(
                    setOf(shortShown, full),
                    page.data.map { it.id }.toSet(),
                    "short without the variant and the unavailable states must be hidden",
                )
            } finally {
                db.close()
            }
        }

    private suspend fun insert(
        db: NeutrodyneDatabase,
        podcastId: Long,
        key: String,
        sortDate: Long,
        block: EpisodeEntity.() -> EpisodeEntity = { this },
    ): Long =
        db
            .ingestDao()
            .insertEpisodes(
                listOf(
                    episodeEntity(
                        podcastId = podcastId,
                        identityKey = "g:$key",
                        title = key,
                        sortDate = sortDate,
                        block = block,
                    ),
                ),
            ).single()

    private suspend fun load(
        db: NeutrodyneDatabase,
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): PagingSource.LoadResult.Page<Int, EpisodeRowProjection> {
        val result =
            db.feedDao().page(FeedQueryBuilder.page(source, filters, order)).load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false),
            )
        assertIs<PagingSource.LoadResult.Page<Int, EpisodeRowProjection>>(result)
        return result
    }
}
