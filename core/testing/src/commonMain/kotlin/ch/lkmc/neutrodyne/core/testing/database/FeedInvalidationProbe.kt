// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import androidx.paging.PagingSource
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeRowProjection
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.FetchStateBatcher
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastFetchState
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shared desktop/device proof of 02 Hygiene tests. Room must use this test's scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
object FeedInvalidationProbe {
    private const val POSITION_WRITES = 100
    private const val SESSION_WRITES = 20
    private const val REFRESH_OUTCOMES = 300
    private const val MAX_INVALIDATIONS = 15
    private const val BATCH_SIZE = 20
    private const val NOW = 1_800_000_000_000L
    private const val PAGE_SIZE = 40

    suspend fun positionWrites(
        db: NeutrodyneDatabase,
        scope: TestScope,
    ) {
        val seeded = seed().install(db)
        for (feed in sources(seeded)) {
            val ids = seeded.feedRows(feed, FeedFilters(), FeedOrder.NEWEST_FIRST).take(2).map { it.id }
            assertEquals(2, ids.size, "nonempty fixture for $feed")
            val states = ids.map { EpisodeStateEntity(it, startedAt = NOW, updatedAt = NOW) }
            db.withWriteTransaction { states.forEach { db.episodeStateDao().upsert(it) } }
            val queueId = db.queueDao().insert(QueueEntryEntity(episodeId = ids.first(), orderKey = "a", addedAt = NOW))
            val counter = Counter(db, scope, feed)
            counter.open()
            try {
                repeat(POSITION_WRITES) { tick ->
                    val id = ids[tick % ids.size]
                    db.withWriteTransaction {
                        // M4's guarded save primitives are not implemented yet. An ignored ensure
                        // is harmless; startedAt is already set, so only the position changes.
                        db.episodeStateDao().ensure(EpisodeStateEntity(id, updatedAt = NOW))
                        db.positionDao().upsert(position(id, tick + 1))
                    }
                    counter.drainAndRearm()
                    assertEquals(0, counter.invalidations, "$feed position tick $tick")
                }
                for (state in states) assertEquals(state, db.episodeStateDao().byEpisode(state.episodeId))
                for ((index, id) in ids.withIndex()) {
                    assertEquals((POSITION_WRITES - 1 + index) * 1_000L, db.positionDao().byEpisode(id)?.positionMs)
                }

                repeat(SESSION_WRITES) { tick ->
                    // M4 owns these missing DAO writers. Exercise the real tables without
                    // widening production APIs merely to arrange a hygiene test.
                    db.useWriterConnection { connection ->
                        connection.usePrepared(
                            "UPDATE play_session SET generation = generation + 1, updatedAt = ? WHERE id = 0",
                        ) {
                            it.bindLong(1, NOW + tick)
                            it.step()
                        }
                    }
                    counter.drainAndRearm()
                    db.useWriterConnection { connection ->
                        connection.usePrepared("UPDATE queue_entry SET orderKey = ? WHERE id = ?") {
                            it.bindText(1, "a$tick")
                            it.bindLong(2, queueId)
                            it.step()
                        }
                    }
                    counter.drainAndRearm()
                }
                assertEquals(0, counter.invalidations, "$feed session/Up next writes")
                assertEquals(
                    "a${SESSION_WRITES - 1}",
                    db
                        .queueDao()
                        .entries()
                        .single { it.id == queueId }
                        .orderKey,
                )

                db.withWriteTransaction {
                    assertEquals(1, db.episodeStateDao().markPlayed(listOf(ids.first()), NOW + 1))
                }
                counter.drainAndRearm()
                assertEquals(1, counter.invalidations, "$feed played-state positive control")
                // A second transition proves that the counter actually observes a fresh source.
                db.withWriteTransaction { db.episodeStateDao().markUnplayed(listOf(ids.first()), NOW + 2) }
                counter.drainAndRearm()
                assertEquals(2, counter.invalidations, "$feed rearmed positive control")
            } finally {
                counter.close()
                db.queueDao().removeEpisodes(ids)
            }
        }
    }

    suspend fun firstPositionStartsStateOnce(
        db: NeutrodyneDatabase,
        scope: TestScope,
    ) {
        val seeded = seed().install(db)
        for (feed in sources(seeded)) {
            val id =
                seeded
                    .feedRows(feed, FeedFilters(), FeedOrder.NEWEST_FIRST)
                    .first {
                        db.episodeStateDao().byEpisode(it.id) ==
                            null
                    }.id
            val counter = Counter(db, scope, feed)
            counter.open()
            try {
                db.withWriteTransaction {
                    db.positionDao().upsert(position(id, 1))
                    // Creation plus startedAt is a single committed state transition.
                    db.episodeStateDao().ensure(EpisodeStateEntity(id, startedAt = NOW, updatedAt = NOW))
                }
                counter.drainAndRearm()
                assertEquals(1, counter.invalidations, "$feed initial startedAt transition")
                assertEquals(NOW, db.episodeStateDao().byEpisode(id)?.startedAt)
                repeat(POSITION_WRITES) { tick ->
                    db.withWriteTransaction {
                        db.episodeStateDao().ensure(EpisodeStateEntity(id, updatedAt = NOW))
                        db.positionDao().upsert(position(id, tick + 2))
                    }
                    counter.drainAndRearm()
                }
                assertEquals(1, counter.invalidations, "$feed later position saves preserve startedAt")
                // Each feed needs a genuinely missing state row before its own first save.
            } finally {
                counter.close()
            }
        }
    }

