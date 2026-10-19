// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.playback.QueueRepositoryImpl
import ch.lkmc.neutrodyne.core.data.playback.SessionWriter
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PlaySessionEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.PlaySessionInfo
import ch.lkmc.neutrodyne.core.model.QueueRemoval
import ch.lkmc.neutrodyne.core.model.RejectReason
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueRepositoryImplTest {
    private val clock = TestClock(nowMs = 10_000)
    private val db = newDb(clock)
    private val capabilities = FakeYouTubeCapabilitiesSource()
    private val sessions = SessionWriter(db, ProtocolOrderKeys, capabilities)
    private val repo = QueueRepositoryImpl(db, sessions, ProtocolOrderKeys, clock, capabilities)

    @AfterTest fun close() = db.close()

    @Test fun batchAddsAndAdjacentMovesPreserveIdentityAndUntouchedKeys() =
        runTest {
            val ids = seed(5)
            queue(ids.take(3))
            val original = db.queueDao().entries().associateBy { it.episodeId }
            assertEquals(AddResult.Added(2), repo.addNext(listOf(ids[1], ids[3], ids[1])))
            assertEquals(listOf(ids[1], ids[3], ids[0], ids[2]), order())
            assertEquals(
                original.getValue(ids[1]).id,
                db
                    .queueDao()
                    .entries()
                    .first()
                    .id,
            )
            assertEquals(
                original.getValue(ids[1]).addedAt,
                db
                    .queueDao()
                    .entries()
                    .first()
                    .addedAt,
            )
            assertEquals(AddResult.Added(2), repo.addLast(listOf(ids[0], ids[4])))
            assertEquals(listOf(ids[1], ids[3], ids[2], ids[0], ids[4]), order())
            val before = db.queueDao().entries().associateBy { it.episodeId }
            repo.move(ids[1], 1)
            val after = db.queueDao().entries().associateBy { it.episodeId }
            assertEquals(listOf(ids[3], ids[1], ids[2], ids[0], ids[4]), order())
            assertEquals(setOf(ids[1]), after.keys.filter { before[it] != after[it] }.toSet())
            repo.addLast(listOf(ids[1]))
            val moved = db.queueDao().entries().associateBy { it.episodeId }
            assertEquals(setOf(ids[1]), moved.keys.filter { after[it] != moved[it] }.toSet())
            repo.move(ids[4], -99)
            assertEquals(ids[4], order().first())
            repo.move(ids[4], 99)
            assertEquals(ids[4], order().last())
            val unchanged = db.queueDao().entries()
            repo.move(-1, 0)
            repo.move(ids[4], 99)
            assertEquals(unchanged, db.queueDao().entries())
            assertEquals(AddResult.Added(0), repo.addNext(emptyList()))
        }

    @Test fun aRefusedBatchWritesNothingAndExternalRowsRemainQueuedAfterCapabilityFlips() =
        runTest {
            val ids = seed(3)
            queue(listOf(ids[1]))
            db.playSessionDao().write(PlaySessionEntity(currentEpisodeId = ids[0], generation = 70, updatedAt = 1))
            val before = db.queueDao().entries()
            db.episodeDao().setAvailability(ids[2], Availability.UPCOMING)
            for ((batch, reason) in listOf(
                listOf(ids[1], ids[0]) to RejectReason.ALREADY_PLAYING,
                listOf(ids[1], -1L) to RejectReason.UNAVAILABLE,
                listOf(ids[1], ids[2]) to RejectReason.UNAVAILABLE,
            )) {
                assertEquals(AddResult.Rejected(reason), repo.addLast(batch))
                assertEquals(before, db.queueDao().entries())
            }
            for (source in listOf(SourceType.YOUTUBE_CHANNEL, SourceType.YOUTUBE_PLAYLIST)) {
                val podcast = db.podcastDao().insertPodcast(podcastEntity { copy(sourceType = source) })
                val video =
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = podcast) { copy(externalMediaId = "vid") },
                    )
                capabilities.capabilities.value = capabilities.capabilities.value.copy(inAppPlayback = false)
                assertEquals(AddResult.Rejected(RejectReason.YOUTUBE_EXTERNAL), repo.addNext(listOf(video)))
                capabilities.capabilities.value = capabilities.capabilities.value.copy(inAppPlayback = true)
                assertEquals(AddResult.Added(1), repo.addLast(listOf(video)))
                capabilities.capabilities.value = capabilities.capabilities.value.copy(inAppPlayback = false)
                assertTrue(video in order())
            }
            assertEquals(70, db.playSessionDao().get()?.generation)
        }

    @Test fun tiedAndOverlongKeysRewriteAndLateFailureRollsBackTheBatch() =
        runTest {
            val ids = seed(4)
            for (id in ids.take(2)) db.queueDao().insert(QueueEntryEntity(episodeId = id, orderKey = "a0", addedAt = 1))
            db.queueDao().insert(QueueEntryEntity(episodeId = ids[2], orderKey = "a1", addedAt = 1))
            db.syncStateDao().upsert(SyncStateEntity(enabled = true, nodeId = "0000000000000001"))
            repo.move(ids[2], 1)
            assertEquals(listOf(ids[0], ids[2], ids[1]), order())
            assertEquals(ProtocolOrderKeys.rewrite(3), db.queueDao().entries().map { it.orderKey })
            val longKeys =
                object : OrderKeys by ProtocolOrderKeys {
                    override fun between(
                        previous: String?,
                        next: String?,
                    ): String = "a".repeat(OrderKeys.MAX_LENGTH + 1)
                }
            val rewriting = QueueRepositoryImpl(db, sessions, longKeys, clock, capabilities)
            rewriting.addNext(listOf(ids[3]))
            assertEquals(listOf(ids[3], ids[0], ids[2], ids[1]), order())
            assertEquals(ProtocolOrderKeys.rewrite(4), db.queueDao().entries().map { it.orderKey })
            val fresh = seed(1).single()
            val before = db.queueDao().entries()
            val captured = db.syncOutboxDao().rows()
            val stamp = db.syncStateDao().get()?.hlc
            assertTrue(captured.any { it.coll == "upnext" && it.field == "ok" })
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER reject_insert BEFORE INSERT ON queue_entry BEGIN SELECT RAISE(ABORT, 'test'); END",
                )
            }
            assertFailsWith<Exception> { repo.addNext(listOf(ids[1], fresh)) }
            assertEquals(before, db.queueDao().entries())
            assertEquals(captured, db.syncOutboxDao().rows())
            assertEquals(stamp, db.syncStateDao().get()?.hlc)
        }

    @Test fun largeQueuesChunkBindsAndQueueEditsDoNotAdvanceGeneration() =
        runTest {
            val ids = seed(503)
            db.playSessionDao().write(PlaySessionEntity(generation = 70, updatedAt = 1))
            assertEquals(AddResult.Added(ids.size), repo.addLast(ids))
            assertEquals(ids, order())
            assertEquals(ids, sessions.virtualQueue(20).upNext.map { it.episodeId })
            assertEquals(ids, repo.observeUpNext().first().map { it.row.id })
            repo.remove(ids.take(501))
            assertEquals(ids.takeLast(2), order())
            repo.clearUpNext()
            assertTrue(order().isEmpty())
            assertEquals(70, db.playSessionDao().get()?.generation)
            db.useWriterConnection { it.execSql("DELETE FROM play_session") }
            repo.clearContext()
            assertEquals(1, db.playSessionDao().get()?.generation)
        }

    @Test fun realObserversTrackTitlesCapabilitiesAndCommittedSnapshots() =
        runTest {
            val tracked = newDb(clock, StandardTestDispatcher(testScheduler))
            try {
                val store = SessionWriter(tracked, ProtocolOrderKeys, capabilities)
                val queue = QueueRepositoryImpl(tracked, store, ProtocolOrderKeys, clock, capabilities)
                val podcast = tracked.podcastDao().insertPodcast(podcastEntity())
                val yt =
                    tracked.podcastDao().insertPodcast(
                        podcastEntity { copy(sourceType = SourceType.YOUTUBE_CHANNEL) },
                    )
                val current = tracked.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = 300))
                val next = tracked.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = 200))
                val tail = tracked.episodeDao().insertStub(episodeEntity(podcastId = yt, sortDate = 100))
                val group = tracked.groupDao().insert(groupEntity(name = "Before", orderKey = "a0"))
                tracked.groupDao().insertMember(memberEntity(group, podcast, "a0"))
                tracked.groupDao().insertMember(memberEntity(group, yt, "a1"))
                val context =
                    PlayContextSpec(ContextType.GROUP, group, FeedOrder.NEWEST_FIRST, 1, MediaFilter.ALL, null, false)
                store.commitStart(SessionStart(current, context, ContextRetention.REPLACE, null, null), clock.now())
                queue.addLast(listOf(next))
                val headers = Channel<PlaySessionInfo?>(Channel.UNLIMITED)
                val rows = Channel<List<UpNextItem>>(Channel.UNLIMITED)
                val snapshots = Channel<VirtualQueue>(Channel.UNLIMITED)
                backgroundScope.launch { queue.observeSession().collect { headers.send(it) } }
                backgroundScope.launch { queue.observeUpNext().collect { rows.send(it) } }
                backgroundScope.launch { queue.observeVirtualQueue(20).collect { snapshots.send(it) } }
                assertEquals("Before", headers.receive()?.context?.title)
                assertEquals(listOf(next), rows.receive().map { it.row.id })
                val initial = snapshots.receive()
                assertTrue(initial.contextTail.isEmpty())
                tracked.groupDao().rename(group, "After", "after", clock.now() + 1)
                assertEquals("After", headers.receive()?.context?.title)
                tracked.episodeDao().setAvailability(next, Availability.UNAVAILABLE)
                assertEquals(
                    Availability.UNAVAILABLE,
                    rows
                        .receive()
                        .single()
                        .row.availability,
                )
                capabilities.capabilities.value = capabilities.capabilities.value.copy(inAppPlayback = true)
                assertEquals(listOf(tail), snapshots.receive().contextTail.map { it.episodeId })
                queue.clearContext()
                val cleared = snapshots.receive()
                assertEquals(initial.generation + 1, cleared.generation)
                assertEquals(current, cleared.current?.episodeId)
                assertEquals(listOf(next), cleared.upNext.map { it.episodeId })
                assertTrue(cleared.contextTail.isEmpty())
                assertNull(headers.receive()?.context)
            } finally {
                tracked.close()
            }
        }

    @Test fun removeWithUndoSnapshotsStoredOrderAndUndoRestoresAroundConcurrentEdits() =
        runTest {
            val ids = seed(5)
            queue(ids.take(4))
            val removal = repo.removeWithUndo(listOf(ids[1], ids[2], -1L))
            // The token is the stored truth: the unqueued request id is absent and the order is
            // the pre-delete one, both captured inside the delete's transaction.
            assertEquals(QueueRemoval(listOf(ids[1], ids[2]), ids.take(4)), removal)
            assertEquals(listOf(ids[0], ids[3]), order())

            // Concurrent edits: a new tail item and a survivor move to the front.
            repo.addLast(listOf(ids[4]))
            repo.move(ids[3], 0)
            assertEquals(listOf(ids[3], ids[0], ids[4]), order())
            val survivors = db.queueDao().entries()

            repo.undoRemoval(removal)
            // ids[1]/ids[2] land before their surviving original successor ids[3], keeping the
            // concurrent order of ids[3], ids[0], ids[4] and the removed pair's relative order.
            assertEquals(listOf(ids[1], ids[2], ids[3], ids[0], ids[4]), order())
            assertEquals(survivors, db.queueDao().entries().filter { it.episodeId !in removal.removedIds })
        }

    @Test fun undoSkipsRequeuedCurrentPlayedAndMissingRowsButRestoresUnavailable() =
        runTest {
            val ids = seed(5)
            queue(ids)
            val removal = repo.removeWithUndo(ids)
            assertEquals(ids, removal.removedIds)

            // Since the removal: ids[0] was requeued (lands at the end), ids[1] became current,
            // ids[2] was played, ids[3] turned unavailable, ids[4]'s episode vanished.
            repo.addLast(listOf(ids[0]))
            db.playSessionDao().write(PlaySessionEntity(currentEpisodeId = ids[1], generation = 70, updatedAt = 1))
            db.episodeStateDao().upsert(episodeStateEntity(ids[2], playedAt = 5))
            db.episodeDao().setAvailability(ids[3], Availability.UNAVAILABLE)
            db.useWriterConnection { it.execSql("DELETE FROM episode WHERE id = ${ids[4]}") }

            repo.undoRemoval(removal)
            // Only ids[3] restores — unplayed, existing, non-current; its old membership means
            // the availability gate does not apply — and it lands after ids[0], its closest
            // surviving predecessor, which stays where the re-add put it.
            assertEquals(listOf(ids[0], ids[3]), order())
            assertEquals(70, db.playSessionDao().get()?.generation)
        }

    @Test fun undoFallsBackToSurvivingPredecessorThenToClampedIndex() =
        runTest {
            val ids = seed(4)
            queue(ids.take(3))

            // Last item removed: no original successor survives, so it follows its predecessor —
            // ahead of the concurrently appended ids[3], whose relative order is untouched.
            val last = repo.removeWithUndo(listOf(ids[2]))
            repo.addLast(listOf(ids[3]))
            repo.undoRemoval(last)
            assertEquals(listOf(ids[0], ids[1], ids[2], ids[3]), order())

            // No survivor at all: the first item's old index 0 clamps ahead of a foreign queue.
            val first = repo.removeWithUndo(listOf(ids[0]))
            repo.remove(listOf(ids[1], ids[2], ids[3]))
            val foreign = seed(1).single()
            queue(listOf(foreign))
            repo.undoRemoval(first)
            assertEquals(listOf(ids[0], foreign), order())
        }

    @Test fun undoPreservesRemovedOrderWhenSurvivingAnchorsWereReversed() =
        runTest {
            val ids = seed(5)
            queue(ids)
            val removal = repo.removeWithUndo(listOf(ids[1], ids[3]))
            repo.move(ids[4], 0)
            val survivors = db.queueDao().entries()

            repo.undoRemoval(removal)

            assertEquals(removal.removedIds, order().filter { it in removal.removedIds })
            assertEquals(survivors, db.queueDao().entries().filter { it.episodeId !in removal.removedIds })
        }

    @Test fun clearWithUndoRestoresTheStoredOrderAndNeverTouchesGeneration() =
        runTest {
            val ids = seed(4)
            queue(ids)
            db.playSessionDao().write(PlaySessionEntity(generation = 70, updatedAt = 1))

            val removal = repo.clearWithUndo()
            assertEquals(QueueRemoval(ids, ids), removal)
            assertTrue(order().isEmpty())

            repo.undoRemoval(removal)
            assertEquals(ids, order())
            assertEquals(70, db.playSessionDao().get()?.generation)
        }

    @Test fun virtualQueueRequiresNonnegativeLimit() {
        assertFailsWith<IllegalArgumentException> { repo.observeVirtualQueue(-1) }
    }

    private suspend fun seed(count: Int): List<Long> {
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        return (1..count).map { db.episodeDao().insertStub(episodeEntity(podcastId = podcast)) }
    }

    private suspend fun queue(ids: List<Long>) {
        ids.zip(ProtocolOrderKeys.rewrite(ids.size)).forEach { (id, key) ->
            db.queueDao().insert(QueueEntryEntity(episodeId = id, orderKey = key, addedAt = 1))
        }
    }

    private suspend fun order(): List<Long> = db.queueDao().entries().map { it.episodeId }
}
