// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.data.playback.PositionWriter
import ch.lkmc.neutrodyne.core.data.repo.EpisodeRepositoryImpl
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositionWriterTest {
    private val clock = TestClock(nowMs = 100)
    private val db = newDb(clock)
    private val writer = PositionWriter(db, clock)

    @AfterTest fun close() = db.close()

    @Test fun measuredDurationEnsuresStateAndPreservesMarksPositionsQueueAndFeed() =
        runTest {
            val fresh = seed()
            writer.setMeasuredDuration(fresh, 0)
            writer.setMeasuredDuration(fresh, -1)
            assertNull(db.episodeStateDao().byEpisode(fresh))
            writer.setMeasuredDuration(9999, 5000)
            writer.setMeasuredDuration(fresh, 5000)
            assertEquals(5000, db.episodeStateDao().byEpisode(fresh)?.measuredDurationMs)
            assertNull(db.positionDao().byEpisode(fresh))
            val old =
                EpisodeStateEntity(
                    fresh,
                    playedAt = 10,
                    playCount = 2,
                    startedAt = 5,
                    lastPlayedAt = 9,
                    isFavorite = true,
                    measuredDurationMs = 5000,
                    updatedAt = 50,
                )
            db.episodeStateDao().upsert(old)
            db.positionDao().upsert(EpisodePositionEntity(fresh, 123, 4000, PositionSource.STREAM, 60))
            db.queueDao().insert(QueueEntryEntity(episodeId = fresh, orderKey = "a0", addedAt = 60))
            val position = db.positionDao().byEpisode(fresh)
            val feed = db.episodeDao().byId(fresh)
            writer.setMeasuredDuration(fresh, 6000)
            assertEquals(old, db.episodeStateDao().byEpisode(fresh))
            writer.setMeasuredDuration(fresh, 6001)
            assertEquals(
                old.copy(measuredDurationMs = 6001, updatedAt = clock.now()),
                db.episodeStateDao().byEpisode(fresh),
            )
            assertEquals(position, db.positionDao().byEpisode(fresh))
            assertEquals(feed, db.episodeDao().byId(fresh))
            assertEquals(listOf(fresh), db.queueDao().queuedEpisodeIds())
        }

    @Test fun positiveSaveStartsOnceAndPreservesUnknownDuration() =
        runTest {
            val id = seed()
            assertTrue(save(id, 500, now = 100, duration = 1000))
            assertEquals(100, db.episodeStateDao().byEpisode(id)?.startedAt)
            assertTrue(save(id, 600, now = 200))
            assertEquals(100, db.episodeStateDao().byEpisode(id)?.startedAt)
            assertEquals(100, db.episodeStateDao().byEpisode(id)?.updatedAt)
            assertEquals(1000, db.positionDao().byEpisode(id)?.durationMs)
            assertEquals(600, writer.observe(id).first()?.positionMs)
        }

    @Test fun zeroCannotOverwriteNonzeroButExplicitResetCan() =
        runTest {
            val id = seed()
            assertTrue(save(id, 45_000))
            val before = db.positionDao().byEpisode(id)
            assertFalse(save(id, 0, now = 200))
            assertEquals(0, db.positionDao().updateGuarded(id, 0, null, PositionSource.STREAM, 200, 1))
            assertEquals(before, db.positionDao().byEpisode(id))
            writer.reset(id, 300)
            assertEquals(0, db.positionDao().byEpisode(id)?.positionMs)
            assertNull(db.episodeStateDao().byEpisode(id)?.startedAt)
        }

    @Test fun allowedFalseWritesNothingIncludingAnInitialRow() =
        runTest {
            val id = seed()
            assertFalse(save(id, 50, allowed = { false }))
            assertNull(db.positionDao().byEpisode(id))
            assertNull(db.episodeStateDao().byEpisode(id))
        }

    @Test fun queuedSaveReadsAllowedAfterItAcquiresTheTransaction() =
        runTest {
            val id = seed()
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var allowed = true
            val blocker =
                async {
                    db.withWriteTransaction {
                        locked.complete(Unit)
                        release.await()
                    }
                }
            locked.await()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { save(id, 50, allowed = { allowed }) }
            allowed = false
            release.complete(Unit)
            blocker.await()
            assertFalse(pending.await())
            assertNull(db.positionDao().byEpisode(id))
        }

    @Test fun markAfterStartBlocksExistingAndFirstPositionWrites() =
        runTest {
            val existing = seed()
            val absent = seed()
            assertTrue(save(existing, 99))
            for (id in listOf(existing, absent)) {
                writer.markPlayed(id, 200)
                val before = db.positionDao().byEpisode(id)
                assertFalse(save(id, 80, now = 300, pin = 200))
                assertEquals(before, db.positionDao().byEpisode(id))
                assertNull(db.episodeStateDao().byEpisode(id)?.startedAt)
            }
        }

    @Test fun olderPlayedTimestampDoesNotBlockNewPlaybackAndReplayClearsAtActualPlay() =
        runTest {
            val id = seed()
            writer.markPlayed(id, 100)
            assertTrue(save(id, 80, pin = 101))
            assertEquals(100, db.episodeStateDao().byEpisode(id)?.playedAt)
            writer.markUnplayedForReplay(id, 200)
            assertNull(db.episodeStateDao().byEpisode(id)?.playedAt)
            assertNull(db.episodeStateDao().byEpisode(id)?.startedAt)
            assertEquals(80, db.positionDao().byEpisode(id)?.positionMs)
            assertTrue(save(id, 90, now = 201, pin = 101))
            assertEquals(201, db.episodeStateDao().byEpisode(id)?.startedAt)
            assertEquals(1, db.episodeStateDao().byEpisode(id)?.playCount)
        }

    @Test fun markPlayedIsIdempotentAndRemovesQueueAndResetsPosition() =
        runTest {
            val id = seed()
            db.episodeStateDao().upsert(
                EpisodeStateEntity(id, isFavorite = true, measuredDurationMs = 1000, lastPlayedAt = 50, updatedAt = 50),
            )
            save(id, 999)
            db.queueDao().insert(QueueEntryEntity(episodeId = id, orderKey = "a0", addedAt = 100))
            writer.markPlayed(id, 200)
            writer.markPlayed(id, 300)
            assertTrue(db.episodeStateDao().byEpisode(id)!!.isFavorite)
            assertEquals(1000, db.episodeStateDao().byEpisode(id)?.measuredDurationMs)
            assertEquals(50, db.episodeStateDao().byEpisode(id)?.lastPlayedAt)
            assertEquals(200, db.episodeStateDao().byEpisode(id)?.playedAt)
            assertEquals(1, db.episodeStateDao().byEpisode(id)?.playCount)
            assertNull(db.episodeStateDao().byEpisode(id)?.startedAt)
            assertEquals(0, db.positionDao().byEpisode(id)?.positionMs)
            assertTrue(db.queueDao().entries().isEmpty())
        }

    @Test fun failedMarkPlayedRollsBackTheWholeChain() =
        runTest {
            val id = seed()
            save(id, 999)
            db.queueDao().insert(QueueEntryEntity(episodeId = id, orderKey = "a0", addedAt = 100))
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER reject_queue_delete BEFORE DELETE ON queue_entry BEGIN SELECT RAISE(ABORT, 'test failure'); END",
                )
            }
            assertFailsWith<Exception> { writer.markPlayed(id, 200) }
            assertNull(db.episodeStateDao().byEpisode(id)?.playedAt)
            assertEquals(999, db.positionDao().byEpisode(id)?.positionMs)
            assertEquals(listOf(id), db.queueDao().queuedEpisodeIds())
        }

    @Test fun episodeBulkPathUsesSameChainAcrossBindChunks() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val ids = (1..503).map { db.episodeDao().insertStub(episodeEntity(podcastId = podcast)) }
            for (id in ids) {
                db.positionDao().upsert(
                    EpisodePositionEntity(id, 99, positionSource = PositionSource.STREAM, updatedAt = 100),
                )
                db.queueDao().insert(QueueEntryEntity(episodeId = id, orderKey = "a0", addedAt = 100))
            }
            val repo = EpisodeRepositoryImpl(db, JsoupShowNotesSanitizer(), clock)
            repo.setPlayed(ids, true)
            for (id in ids) {
                assertEquals(100, db.episodeStateDao().byEpisode(id)?.playedAt)
                assertEquals(0, db.positionDao().byEpisode(id)?.positionMs)
            }
            assertTrue(db.queueDao().entries().isEmpty())
            repo.setPlayed(ids, false)
            assertTrue(ids.all { db.episodeStateDao().byEpisode(it)?.playedAt == null })
        }

    @Test fun resetPreservesPlayedAndRepeatedZeroCreatesNoState() =
        runTest {
            val id = seed()
            writer.markPlayed(id, 100)
            writer.reset(id, 200)
            assertEquals(100, db.episodeStateDao().byEpisode(id)?.playedAt)
            val fresh = seed()
            assertTrue(save(fresh, 0))
            assertTrue(save(fresh, 0, now = 200))
            assertNull(db.episodeStateDao().byEpisode(fresh))
        }

    @Test fun missingEpisodeAndInvalidInputsDoNotCreateRows() =
        runTest {
            assertFalse(save(999, 30))
            val id = seed()
            assertFailsWith<IllegalArgumentException> { save(id, -1) }
            assertFailsWith<IllegalArgumentException> { save(id, 1, duration = -1) }
            assertFailsWith<IllegalArgumentException> { save(id, 1, now = -1) }
            assertFailsWith<IllegalArgumentException> { save(id, 1, pin = -1) }
            assertNull(db.positionDao().byEpisode(id))
        }

    private suspend fun seed(): Long {
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        return db.episodeDao().insertStub(episodeEntity(podcastId = podcast))
    }

    private suspend fun save(
        id: Long,
        pos: Long,
        now: Long = 100,
        duration: Long? = null,
        pin: Long = 1,
        allowed: () -> Boolean = { true },
    ): Boolean = writer.save(id, pos, duration, PositionSource.STREAM, now, pin, allowed)
}
