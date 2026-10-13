// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import ch.lkmc.neutrodyne.core.data.repo.FeedRepositoryImpl
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.EpisodeType
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `FeedRepositoryImpl` (05 Group feeds, M1a slice) over a real bundled-driver Room database:
 * `pagedFeed` goes through `Pager`/`FeedDao.page` end to end, and `setFeedOrder` writes the
 * persisted order columns. `FeedPageTest` (in `:core:database`) covers SQL semantics; these cover
 * the repository seam — projection→model mapping, the `includeInAll`/`Podcast` split, ordering,
 * Room invalidation of the live flow and the order-writer routing.
 */
class FeedRepositoryImplTest {
    private fun repository(db: NeutrodyneDatabase) = FeedRepositoryImpl(db)

    /** Full projection→model mapping: every UI-relevant column lands on the right `EpisodeRow` field. */
    @Test
    fun pagedFeedMapsProjectionToEpisodeRow() =
        runTest {
            val db = newDb()
            try {
                val podcast =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-map", title = "Custom") { copy(customTitle = "Renamed") },
                    )
                val episode =
                    insertEpisode(db, podcast, "g:map", sortDate = 500) {
                        copy(
                            pubDate = 400,
                            durationMs = 1_000,
                            isVideo = true,
                            isShort = false,
                            episodeType = EpisodeType.BONUS,
                            episodeDisplay = "B1",
                            externalMediaId = "ext-1",
                            isNew = true,
                            imageUrl = "https://img.example.com/e.png",
                            artworkKey = "e-art",
                        )
                    }
                db.episodeStateDao().upsert(
                    episodeStateEntity(episode) {
                        copy(startedAt = 10, playedAt = 20, isFavorite = true, measuredDurationMs = 2_000)
                    },
                )
                db.downloadDao().insert(downloadEntity(episode, state = DownloadState.COMPLETED))
                db.artworkDao().upsert(ArtworkEntity(key = "e-art", version = 3, avgArgb = 0xFF112233.toInt()))
                db.artworkDao().upsert(
                    ArtworkEntity(key = podcastArtworkKey(db, podcast), version = 7, avgArgb = 0xFF445566.toInt()),
                )

                val rows =
                    repository(db)
                        .pagedFeed(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST)
                        .asSnapshot()

                assertEquals(1, rows.size)
                val row = rows.single()
                assertEquals(episode, row.id)
                assertEquals(podcast, row.podcastId)
                assertEquals("g:map", row.title)
                assertEquals("Renamed", row.podcastTitle, "COALESCE(customTitle, title)")
                assertEquals(500, row.sortDate)
                assertEquals(400, row.pubDate)
                assertEquals(2_000, row.durationMs, "measured duration wins over feed duration")
                assertTrue(row.isVideo)
                assertEquals(EpisodeType.BONUS, row.episodeType)
                assertEquals("B1", row.episodeDisplay)
                assertEquals("ext-1", row.externalMediaId)
                assertTrue(row.isNew)
                assertEquals("e-art", row.artwork.key)
                assertEquals("https://img.example.com/e.png", row.artwork.url)
                assertEquals(3, row.artwork.version)
                assertEquals(0xFF112233.toInt(), row.artworkAvgArgb)
                assertEquals(podcastArtworkKey(db, podcast), row.podcastArtwork.key)
                assertEquals(7, row.podcastArtwork.version)
                assertEquals(0xFF445566.toInt(), row.podcastArtworkAvgArgb)
                assertEquals(20L, row.playedAt)
                assertEquals(10L, row.startedAt)
                assertTrue(row.isFavorite)
                assertEquals(DownloadState.COMPLETED, row.downloadState)
            } finally {
                db.close()
            }
        }

    /** `includeInAll = 0` removes the podcast from All but its own Podcast source still lists it. */
    @Test
    fun includeInAllExcludesFromAllButNotPodcast() =
        runTest {
            val db = newDb()
            try {
                val inAll = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-in"))
                val hidden =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-hidden") { copy(includeInAll = false) },
                    )
                val kept = insertEpisode(db, inAll, "g:kept", sortDate = 100)
                val hiddenRow = insertEpisode(db, hidden, "g:hidden", sortDate = 200)

                val repo = repository(db)
                assertEquals(
                    listOf(kept),
                    repo
                        .pagedFeed(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST)
                        .asSnapshot()
                        .map { it.id },
                )
                assertEquals(
                    listOf(hiddenRow),
                    repo
                        .pagedFeed(FeedSource.Podcast(hidden), FeedFilters(), FeedOrder.NEWEST_FIRST)
                        .asSnapshot()
                        .map { it.id },
                )
            } finally {
                db.close()
            }
        }

    /** Ordering is `sortDate` then `id` in the requested direction — stable, both ways. */
    @Test
    fun orderIsStableSortDateThenIdBothDirections() =
        runTest {
            val db = newDb()
            try {
                val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-ord"))
                val a = insertEpisode(db, p, "g:a", sortDate = 100)
                val b = insertEpisode(db, p, "g:b", sortDate = 200)
                val c = insertEpisode(db, p, "g:c", sortDate = 200)
                val d = insertEpisode(db, p, "g:d", sortDate = 300)

                val repo = repository(db)
                assertEquals(
                    listOf(d, c, b, a),
                    repo
                        .pagedFeed(FeedSource.Podcast(p), FeedFilters(), FeedOrder.NEWEST_FIRST)
                        .asSnapshot()
                        .map { it.id },
                    "sortDate DESC, id DESC breaks the 200-tie",
                )
                assertEquals(
                    listOf(a, b, c, d),
                    repo
                        .pagedFeed(FeedSource.Podcast(p), FeedFilters(), FeedOrder.OLDEST_FIRST)
                        .asSnapshot()
                        .map { it.id },
                )
            } finally {
                db.close()
            }
        }

    /** A filter flag applies through the repository path (SQL detail is `FeedPageTest`'s). */
    @Test
    fun filtersApplyThroughThePager() =
        runTest {
            val db = newDb()
            try {
                val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-filt"))
                val played = insertEpisode(db, p, "g:played", sortDate = 10)
                insertEpisode(db, p, "g:open", sortDate = 20)
                db.episodeStateDao().upsert(episodeStateEntity(played, playedAt = 1))

                val rows =
                    repository(db)
                        .pagedFeed(FeedSource.All, FeedFilters(unplayedOnly = true), FeedOrder.NEWEST_FIRST)
                        .asSnapshot()
                assertEquals(listOf("g:open"), rows.map { it.title })
            } finally {
                db.close()
            }
        }

    /** A Room write to an observed table regenerates the live `Pager` flow with the new state. */
    @Test
    fun roomWriteInvalidatesAndReloadsTheLiveFlow() =
        runTest(timeout = 30.seconds) {
            val db = newDb()
            try {
                val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-inv"))
                val episode = insertEpisode(db, p, "g:inv", sortDate = 100)
                val flow = repository(db).pagedFeed(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST)

                val emissions = Channel<PagingData<EpisodeRow>>(Channel.UNLIMITED)
                val collectJob = backgroundScope.launch { flow.collect { emissions.send(it) } }
                try {
                    // Presenting generation 1 runs its first PagingSource.load(), which registers
                    // the source with Room's invalidation tracker — until then no observer exists
                    // and a write cannot invalidate it.
                    val first = emissions.receive()
                    assertEquals(listOf(episode), flowOf(first).asSnapshot().map { it.id })
                    // A write to an observed entity table invalidates the PagingSource; the same
                    // flow then emits a fresh PagingData generation carrying the new state. If
                    // invalidation is broken this receive suspends until runTest's timeout.
                    db.episodeStateDao().upsert(episodeStateEntity(episode, playedAt = 1))
                    val second = emissions.receive()
                    assertNotSame(first, second)
                    assertEquals(
                        1L,
                        flowOf(second).asSnapshot().single().playedAt,
                        "the regenerated page carries the written state",
                    )
                } finally {
                    collectJob.cancel()
                }
            } finally {
                db.close()
            }
        }

    /** `FeedSource.Podcast` writes `podcast.episodeOrder` (05). */
    @Test
    fun setFeedOrderPodcastWritesEpisodeOrder() =
        runTest {
            val db = newDb()
            try {
                val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-ord1"))
                repository(db).setFeedOrder(FeedSource.Podcast(p), FeedOrder.OLDEST_FIRST)
                assertEquals(FeedOrder.OLDEST_FIRST, db.podcastDao().byId(p)?.episodeOrder)
            } finally {
                db.close()
            }
        }

    /** `FeedSource.Group` writes `podcast_group.feedOrder` through `GroupDao` (05; M2 feeds it). */
    @Test
    fun setFeedOrderGroupWritesGroupFeedOrder() =
        runTest {
            val db = newDb()
            try {
                val group = db.groupDao().insert(groupEntity(orderKey = "a"))
                repository(db).setFeedOrder(FeedSource.Group(group), FeedOrder.OLDEST_FIRST)
                assertEquals(FeedOrder.OLDEST_FIRST, db.groupDao().groupById(group)?.feedOrder)
            } finally {
                db.close()
            }
        }

    /** `All` and `Ungrouped` are fixed `NEWEST_FIRST` in v1 — both throw `IllegalArgumentException`. */
    @Test
    fun setFeedOrderVirtualSourcesThrow() =
        runTest {
            val db = newDb()
            try {
                val repo = repository(db)
                assertFailsWith<IllegalArgumentException> {
                    repo.setFeedOrder(FeedSource.All, FeedOrder.OLDEST_FIRST)
                }
                assertFailsWith<IllegalArgumentException> {
                    repo.setFeedOrder(FeedSource.Ungrouped, FeedOrder.NEWEST_FIRST)
                }
            } finally {
                db.close()
            }
        }

    private suspend fun insertEpisode(
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
                        identityKey = key,
                        title = key,
                        sortDate = sortDate,
                        block = block,
                    ),
                ),
            ).single()

    private suspend fun podcastArtworkKey(
        db: NeutrodyneDatabase,
        podcastId: Long,
    ): String = checkNotNull(db.podcastDao().byId(podcastId)).artworkKey
}
