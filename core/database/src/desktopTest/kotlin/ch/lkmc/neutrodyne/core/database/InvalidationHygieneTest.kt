// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.database.FeedInvalidationProbe
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class InvalidationHygieneTest {
    @Test
    fun positionOnlyWritesDoNotInvalidateOpenFeeds() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                FeedInvalidationProbe.positionWrites(db, this)
            } finally {
                db.close()
            }
        }

    @Test
    fun theFirstStartedStateTransitionInvalidatesOnlyOnce() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                FeedInvalidationProbe.firstPositionStartsStateOnce(db, this)
            } finally {
                db.close()
            }
        }

    @Test
    fun linkedPositionWritesDoNotInvalidateOpenFeeds() =
        runTest {
            val db = TestDb.linked(queryContext = StandardTestDispatcher(testScheduler))
            try {
                FeedInvalidationProbe.positionWrites(db, this)
            } finally {
                db.close()
            }
        }

    @Test
    fun linkedStartedStateTransitionInvalidatesOnlyOnce() =
        runTest {
            val db = TestDb.linked(queryContext = StandardTestDispatcher(testScheduler))
            try {
                FeedInvalidationProbe.firstPositionStartsStateOnce(db, this)
            } finally {
                db.close()
            }
        }

    /**
     * W10: an open `inProgressOnly` feed observes `episode_state`. Position ticks on an
     * already-started episode never invalidate it (the high-churn table stays unobserved), the
     * first save stamps `startedAt` exactly once so the row joins, and a played transition counts
     * once as the row leaves.
     */
    @Test
    fun positionWritesDoNotInvalidateAnInProgressFeedAndTransitionsCountOnce() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                val podcast = db.podcastDao().insertPodcast(podcastEntity())

                suspend fun episode(key: String): Long =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcast, identityKey = key, title = key)),
                        ).single()
                val alreadyStarted = episode("e-started")
                val fresh = episode("e-fresh")
                db.episodeStateDao().upsert(episodeStateEntity(alreadyStarted) { copy(startedAt = 1) })

                val counter =
                    FeedInvalidationProbe.Counter(
                        db,
                        this,
                        FeedSource.All,
                        FeedFilters(inProgressOnly = true),
                    )
                counter.open()
                try {
                    assertEquals(setOf(alreadyStarted), counter.ids())

                    repeat(POSITION_WRITES) { tick ->
                        // 06's save sequence: the state row and startedAt already exist, so only
                        // the unobserved episode_position row is written.
                        savePosition(db, alreadyStarted, (tick + 1) * 1_000L, NOW + tick)
                        counter.drainAndRearm()
                        assertEquals(0, counter.invalidations, "position tick $tick")
                    }

                    // The first save on an unstarted episode creates and stamps the state row in
                    // one transaction: exactly one invalidation, and the row joins the filter.
                    savePosition(db, fresh, 1_000L, NOW + POSITION_WRITES)
                    counter.drainAndRearm()
                    assertEquals(1, counter.invalidations, "first save stamps startedAt once")
                    assertEquals(setOf(alreadyStarted, fresh), counter.ids())

                    repeat(POSITION_WRITES) { tick ->
                        savePosition(db, fresh, (tick + 2) * 1_000L, NOW + 2 * POSITION_WRITES + tick)
                        counter.drainAndRearm()
                        assertEquals(1, counter.invalidations, "later position saves stay silent")
                    }

                    // Played leaves the filtered feed: one state write, one invalidation.
                    db.withWriteTransaction {
                        db.episodeStateDao().ensureAll(listOf(alreadyStarted), NOW + 300)
                        assertEquals(1, db.episodeStateDao().markPlayed(listOf(alreadyStarted), NOW + 300))
                    }
                    counter.drainAndRearm()
                    assertEquals(2, counter.invalidations, "played transition")
                    assertEquals(setOf(fresh), counter.ids())
                } finally {
                    counter.close()
                }
            } finally {
                db.close()
            }
        }

    private suspend fun savePosition(
        db: NeutrodyneDatabase,
        id: Long,
        positionMs: Long,
        now: Long,
    ) {
        // The 06 save order (02 User-state writes): lazy position row, the guarded update, the
        // lazy state row and the one-shot startedAt stamp — one transaction per save.
        db.withWriteTransaction {
            db.positionDao().insertIfAbsent(
                EpisodePositionEntity(
                    episodeId = id,
                    positionMs = positionMs,
                    positionSource = PositionSource.STREAM,
                    updatedAt = now,
                ),
            )
            assertEquals(
                1,
                db.positionDao().updateGuarded(
                    id,
                    positionMs,
                    null,
                    PositionSource.STREAM,
                    now,
                    pinStartedAt = now,
                ),
                "guarded position write",
            )
            db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = id, updatedAt = now))
            db.episodeStateDao().markStarted(id, now)
        }
    }

    private companion object {
        private const val POSITION_WRITES = 20
        private const val NOW = 1_800_000_000_000L
    }
}
