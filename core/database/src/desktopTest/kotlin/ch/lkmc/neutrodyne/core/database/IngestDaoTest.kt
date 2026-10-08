// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.EpisodeType
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 02 Ingestion support + Refresh selection: `updateFeedFields`'s null-preserving columns,
 * `touchSeen`'s day granularity, `applyFeedMetadata`'s ownership boundary, due selection order
 * and `forceDue`, and `FetchStateBatcher`'s batching.
 */
class IngestDaoTest {
    @Test
    fun updateFeedFieldsKeepsStoredValuesWhenParsedOnesAreNull() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = db.podcastDao().insertPodcast(podcastEntity())
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(
                                episodeEntity(podcastId = podcastId, identityKey = "g:1") {
                                    copy(
                                        title = "old",
                                        durationMs = 3_600_000,
                                        imageUrl = "https://img/e.png",
                                        artworkKey = "e-key",
                                        chaptersUrl = "https://chaps",
                                        chaptersType = "application/json+chapters",
                                    )
                                },
                            ),
                        ).single()

                db.ingestDao().updateFeedFields(
                    feedUpdate(
                        episodeId,
                        title = "new",
                        contentHash = 7,
                        durationMs = null,
                        imageUrl = null,
                        artworkKey = null,
                        chaptersUrl = null,
                        chaptersType = null,
                    ),
                )

                val row = assertNotNull(db.episodeDao().byId(episodeId))
                assertEquals("new", row.title)
                assertEquals(7, row.contentHash)
                assertEquals(3_600_000, row.durationMs, "durationMs is null-preserving")
                assertEquals("https://img/e.png", row.imageUrl, "imageUrl is null-preserving")
                assertEquals("e-key", row.artworkKey, "artworkKey is null-preserving")
                assertEquals("https://chaps", row.chaptersUrl, "chaptersUrl is null-preserving")
                assertEquals("application/json+chapters", row.chaptersType)
            } finally {
                db.close()
            }
        }

    @Test
    fun updateFeedFieldsOverwritesTheNullPreservingColumnsWhenSet() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = db.podcastDao().insertPodcast(podcastEntity())
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcastId, identityKey = "g:2")),
                        ).single()

                db.ingestDao().updateFeedFields(
                    feedUpdate(episodeId, durationMs = 99_000, artworkKey = "new-key", isShort = true),
                )

                val row = assertNotNull(db.episodeDao().byId(episodeId))
                assertEquals(99_000, row.durationMs)
                assertEquals("new-key", row.artworkKey)
                assertTrue(row.isShort)
            } finally {
                db.close()
            }
        }

    @Test
    fun touchSeenBumpsOnlyRowsOlderThanADay() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val now = TestClock.DEFAULT_NOW
                val podcastId = db.podcastDao().insertPodcast(podcastEntity())
                val stale =
                    db.ingestDao().insertEpisodes(
                        listOf(
                            episodeEntity(podcastId = podcastId, identityKey = "g:stale") {
                                copy(lastSeenAt = now - 2 * IngestDao.DAY_MS)
                            },
                            episodeEntity(podcastId = podcastId, identityKey = "g:fresh") {
                                copy(lastSeenAt = now - 1_000)
                            },
                        ),
                    )

                db.ingestDao().touchSeen(podcastId, now)

                assertEquals(now, db.episodeDao().byId(stale[0])?.lastSeenAt)
                assertEquals(now - 1_000, db.episodeDao().byId(stale[1])?.lastSeenAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun applyFeedMetadataNeverTouchesUserOrIdentityColumns() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "https://feed", syncId = "sync-fixed") {
                            copy(
                                customTitle = "My rename",
                                includeInAll = false,
                                youtubeChannelId = "UC123",
                                subscribedAt = 5,
                                feedUrl = "https://feed",
                            )
                        },
                    )

                db.ingestDao().applyFeedMetadata(
                    PodcastFeedMetadata(
                        id = podcastId,
                        podcastGuid = "pguid",
                        podcastGuidDerived = true,
                        title = "Feed title",
                        author = "A",
                        link = "https://link",
                        language = "en",
                        explicit = false,
                        showType = null,
                        medium = "podcast",
                        locked = false,
                        complete = false,
                        artworkUrl = "https://art",
                        artworkKey = "a-key",
                        bannerUrl = null,
                        status = PodcastStatus.ACTIVE,
                        initialFetch = false,
                        latestEpisodeAt = 10,
                        etag = "e1",
                        lastModified = "m1",
                        contentSha256 = "sha",
                        parserVersion = 3,
                        lastParseOk = true,
                        lastAttemptAt = 11,
                        lastSuccessAt = 12,
                        lastFullFetchAt = 13,
                        nextRefreshAt = 14,
                        failureCount = 0,
                        lastErrorKind = null,
                        lastErrorDetail = null,
                        gone = false,
                        needsCredentials = false,
                        ttlMinutes = 60,
                        updateFrequencyRrule = null,
                        pendingNewFeedUrl = null,
                        pagingNextUrl = null,
                        pagingComplete = true,
                        hubUrl = null,
                        usesPodping = false,
                        descriptionHtml = "<p>desc</p>",
                        categoriesJson = "[]",
                    ),
                )

                val row = assertNotNull(db.podcastDao().byId(podcastId))
                assertEquals("Feed title", row.title)
                assertEquals("a-key", row.artworkKey)
                assertEquals("My rename", row.customTitle, "customTitle is user-owned")
                assertEquals(false, row.includeInAll, "includeInAll is user-owned")
                assertEquals("UC123", row.youtubeChannelId, "youtubeChannelId is not feed-owned")
                assertEquals("sync-fixed", row.syncId, "syncId is identity, never feed-written")
                assertEquals("https://feed", row.feedKey)
                assertEquals(5, row.subscribedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun dueForRefreshOrdersPendingFirstThenStalest() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val stale =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-stale") {
                            copy(status = PodcastStatus.ACTIVE, nextRefreshAt = 0, lastSuccessAt = 1)
                        },
                    )
                val pending =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-pending") {
                            copy(status = PodcastStatus.PENDING_FIRST_FETCH, nextRefreshAt = 0)
                        },
                    )
                val fresher =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-fresh") {
                            copy(status = PodcastStatus.ACTIVE, nextRefreshAt = 0, lastSuccessAt = 9)
                        },
                    )
                // Not due: scheduled in the future, and gone feeds never show up.
                db.podcastDao().insertPodcast(
                    podcastEntity(feedKey = "k-future") { copy(nextRefreshAt = Long.MAX_VALUE) },
                )
                db.podcastDao().insertPodcast(
                    podcastEntity(feedKey = "k-gone") { copy(nextRefreshAt = 0, gone = true) },
                )

                val due = db.podcastDao().dueForRefresh(dueBefore = 100, scopeAll = true)
                assertEquals(listOf(pending, stale, fresher), due.map { it.id })
            } finally {
                db.close()
            }
        }

    @Test
    fun chunkedSelectionDedupesAndSortsGlobally() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val dao = db.podcastDao()
                // 501 ACTIVE rows fill the first bind chunk; the 502nd is PENDING_FIRST_FETCH —
                // concatenating per-chunk ORDER BY results would still leave it at the end (D3).
                val ids = ArrayList<Long>(502)
                repeat(501) {
                    ids +=
                        dao.insertPodcast(
                            podcastEntity(feedKey = "k-$it") {
                                copy(
                                    status = PodcastStatus.ACTIVE,
                                    nextRefreshAt = 0,
                                    lastSuccessAt = 0,
                                    pagingNextUrl = "https://next/$it",
                                )
                            },
                        )
                }
                val pending =
                    dao.insertPodcast(
                        podcastEntity(feedKey = "k-pending") {
                            copy(status = PodcastStatus.PENDING_FIRST_FETCH, nextRefreshAt = 0)
                        },
                    )

                val due = dao.dueForRefresh(dueBefore = 100, scopeAll = false, ids = ids + pending)
                assertEquals(502, due.size)
                assertEquals(pending, due.first().id, "PENDING_FIRST_FETCH sorts first across chunks")

                // A duplicate id straddling the chunk boundary still returns one row.
                val dup =
                    dao.dueForRefresh(dueBefore = 100, scopeAll = false, ids = ids + listOf(ids[0], pending))
                assertEquals(502, dup.size)

                // pagingPending's global key is `id`; a reversed id list puts the lowest id in
                // the second chunk.
                val paging = dao.pagingPending(scopeAll = false, ids = ids.asReversed())
                assertEquals(ids.sorted(), paging.map { it.id })
            } finally {
                db.close()
            }
        }

    @Test
    fun forceDueMarksEveryRowDueAndScopesWork() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val a =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-a") { copy(nextRefreshAt = Long.MAX_VALUE) },
                    )
                val b =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedKey = "k-b") { copy(nextRefreshAt = Long.MAX_VALUE) },
                    )

                db.podcastDao().forceDue(scopeAll = false, ids = listOf(a))
                var due = db.podcastDao().dueForRefresh(dueBefore = 0, scopeAll = true).map { it.id }
                assertEquals(listOf(a), due, "forceDue must only touch the requested ids")

                db.podcastDao().forceDue(scopeAll = true)
                due =
                    db
                        .podcastDao()
                        .dueForRefresh(dueBefore = 0, scopeAll = true)
                        .map { it.id }
                        .sorted()
                assertEquals(listOf(a, b), due)
            } finally {
                db.close()
            }
        }

    @Test
    fun fetchStateBatcherFlushesAtMaxBatchAndOnFlush() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val id = db.podcastDao().insertPodcast(podcastEntity())
                val batcher = FetchStateBatcher(db.podcastDao(), TestClock(), backgroundScope)
                val row = { n: Int ->
                    PodcastFetchState(
                        id = id,
                        lastAttemptAt = n.toLong(),
                        lastSuccessAt = null,
                        nextRefreshAt = null,
                        failureCount = n,
                        lastErrorKind = FeedErrorKind.UNKNOWN,
                        lastErrorDetail = null,
                        gone = false,
                        needsCredentials = false,
                        etag = null,
                        lastModified = null,
                        lastFullFetchAt = null,
                        lastParseOk = true,
                    )
                }

                // 19 adds stay buffered; the 20th flushes.
                repeat(FetchStateBatcher.MAX_BATCH - 1) { batcher.add(row(it)) }
                assertEquals(0, db.podcastDao().byId(id)?.failureCount)
                batcher.add(row(42))
                assertEquals(42, db.podcastDao().byId(id)?.failureCount)

                // A partial batch flushes on demand.
                batcher.add(row(7))
                batcher.flush()
                assertEquals(7, db.podcastDao().byId(id)?.failureCount)
            } finally {
                db.close()
            }
        }

    @Test
    fun setInFeedFlipsOnlyTheListedRows() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = db.podcastDao().insertPodcast(podcastEntity())
                val ids =
                    db.ingestDao().insertEpisodes(
                        (1..3).map { episodeEntity(podcastId = podcastId, identityKey = "g:$it") },
                    )
                db.ingestDao().setInFeed(listOf(ids[0], ids[2]), inFeed = false)
                val rows = db.ingestDao().existing(podcastId).sortedBy { it.id }
                assertEquals(listOf(false, true, false), rows.map { it.inFeed })
            } finally {
                db.close()
            }
        }

    private fun feedUpdate(
        id: Long,
        title: String = "t",
        contentHash: Long = 1,
        durationMs: Long? = null,
        imageUrl: String? = null,
        artworkKey: String? = null,
        chaptersUrl: String? = null,
        chaptersType: String? = null,
        isShort: Boolean? = null,
    ) = EpisodeFeedUpdate(
        id = id,
        guid = "g",
        title = title,
        pubDate = 1,
        rawPubDate = null,
        sortDate = 1,
        feedOrder = 0,
        lastSeenAt = 1,
        enclosureUrl = null,
        enclosureType = null,
        enclosureLength = null,
        externalMediaId = null,
        season = null,
        seasonName = null,
        episodeNumber = null,
        episodeDisplay = null,
        episodeType = EpisodeType.FULL,
        explicit = null,
        link = null,
        contentHash = contentHash,
        snippet = null,
        durationMs = durationMs,
        imageUrl = imageUrl,
        artworkKey = artworkKey,
        chaptersUrl = chaptersUrl,
        chaptersType = chaptersType,
        availability = null,
        isShort = isShort,
        isVideo = null,
    )
}
