// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.playback.PositionWriter
import ch.lkmc.neutrodyne.core.data.playback.SessionWriter
import ch.lkmc.neutrodyne.core.database.PlaySessionEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.SessionRevision
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionWriterTest {
    private val db = newDb()
    private val capabilities = FakeYouTubeCapabilitiesSource()
    private val writer = SessionWriter(db, ProtocolOrderKeys, capabilities)
    private val context =
        PlayContextSpec(ContextType.ALL, null, FeedOrder.NEWEST_FIRST, 1, MediaFilter.ALL, null, false)

    @AfterTest fun close() = db.close()

    @Test fun promotionsDeparturesPreviousAndEndCommitOneGenerationEach() =
        runTest {
            val ids = seed(4)
            queue(ids)
            db.useWriterConnection { it.execSql("DELETE FROM play_session") }
            assertEquals(1, writer.commitStart(start(ids[0], ids[0], 400), 100))
            assertEquals(ids.drop(1), writer.virtualQueue(20).upNext.map { it.episodeId })
            assertEquals(100, db.episodeStateDao().byEpisode(ids[0])?.lastPlayedAt)
            assertNull(db.episodeStateDao().byEpisode(ids[0])?.startedAt)
            assertEquals(2L, writer.onTransition(departure(ids[0]), ids[1], QueueOrigin.UP_NEXT, revision(), 200))
            assertEquals(99, db.positionDao().byEpisode(ids[0])?.positionMs)
            assertEquals(ids[0], db.playSessionDao().get()?.contextAnchorEpisodeId)
            assertEquals(
                3L,
                writer.onTransition(departure(ids[1], played = true), ids[2], QueueOrigin.CONTEXT, revision(), 300),
            )
            assertEquals(300, db.episodeStateDao().byEpisode(ids[1])?.playedAt)
            assertEquals(0, db.positionDao().byEpisode(ids[1])?.positionMs)
            assertEquals(200, db.playSessionDao().get()?.contextAnchorSortDate)
            writer.goBack(QueueItem(ids[0], QueueOrigin.CONTEXT), departure(ids[2]), 400)
            assertEquals(listOf(ids[2], ids[3]), writer.virtualQueue(0).upNext.map { it.episodeId })
            assertEquals(ids[0], db.playSessionDao().get()?.contextAnchorEpisodeId)
            writer.goBack(QueueItem(ids[3], QueueOrigin.UP_NEXT), departure(ids[0]), 500)
            assertEquals(ids[0], db.playSessionDao().get()?.contextAnchorEpisodeId)
            assertEquals(listOf(ids[0], ids[2]), writer.virtualQueue(0).upNext.map { it.episodeId })
            assertEquals(6L, writer.endSession(departure(ids[3], played = true), revision(), 600))
            val ended = db.playSessionDao().get()!!
            assertNull(ended.currentEpisodeId)
            assertNull(ended.contextType)
            assertNull(ended.contextAnchorEpisodeId)
            assertNull(ended.contextAnchorSortDate)
            assertEquals(listOf(ids[0], ids[2]), writer.virtualQueue(20).upNext.map { it.episodeId })
            assertTrue(writer.virtualQueue(20).contextTail.isEmpty())
        }

    @Test fun startsSaveDepartureBeforeArrivalForBothContextModes() =
        runTest {
            for (retention in ContextRetention.entries) {
                val ids = seed(2)
                writer.commitStart(start(ids[0], ids[0], 200), 100)
                queue(listOf(ids[1]))
                val generation = db.playSessionDao().get()!!.generation
                db.useWriterConnection {
                    // Fail promotion if the outgoing progress has not already been saved.
                    it.execSql(
                        "CREATE TRIGGER departure_before_arrival BEFORE UPDATE OF lastPlayedAt ON episode_state" +
                            " WHEN NEW.episodeId = ${ids[1]} AND NOT EXISTS" +
                            " (SELECT 1 FROM episode_position WHERE episodeId = ${ids[0]} AND positionMs = 99)" +
                            " BEGIN SELECT RAISE(ABORT, 'departure missing'); END",
                    )
                }

                assertEquals(
                    generation + 1,
                    writer.commitStart(
                        start(ids[1], ids[1], 100).copy(
                            contextRetention = retention,
                            departure = departure(ids[0]),
                        ),
                        200,
                    ),
                )
                db.useWriterConnection { it.execSql("DROP TRIGGER departure_before_arrival") }
                assertEquals(99, db.positionDao().byEpisode(ids[0])?.positionMs)
                assertEquals(200, db.episodeStateDao().byEpisode(ids[0])?.startedAt)
                assertEquals(ids[1], db.playSessionDao().get()?.currentEpisodeId)
                assertEquals(200, db.episodeStateDao().byEpisode(ids[1])?.lastPlayedAt)
                assertTrue(db.queueDao().entries().none { it.episodeId == ids[1] })
                assertEquals(
                    if (retention == ContextRetention.KEEP) ids[0] else ids[1],
                    db.playSessionDao().get()?.contextAnchorEpisodeId,
                )
            }
        }

    @Test fun startDeparturesUseTransitionPositionAndPlayedGuards() =
        runTest {
            val ids = seed(6)
            val positions = PositionWriter(db, TestClock())
            positions.save(ids[0], 500, 1000, PositionSource.STREAM, 110, 100) { true }
            positions.markPlayed(ids[1], 120)
            queue(listOf(ids[2]))
            val departures =
                listOf(
                    departure(ids[0]).copy(positionMs = 0),
                    departure(ids[1]),
                    departure(ids[2], played = true),
                    departure(ids[3]).copy(writePosition = false),
                    departure(ids[4], played = true).copy(writePosition = false),
                )
            for (outgoing in departures) {
                writer.commitStart(start(ids[5]).copy(departure = outgoing), 200)
            }

            assertEquals(500, db.positionDao().byEpisode(ids[0])?.positionMs)
            assertNull(db.positionDao().byEpisode(ids[1]))
            assertEquals(120, db.episodeStateDao().byEpisode(ids[1])?.playedAt)
            assertEquals(0, db.positionDao().byEpisode(ids[2])?.positionMs)
            assertEquals(200, db.episodeStateDao().byEpisode(ids[2])?.playedAt)
            assertTrue(db.queueDao().entries().none { it.episodeId == ids[2] })
            assertNull(db.positionDao().byEpisode(ids[3]))
            assertNull(db.episodeStateDao().byEpisode(ids[3]))
            assertEquals(200, db.episodeStateDao().byEpisode(ids[4])?.playedAt)
        }

    @Test fun failedStartRollsBackDepartureAndArrivalTogether() =
        runTest {
            val ids = seed(2)
            writer.commitStart(start(ids[0]), 100)
            queue(listOf(ids[1]))
            val before = db.playSessionDao().get()
            assertFailsWith<IllegalArgumentException> {
                writer.commitStart(start(-1).copy(departure = departure(ids[0], played = true)), 200)
            }
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER reject_start BEFORE UPDATE ON play_session" +
                        " BEGIN SELECT RAISE(ABORT, 'test'); END",
                )
            }
            assertFailsWith<Exception> {
                writer.commitStart(start(ids[1]).copy(departure = departure(ids[0], played = true)), 200)
            }
            assertEquals(before, db.playSessionDao().get())
            assertEquals(listOf(ids[1]), db.queueDao().queuedEpisodeIds())
            assertNull(db.positionDao().byEpisode(ids[0]))
            assertNull(db.episodeStateDao().byEpisode(ids[0])?.playedAt)
            assertNull(db.episodeStateDao().byEpisode(ids[1]))
        }

    @Test fun selectedRowsNullAnchorsKeepContextAndExternalDismissal() =
        runTest {
            val ids = seed(4)
            writer.commitStart(start(ids[2], ids[2], 200), 100)
            assertEquals(ids[2], writer.virtualQueue(20).current?.episodeId)
            assertEquals(listOf(ids[3]), writer.virtualQueue(20).contextTail.map { it.episodeId })
            writer.commitStart(SessionStart(ids[1], null, ContextRetention.KEEP, ids[3], null), 200)
            assertEquals(ids[2], db.playSessionDao().get()?.contextAnchorEpisodeId)
            writer.commitStart(start(ids[2]), 300)
            assertNull(db.playSessionDao().get()?.contextAnchorEpisodeId)
            assertEquals(ids.take(2), writer.virtualQueue(2).contextTail.map { it.episodeId })
            writer.commitStart(SessionStart(ids[0], null, ContextRetention.REPLACE, null, null), 400)
            assertEquals(ContextType.EXTERNAL, db.playSessionDao().get()?.contextType)
            assertTrue(writer.virtualQueue(20).contextTail.isEmpty())
            queue(listOf(ids[3]))
            val generation = db.playSessionDao().get()!!.generation
            assertEquals(generation + 1, writer.clearCurrent(departure(ids[0]), 500))
            assertNull(db.episodeStateDao().byEpisode(ids[0])?.playedAt)
            assertEquals(listOf(ids[3]), writer.virtualQueue(20).upNext.map { it.episodeId })
            assertFailsWith<IllegalArgumentException> { writer.virtualQueue(-1) }
            assertFailsWith<IllegalArgumentException> { writer.commitStart(start(ids[0], ids[0], null), 600) }
        }

    @Test fun supersededDismissLeavesTheSessionAndDepartureUntouched() =
        runTest {
            val ids = seed(2)
            writer.commitStart(start(ids[0], ids[0], 200), 100)
            queue(listOf(ids[1]))
            val before = db.playSessionDao().get()!!

            assertFailsWith<CancellationException> {
                writer.clearCurrent(departure(ids[0], played = true), 200) { false }
            }
            assertEquals(before, db.playSessionDao().get())
            assertEquals(listOf(ids[1]), db.queueDao().queuedEpisodeIds())
            assertNull(db.positionDao().byEpisode(ids[0]))
            assertNull(db.episodeStateDao().byEpisode(ids[0])?.playedAt)

            assertEquals(before.generation + 1, writer.clearCurrent(departure(ids[0]), 300) { true })
            assertNull(db.playSessionDao().get()?.currentEpisodeId)
            assertEquals(listOf(ids[1]), db.queueDao().queuedEpisodeIds())
        }

    @Test fun supersededDismissDoesNotCreateAMissingSingleton() =
        runTest {
            db.useWriterConnection { it.execSql("DELETE FROM play_session") }
            assertFailsWith<CancellationException> { writer.clearCurrent(null, 100) { false } }
            assertNull(db.playSessionDao().get())
            assertEquals(1, writer.clearCurrent(null, 200) { true })
        }

    @Test fun restoredForeignGenerationAndIncompleteAnchorsRemainReadable() =
        runTest {
            val ids = seed(3)
            val restored =
                PlaySessionEntity(
                    currentEpisodeId = ids[0],
                    contextType = ContextType.ALL,
                    contextAnchorSortDate = 999,
                    generation = 900,
                    updatedAt = 100,
                )
            db.playSessionDao().write(restored)
            assertEquals(900, writer.virtualQueue(20).generation)
            assertEquals(ids.drop(1), writer.virtualQueue(20).contextTail.map { it.episodeId })
            assertEquals(restored, db.playSessionDao().get())
            queue(ids.drop(1))
            assertEquals(901L, writer.onTransition(null, ids[1], QueueOrigin.UP_NEXT, revision(), 200))
            assertEquals(listOf(ids[2]), writer.virtualQueue(20).upNext.map { it.episodeId })
        }

    @Test fun failedArrivalAndLateSessionFailureRollBackAllEarlierWrites() =
        runTest {
            val ids = seed(2)
            writer.commitStart(start(ids[0]), 100)
            queue(listOf(ids[1]))
            val before = db.playSessionDao().get()
            assertFailsWith<IllegalArgumentException> {
                writer.onTransition(departure(ids[0], played = true), -1, QueueOrigin.CONTEXT, revision(), 200)
            }
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER reject_session BEFORE UPDATE ON play_session" +
                        " BEGIN SELECT RAISE(ABORT, 'test'); END",
                )
            }
            assertFailsWith<Exception> {
                writer.onTransition(departure(ids[0], played = true), ids[1], QueueOrigin.CONTEXT, revision(), 200)
            }
            assertEquals(before, db.playSessionDao().get())
            assertEquals(listOf(ids[1]), db.queueDao().queuedEpisodeIds())
            assertNull(db.positionDao().byEpisode(ids[0]))
            assertNull(db.episodeStateDao().byEpisode(ids[0])?.playedAt)
            assertNull(db.episodeStateDao().byEpisode(ids[1]))
            db.useWriterConnection { it.execSql("DELETE FROM play_session") }
            assertFailsWith<Exception> { writer.commitStart(start(ids[1]), 300) }
            assertNull(db.playSessionDao().get())
        }

    @Test fun previousRewritesOverlongHeadKeys() =
        runTest {
            val ids = seed(3)
            val keys =
                object : OrderKeys by ProtocolOrderKeys {
                    override fun between(
                        previous: String?,
                        next: String?,
                    ): String = "a".repeat(OrderKeys.MAX_LENGTH + 1)
                }
            val rewriting = SessionWriter(db, keys, capabilities)
            queue(listOf(ids[2]))
            rewriting.commitStart(start(ids[1]), 100)
            rewriting.goBack(QueueItem(ids[0], QueueOrigin.CONTEXT), departure(ids[1]), 200)
            assertEquals(listOf(ids[1], ids[2]), db.queueDao().entries().map { it.episodeId })
            assertEquals(ProtocolOrderKeys.rewrite(2), db.queueDao().entries().map { it.orderKey })
        }

    @Test fun concurrentStartsSerializeAndUnlinkedPlaybackLeavesSyncTablesEmpty() =
        runTest {
            val ids = seed(12)
            queue(ids)
            val generations = ids.map { id -> async { writer.commitStart(start(id), 100) } }.awaitAll()
            assertEquals((1L..12L).toSet(), generations.toSet())
            val virtual = writer.virtualQueue(20)
            assertEquals(12, virtual.generation)
            assertTrue(virtual.upNext.isEmpty())
            writer.clearCurrent(departure(virtual.current!!.episodeId, played = true), 200)
            db.useReaderConnection { connection ->
                for (table in listOf("sync_outbox", "sync_clock", "sync_parked", "sync_held")) {
                    connection.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
                        assertTrue(statement.step())
                        assertEquals(0, statement.getLong(0), table)
                    }
                }
            }
        }

    @Test fun staleEndAfterNewerStartIsRejectedWithoutSideEffects() =
        runTest {
            val ids = seed(2)
            writer.commitStart(start(ids[0]), 100)
            writer.commitStart(start(ids[1]), 200)
            assertNull(writer.endSession(departure(ids[0], played = true), SessionRevision(ids[0], 1), 300))
            val session = db.playSessionDao().get()!!
            assertEquals(ids[1], session.currentEpisodeId)
            assertEquals(2, session.generation)
            assertNull(db.episodeStateDao().byEpisode(ids[0])?.playedAt)
            assertNull(db.positionDao().byEpisode(ids[0]))
            assertEquals(3L, writer.endSession(departure(ids[1]), SessionRevision(ids[1], 2), 400))
        }

    @Test fun staleTransitionAfterNewerStartIsRejectedWithoutSideEffects() =
        runTest {
            val ids = seed(3)
            writer.commitStart(start(ids[0]), 100)
            queue(listOf(ids[2]))
            writer.commitStart(start(ids[1]), 200)
            assertNull(
                writer.onTransition(
                    departure(ids[0], played = true),
                    ids[2],
                    QueueOrigin.UP_NEXT,
                    SessionRevision(ids[0], 1),
                    300,
                ),
            )
            val session = db.playSessionDao().get()!!
            assertEquals(ids[1], session.currentEpisodeId)
            assertEquals(2, session.generation)
            assertEquals(listOf(ids[2]), db.queueDao().queuedEpisodeIds())
            assertNull(db.episodeStateDao().byEpisode(ids[0])?.playedAt)
            assertNull(db.episodeStateDao().byEpisode(ids[2])?.lastPlayedAt)
            assertNull(db.positionDao().byEpisode(ids[0]))
            assertEquals(
                3L,
                writer.onTransition(departure(ids[1]), ids[2], QueueOrigin.UP_NEXT, SessionRevision(ids[1], 2), 400),
            )
        }

    @Test fun sameEpisodeCurrentRejectsWhenGenerationAdvanced() =
        runTest {
            val ids = seed(2)
            writer.commitStart(start(ids[0]), 100)
            writer.commitStart(start(ids[1]), 200)
            writer.commitStart(start(ids[0]), 300)
            assertNull(writer.endSession(departure(ids[0]), SessionRevision(ids[0], 1), 400))
            val session = db.playSessionDao().get()!!
            assertEquals(ids[0], session.currentEpisodeId)
            assertEquals(3, session.generation)
        }

    @Test fun ensuredSingletonMatchesNullCurrentAtGenerationZero() =
        runTest {
            val ids = seed(1)
            assertNull(writer.endSession(null, SessionRevision(ids[0], 0), 50))
            assertNull(writer.onTransition(null, ids[0], QueueOrigin.CONTEXT, SessionRevision(null, 1), 60))
            assertEquals(0L, db.playSessionDao().get()?.generation)
            assertEquals(1L, writer.onTransition(null, ids[0], QueueOrigin.CONTEXT, SessionRevision(null, 0), 100))
            val session = db.playSessionDao().get()!!
            assertEquals(ids[0], session.currentEpisodeId)
            assertEquals(ids[0], session.contextAnchorEpisodeId)
        }

    private suspend fun revision() =
        db.playSessionDao().get()!!.let { SessionRevision(it.currentEpisodeId, it.generation) }

    private suspend fun seed(count: Int): List<Long> {
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        return (count downTo 1).map {
            db.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = it * 100L))
        }
    }

    private suspend fun queue(ids: List<Long>) {
        ids.zip(ProtocolOrderKeys.rewrite(ids.size)).forEach { (id, key) ->
            db.queueDao().insert(QueueEntryEntity(episodeId = id, orderKey = key, addedAt = 100))
        }
    }

    private fun start(
        id: Long,
        anchor: Long? = null,
        date: Long? = null,
    ) = SessionStart(id, context, ContextRetention.REPLACE, anchor, date)

    private fun departure(
        id: Long,
        played: Boolean = false,
    ) = Departure(id, 99, 1000, PositionSource.STREAM, 100, markPlayed = played, writePosition = true)
}