    suspend fun refreshBatching(
        db: NeutrodyneDatabase,
        scope: TestScope,
    ) {
        val seeded = seed(podcasts = REFRESH_OUTCOMES).install(db)
        val counter = Counter(db, scope, FeedSource.All)
        val batcher = FetchStateBatcher(db.podcastDao(), TestClock(nowMs = NOW), scope.backgroundScope)
        counter.open()
        try {
            for ((index, podcast) in seeded.podcasts.withIndex()) {
                // A 304 changes scheduling/validators only; no ingestion or episode write.
                batcher.add(
                    PodcastFetchState(
                        id = podcast.id,
                        lastAttemptAt = NOW,
                        lastSuccessAt = NOW,
                        nextRefreshAt = NOW + 3_600_000L,
                        failureCount = 0,
                        lastErrorKind = null,
                        lastErrorDetail = null,
                        gone = false,
                        needsCredentials = false,
                        etag = "b5-304-${podcast.id}",
                        lastModified = podcast.lastModified,
                        lastFullFetchAt = podcast.lastFullFetchAt,
                        lastParseOk = true,
                    ),
                )
                // Drain after EVERY outcome, not just at the end: unbatched writes must
                // invalidate 300 different sources rather than collapse to one notification.
                counter.drainAndRearm()
                if (index == BATCH_SIZE - 2) {
                    assertEquals(0, counter.invalidations, "19 outcomes must remain buffered")
                    assertEquals(
                        seeded.podcasts.first().lastAttemptAt,
                        db.podcastDao().byId(seeded.podcasts.first().id)?.lastAttemptAt,
                    )
                }
            }
            batcher.flush()
            counter.drainAndRearm()
            assertTrue(
                counter.invalidations in 1..MAX_INVALIDATIONS,
                "300 outcomes caused ${counter.invalidations} invalidations",
            )
            for (podcast in seeded.podcasts) {
                val stored = assertNotNull(db.podcastDao().byId(podcast.id))
                assertEquals(NOW, stored.lastAttemptAt)
                assertEquals(NOW, stored.lastSuccessAt)
                assertEquals("b5-304-${podcast.id}", stored.etag)
            }
        } finally {
            counter.close()
        }
    }

    private fun seed(podcasts: Int = 36) =
        SeedDatabase(
            podcasts = podcasts,
            episodes = 1_200,
            groups = 5,
            downloads = 30,
            inProgress = 25,
            favourites = 40,
            youtubeChannels = 6,
        )

    private fun sources(seeded: Seeded): List<FeedSource> =
        listOf(
            FeedSource.All,
            FeedSource.Ungrouped,
            seeded.matrixSources().filterIsInstance<FeedSource.Group>().first(),
            FeedSource.Podcast(seeded.podcasts.first().id),
        )

    private fun position(
        id: Long,
        tick: Int,
    ) = EpisodePositionEntity(
        id,
        positionMs = tick * 1_000L,
        positionSource = PositionSource.STREAM,
        updatedAt =
            NOW + tick,
    )

    private class Counter(
        private val db: NeutrodyneDatabase,
        private val scope: TestScope,
        private val feed: FeedSource,
    ) {
        private var source: PagingSource<Int, EpisodeRowProjection>? = null
        private val onInvalidated: () -> Unit = { invalidations++ }
        var invalidations = 0
            private set

        suspend fun open() {
            db.invalidationTracker.refresh()
            scope.runCurrent()
            source =
                db.feedDao().page(FeedQueryBuilder.page(feed, FeedFilters(), FeedOrder.NEWEST_FIRST)).also {
                    it.registerInvalidatedCallback(onInvalidated)
                    val page = it.load(PagingSource.LoadParams.Refresh<Int>(null, PAGE_SIZE, false))
                    assertIs<PagingSource.LoadResult.Page<Int, EpisodeRowProjection>>(page)
                    assertTrue(page.data.isNotEmpty(), "open $feed must load real rows")
                }
            scope.runCurrent()
            assertFalse(assertNotNull(source).invalid, "fresh source for $feed")
        }

        suspend fun drainAndRearm() {
            // Room refresh is a suspending barrier for table notifications; runCurrent also
            // drains the PagingSource's Flow collector on the shared test dispatcher.
            db.invalidationTracker.refresh()
            scope.runCurrent()
            if (assertNotNull(source).invalid) {
                close()
                open()
            }
        }

        fun close() {
            source?.unregisterInvalidatedCallback(onInvalidated)
            source?.invalidate()
            source = null
        }
    }
}
