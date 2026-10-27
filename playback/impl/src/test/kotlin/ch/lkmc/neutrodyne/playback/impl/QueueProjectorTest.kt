// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.playback.core.PlayerWindow
import ch.lkmc.neutrodyne.playback.core.StartOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class QueueProjectorTest {
    @Test fun startConvergesAndReorderKeepsCurrentSource() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(4)
                f.attach()
                f.start(ids, 42_000)
                awaitAdapter { f.player.ids() == ids }
                val current = f.player.currentMediaItem!!.localConfiguration
                f.queue.move(ids[2], 0)
                awaitAdapter { f.player.ids() == listOf(ids[0], ids[2], ids[1], ids[3]) }
                assertEquals(1, f.player.calls.count { it == "set" })
                assertEquals(1, f.player.calls.count { it == "move" })
                assertEquals(current, f.player.currentMediaItem!!.localConfiguration)
                assertEquals(42_000L, f.player.currentPosition)
                assertEquals(
                    ids[0],
                    f.sessions
                        .virtualQueue(20)
                        .current!!
                        .episodeId,
                )
                assertTrue(f.failures.toString(), f.failures.isEmpty())
            } finally {
                f.close()
            }
        }

    @Test fun restoreLoadsPausedWithoutPrepare() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                f.positions.save(id, 72_000, null, PositionSource.STREAM, f.clock.now(), 0) { true }
                f.sessions.commitStart(SessionStart(id, null, ContextRetention.REPLACE, null, null), f.clock.now())
                f.attach()
                awaitAdapter { f.player.ids() == listOf(id) }
                assertFalse(f.player.playWhenReady)
                assertEquals(72_000L, f.player.currentPosition)
                assertFalse(f.player.calls.contains("prepare"))
                assertEquals(Player.STATE_IDLE, f.player.playbackState)
            } finally {
                f.close()
            }
        }

    @Test fun foreignGenerationsLoadPaused() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids.take(2))
                awaitAdapter { f.player.ids().size == 2 }
                f.player.play()
                val row = f.db.playSessionDao().get()!!
                f.positions.save(ids[2], 91_000, null, PositionSource.STREAM, f.clock.now(), 0) { true }
                f.db.playSessionDao().write(row.copy(currentEpisodeId = ids[2], generation = row.generation + 1))
                awaitAdapter { f.player.episodeIdOrNull() == ids[2] }
                assertFalse(f.player.playWhenReady)
                assertEquals(91_000L, f.player.currentPosition)
                f.positions.save(ids[2], 104_000, null, PositionSource.STREAM, f.clock.now(), 0) { true }
                val next = f.db.playSessionDao().get()!!
                f.db.playSessionDao().write(next.copy(generation = next.generation + 1))
                awaitAdapter { f.player.currentPosition == 104_000L }
                assertFalse(f.player.playWhenReady)
            } finally {
                f.close()
            }
        }

    @Test fun externalStartWritesBeforeAsyncApplyAndDoesNotSetTwice() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.attach()
                f.projector.beginOwnWrite()
                val gen =
                    f.sessions.commitStart(
                        SessionStart(ids[0], null, ContextRetention.REPLACE, null, null),
                        f.clock.now(),
                    )
                f.queue.addLast(ids.drop(1))
                val snapshot = f.sessions.virtualQueue(20)
                assertEquals(ids[0], snapshot.current!!.episodeId)

                // Finish Room reads first: real IO can advance the virtual apply timeout.
                val items = f.projector.prepareExternalStart(StartOutcome.Committed(snapshot, 0))
                runCurrent()
                assertEquals(0, f.player.mediaItemCount)
                f.player.setMediaItems(items, 0, 0)
                awaitAdapter { f.player.ids() == ids }
                runCurrent()
                assertEquals(1, f.player.calls.count { it == "set" })
                assertEquals(gen, f.sessions.virtualQueue(20).generation)
                assertNotNull(f.index[ids[1]])
            } finally {
                f.close()
            }
        }

    @Test fun localMarkAdvancesExactlyOnceWhilePaused() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                f.fakeClock.advanceTime(10)
                f.positions.markPlayed(ids[0], f.clock.now())
                awaitAdapter { f.player.episodeIdOrNull() == ids[1] && f.player.ids().size == 2 }
                runCurrent()
                assertEquals(
                    ids[1],
                    f.sessions
                        .virtualQueue(20)
                        .current!!
                        .episodeId,
                )
                assertFalse(f.player.playWhenReady)
                assertEquals(1, f.player.calls.count { it == "set" })
                assertTrue(f.failures.toString(), f.failures.isEmpty())
            } finally {
                f.close()
            }
        }

    @Test fun olderPlayedMarkBelongsToReplay() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.positions.markPlayed(ids[0], f.clock.now() - 100)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                runCurrent()
                assertEquals(ids[0], f.player.episodeIdOrNull())
            } finally {
                f.close()
            }
        }

    @Test fun metadataUpdatesKeepLocalConfigurationAndDoNotPrepare() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                val old = f.player.currentMediaItem!!.localConfiguration
                f.player.calls.clear()
                val podcast = f.lookup.get(ids[0])!!.podcastId
                f.db.podcastDao().setCustomTitle(podcast, "Renamed")
                awaitAdapter {
                    f.player.currentMediaItem!!
                        .mediaMetadata.albumTitle
                        .toString() == "Renamed"
                }
                assertEquals(old, f.player.currentMediaItem!!.localConfiguration)
                assertFalse(f.player.calls.contains("set"))
                assertFalse(f.player.calls.contains("prepare"))
            } finally {
                f.close()
            }
        }

    @Test fun unavailableRowsStayStoredButLeaveProjection() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                f.db.episodeDao().setAvailability(ids[1], Availability.UNAVAILABLE)
                awaitAdapter { f.player.ids() == listOf(ids[0], ids[2]) }
                assertEquals(
                    2,
                    f.queue
                        .observeUpNext()
                        .first()
                        .size,
                )
                assertNull(f.index[ids[1]])
                assertEquals(1, f.player.calls.count { it == "set" })
            } finally {
                f.close()
            }
        }

    @Test fun projectionCapsAt100AndTopsUpAfterRemoval() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(103)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.mediaItemCount == 101 }
                assertEquals(ids.take(101), f.player.ids())
                assertEquals(
                    102,
                    f.queue
                        .observeUpNext()
                        .first()
                        .size,
                )
                f.queue.remove(listOf(ids[1]))
                awaitAdapter { f.player.ids() == listOf(ids[0]) + ids.drop(2).take(100) }
                assertEquals(1, f.player.calls.count { it == "set" })
            } finally {
                f.close()
            }
        }

    @Test fun unsubscribeClearsPlayerAndIndex() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                val podcast = f.lookup.get(ids[0])!!.podcastId
                f.player.pause()
                f.db.podcastDao().deleteCascade(podcast, f.clock.now())
                awaitAdapter { f.player.mediaItemCount == 0 }
                assertNull(f.index[ids[0]])
                assertFalse(f.player.playWhenReady)
            } finally {
                f.close()
            }
        }

    @Test fun changedFingerprintReloadsOnlyTail() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.attach()
                f.start(ids, 63_000)
                awaitAdapter { f.player.ids() == ids }
                val current = f.player.currentMediaItem!!.localConfiguration
                val tail = f.player.getMediaItemAt(1).localConfiguration
                f.player.calls.clear()
                f.sql("UPDATE episode SET enclosureUrl = 'https://example.com/new.mp3', enclosureLength = 999")
                awaitAdapter { f.player.getMediaItemAt(1).localConfiguration != tail }
                assertEquals(current, f.player.currentMediaItem!!.localConfiguration)
                assertEquals(63_000L, f.player.currentPosition)
                assertFalse(f.player.calls.contains("set"))
                assertFalse(f.player.calls.contains("prepare"))
            } finally {
                f.close()
            }
        }

    @Test fun groupDeletionKeepsPlayingCurrentWithoutReload() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(4)
                val podcast = f.lookup.get(ids[0])!!.podcastId
                val group = f.db.groupDao().insert(groupEntity(orderKey = "a0"))
                f.db.groupDao().insertMember(memberEntity(group, podcast, "a0"))
                val context =
                    PlayContextSpec(
                        ContextType.GROUP,
                        group,
                        FeedOrder.NEWEST_FIRST,
                        0,
                        MediaFilter.ALL,
                        null,
                        false,
                    )
                f.attach()
                f.start(ids.take(2), 63_000, context)
                awaitAdapter { f.player.mediaItemCount == 4 }
                f.player.advancing = true
                f.player.play()
                f.player.calls.clear()
                f.db.withWriteTransaction {
                    f.db.groupDao().clearGroupContext(group, f.clock.now())
                    f.db.groupDao().deleteGroup(group)
                }
                awaitAdapter { f.player.ids() == ids.take(2) }
                assertTrue(f.player.playWhenReady)
                assertEquals(63_000L, f.player.currentPosition)
                assertFalse(f.player.calls.contains("set"))
                assertFalse(f.player.calls.contains("prepare"))
                f.player.advancing = false
            } finally {
                f.close()
            }
        }

    @Test fun rawPlaylistEditIsReconciledFromDatabase() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                f.player.removeMediaItem(1)
                awaitAdapter { f.player.ids() == ids }
                assertEquals(1, f.player.calls.count { it == "set" })
                f.player.moveMediaItem(2, 1)
                awaitAdapter { f.player.ids() == ids }
                assertEquals(
                    2,
                    f.queue
                        .observeUpNext()
                        .first()
                        .size,
                )
            } finally {
                f.close()
            }
        }

    @Test fun largeTailReplacementKeepsEverySourceIndexed() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(65)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                f.projector.beginOwnWrite()
                try {
                    for (id in ids.drop(1)) f.queue.move(id, 0)
                } finally {
                    f.projector.endOwnWrite(f.sessions.virtualQueue(20).generation)
                }
                val expected = listOf(ids[0]) + ids.drop(1).reversed()
                awaitAdapter { f.player.ids() == expected }
                assertEquals(1, f.player.calls.count { it == "set" })
                for (id in ids) assertNotNull("Source $id disappeared", f.index[id])
            } finally {
                f.close()
            }
        }

    @Test fun externalAddKeepsCurrentPinAndWriteBracket() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids.take(2), 65_000)
                awaitAdapter { f.player.ids() == ids.take(2) }
                val since = f.tracker.currentSinceMs
                f.fakeClock.advanceTime(100)
                f.projector.beginOwnWrite()
                f.queue.addLast(ids.takeLast(1))
                val window = f.projector.prepareExternalEdit(f.sessions.virtualQueue(20))
                runCurrent()
                assertEquals(ids.take(2), f.player.ids())
                f.player.addMediaItems(window.takeLast(1))
                awaitAdapter { f.player.ids() == ids }
                runCurrent()
                assertEquals(since, f.tracker.currentSinceMs)
                assertEquals(65_000L, f.player.currentPosition)
                assertEquals(1, f.player.calls.count { it == "set" })
                assertNotNull(f.index[ids[2]])
            } finally {
                f.close()
            }
        }

    @Test fun abandonedExternalApplyTimesOutAndLoadsDurableStatePaused() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                f.attach()
                f.projector.beginOwnWrite()
                f.sessions.commitStart(SessionStart(id, null, ContextRetention.REPLACE, null, null), f.clock.now())
                f.projector.prepareExternalStart(StartOutcome.Committed(f.sessions.virtualQueue(20), 0))
                runCurrent()
                assertEquals(0, f.player.mediaItemCount)
                advanceTimeBy(2_000)
                runCurrent()
                awaitAdapter { f.player.episodeIdOrNull() == id }
                assertFalse(f.player.playWhenReady)
                assertEquals(1, f.player.calls.count { it == "set" })
                assertFalse(f.player.calls.contains("prepare"))
            } finally {
                f.close()
            }
        }

    @Test fun capabilityFlipRemovesOnlyTailAndKeepsGeneration() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(3)
                f.caps.capabilities.value =
                    f.caps.capabilities.value
                        .copy(inAppPlayback = true)
                f.sql("UPDATE podcast SET sourceType = 'YOUTUBE_CHANNEL'")
                f.sql("UPDATE episode SET externalMediaId = 'abcdefghijk'")
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                val generation = f.sessions.virtualQueue(20).generation
                f.caps.capabilities.value =
                    f.caps.capabilities.value
                        .copy(inAppPlayback = false)
                awaitAdapter { f.player.ids() == ids.take(1) }
                assertNull(f.index[ids[1]])
                assertEquals(generation, f.sessions.virtualQueue(20).generation)
                f.caps.capabilities.value =
                    f.caps.capabilities.value
                        .copy(inAppPlayback = true)
                awaitAdapter { f.player.ids() == ids }
                assertEquals(1, f.player.calls.count { it == "set" })
            } finally {
                f.close()
            }
        }

    @Test fun startingAnotherItemUnpinsOutgoingEvenWhenItStaysQueued() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.attach()
                f.start(ids)
                awaitAdapter { f.player.ids() == ids }
                f.resolver.resolveDataSpec(DataSpec(episodeUri(ids[0])))
                assertNotNull(f.resolver.streamKind(ids[0]))
                f.start(ids.reversed())
                awaitAdapter { f.player.ids() == ids.reversed() }
                assertNull(f.resolver.streamKind(ids[0]))
                assertNotNull(f.index[ids[0]])
            } finally {
                f.close()
            }
        }

    @Test fun failedTransitionKeepsPlayingAndNextTransitionRepairsSession() =
        runTest {
            var attempts = 0
            var refuseTransition = true
            val f =
                AdapterFixture(this, sessionStore = { real ->
                    object : PlaySessionStore by real {
                        override suspend fun onTransition(
                            departure: Departure?,
                            arrivedEpisodeId: Long,
                            arrivedOrigin: QueueOrigin,
                            now: Long,
                        ): Long {
                            attempts++
                            if (refuseTransition) throw IOException("Injected transition failure")
                            return real.onTransition(departure, arrivedEpisodeId, arrivedOrigin, now)
                        }
                    }
                })
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids)
                f.db.podcastDao().setCustomTitle(f.lookup.get(ids[0])!!.podcastId, "Ready")
                awaitAdapter {
                    f.player.currentMediaItem
                        ?.mediaMetadata
                        ?.albumTitle
                        .toString() == "Ready"
                }
                f.player.seekToNextMediaItem()
                awaitAdapter { attempts == 2 && f.failures.size == 1 }
                f.db.podcastDao().setCustomTitle(f.lookup.get(ids[0])!!.podcastId, "After failure")
                awaitAdapter {
                    f.player.currentMediaItem
                        ?.mediaMetadata
                        ?.albumTitle
                        .toString() == "After failure"
                }
                assertEquals(ids[1], f.player.episodeIdOrNull())
                assertEquals(
                    ids[0],
                    f.sessions
                        .virtualQueue(20)
                        .current!!
                        .episodeId,
                )
                assertEquals(1, f.player.calls.count { it == "set" })
                refuseTransition = false
                f.player.seekToNextMediaItem()
                // The failed promotion rolled back, so its unconsumed Up next row survives.
                awaitAdapter { f.player.ids() == listOf(ids[2], ids[1]) }
                assertEquals(
                    ids[2],
                    f.sessions
                        .virtualQueue(20)
                        .current!!
                        .episodeId,
                )
                assertEquals(3, attempts)
            } finally {
                f.close()
            }
        }

    /**
     * A snapshot planned last inside the bracket can be older than the bracket's own
     * committed apply (the delayed-emission ordering a loaded executor produces).
     * Closing the write must never replay that older content: the externally added
     * item stays in the player and in the source index through the whole drain.
     */
    @Test fun externalAddNeverReplaysPreAddSnapshot() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids.take(2), 65_000)
                awaitAdapter { f.player.ids() == ids.take(2) }
                val preAdd = f.sessions.virtualQueue(20)
                f.projector.beginOwnWrite()
                f.queue.addLast(ids.takeLast(1))
                val window = f.projector.prepareExternalEdit(f.sessions.virtualQueue(20))
                repeat(40) {
                    runCurrent()
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(3)
                }
                assertNull(f.planner.plan(preAdd, emptyMap(), PlayerWindow()))
                f.player.addMediaItems(window.takeLast(1))
                var droppedIndex = false
                var droppedPlayer = false
                var stable = 0
                var iter = 0
                while (iter++ < 2_000 && stable < 40) {
                    runCurrent()
                    shadowOf(Looper.getMainLooper()).idle()
                    if (f.index[ids[2]] == null) droppedIndex = true
                    if (f.player.ids() != ids) droppedPlayer = true
                    stable =
                        if (f.player.ids() == ids && f.index[ids[2]] != null) stable + 1 else 0
                    Thread.sleep(2)
                }
                assertFalse("index dropped the externally added source", droppedIndex)
                assertFalse("player dropped the externally added item", droppedPlayer)
                assertEquals(ids, f.player.ids())
                assertNotNull(f.index[ids[2]])
            } finally {
                f.close()
            }
        }

    /**
     * Same stale-stash shape for batched durable edits: a snapshot read between two
     * moves is planned last in the bracket. Closing the write must project the
     * newest durable order, never revert the player to the older mid-sequence one.
     */
    @Test fun batchedMovesNeverReplayMidSequenceSnapshot() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(6)
                f.attach()
                f.start(ids, 1_000)
                awaitAdapter { f.player.ids() == ids }
                f.projector.beginOwnWrite()
                f.queue.move(ids[3], 0)
                val mid = f.sessions.virtualQueue(20)
                f.queue.move(ids[4], 0)
                repeat(40) {
                    runCurrent()
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(3)
                }
                assertNull(f.planner.plan(mid, emptyMap(), PlayerWindow()))
                f.projector.endOwnWrite(f.sessions.virtualQueue(20).generation)
                val midIds = listOf(ids[0], ids[3], ids[1], ids[2], ids[4], ids[5])
                val expected = listOf(ids[0], ids[4], ids[3], ids[1], ids[2], ids[5])
                var reverted = false
                var stable = 0
                var iter = 0
                while (iter++ < 2_000 && stable < 40) {
                    runCurrent()
                    shadowOf(Looper.getMainLooper()).idle()
                    if (f.player.ids() == midIds) reverted = true
                    stable = if (f.player.ids() == expected) stable + 1 else 0
                    Thread.sleep(2)
                }
                assertFalse("player replayed a mid-sequence durable order", reverted)
                assertEquals(expected, f.player.ids())
            } finally {
                f.close()
            }
        }

    /**
     * A bracket that commits and applies while a refresh resolves its read's
     * media/session input must not be reverted when the read resumes.
     */
    @Test fun externalAddApplyCannotBeRevertedByDelayedRefreshRead() =
        runTest {
            lateinit var gate: GatedLookup
            val f =
                AdapterFixture(
                    this,
                    mediaLookup = { real -> GatedLookup(real).also { gate = it } },
                )
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids.take(2), 65_000)
                awaitAdapter { f.player.ids() == ids.take(2) }
                awaitProjectionReady(f, ids[0])
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                gate.barrier = {
                    reached.complete(Unit)
                    release.await()
                }
                // Trigger a durable refresh without leaving the player diverged
                // from the pending external apply: remove and re-add the same item.
                val second = f.player.getMediaItemAt(1)
                f.player.removeMediaItem(1)
                f.player.addMediaItem(1, second)
                awaitAdapter("refresh job never reached the media lookup") { reached.isCompleted }
                f.projector.beginOwnWrite()
                f.queue.addLast(ids.takeLast(1))
                val window = f.projector.prepareExternalEdit(f.sessions.virtualQueue(20))
                f.player.addMediaItems(window.takeLast(1))
                awaitAdapter { f.player.ids() == ids }
                assertNotNull(f.index[ids[2]])
                release.complete(Unit)
                var dropped = false
                var stable = 0
                var iter = 0
                while (iter++ < 2_000 && stable < 40) {
                    runCurrent()
                    shadowOf(Looper.getMainLooper()).idle()
                    if (f.player.ids() != ids || f.index[ids[2]] == null) dropped = true
                    stable = if (f.player.ids() == ids && f.index[ids[2]] != null) stable + 1 else 0
                    Thread.sleep(2)
                }
                assertFalse("a read suspended inside a newer own write reverted it", dropped)
                assertEquals(ids, f.player.ids())
                assertNotNull(f.index[ids[2]])
            } finally {
                f.close()
            }
        }

    /**
     * A bracket that bumps the revision without committing must not silently drop
     * a pending reconciliation: the deferred refresh re-arms and converges without
     * relying on any later database emission to heal it.
     */
    @Test fun noCommitBracketDuringDurableReadKeepsReconcile() =
        runTest {
            lateinit var gate: GatedSessions
            val f =
                AdapterFixture(
                    this,
                    sessionStore = { real -> GatedSessions(real).also { gate = it } },
                )
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids.take(2), 1_000)
                awaitAdapter { f.player.ids() == ids.take(2) }
                awaitProjectionReady(f, ids[0])
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                gate.barrier = {
                    reached.complete(Unit)
                    release.await()
                }
                f.player.removeMediaItem(1)
                awaitAdapter("refresh job never reached the durable read") { reached.isCompleted }
                f.projector.beginOwnWrite()
                f.projector.endOwnWrite(null)
                release.complete(Unit)
                awaitAdapter("pending reconciliation was lost across a no-commit bracket") {
                    f.player.ids() == ids.take(2)
                }
            } finally {
                f.close()
            }
        }

    /**
     * The revision fence holds through the plan mutex wait: a refresh queued
     * behind another job while a bracket removes an item discards its
     * pre-bracket read instead of re-applying it.
     */
    @Test fun refreshReadWaitingOnMutexAcrossBracketIsDiscarded() =
        runTest {
            lateinit var lookupGate: GatedLookup
            lateinit var sessionGate: GatedSessions
            val f =
                AdapterFixture(
                    this,
                    sessionStore = { real -> GatedSessions(real).also { sessionGate = it } },
                    mediaLookup = { real -> GatedLookup(real).also { lookupGate = it } },
                )
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids, 1_000)
                awaitAdapter { f.player.ids() == ids }
                awaitProjectionReady(f, ids[0])
                val holderAtGate = CompletableDeferred<Unit>()
                val holderRelease = CompletableDeferred<Unit>()
                lookupGate.barrier = {
                    holderAtGate.complete(Unit)
                    holderRelease.await()
                }
                val holder = async { f.projector.prepareExternalEdit(f.sessions.virtualQueue(20)) }
                awaitAdapter("mutex holder never reached the lookup gate") { holderAtGate.isCompleted }
                val readAtGate = CompletableDeferred<Unit>()
                val readRelease = CompletableDeferred<Unit>()
                sessionGate.barrier = {
                    readAtGate.complete(Unit)
                    readRelease.await()
                }
                f.player.removeMediaItem(1)
                awaitAdapter("refresh job never reached the durable read") { readAtGate.isCompleted }
                readRelease.complete(Unit)
                repeat(20) {
                    runCurrent()
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(2)
                }
                f.projector.beginOwnWrite()
                f.queue.remove(listOf(ids[2]))
                f.projector.endOwnWrite(f.sessions.virtualQueue(20).generation)
                val opsBefore = f.player.calls.size
                holderRelease.complete(Unit)
                holder.await()
                awaitAdapter("durable state never reconciled") { f.player.ids() == ids.take(2) }
                val postOps = f.player.calls.drop(opsBefore)
                val add = postOps.indexOf("add")
                val remove = postOps.indexOf("remove")
                assertFalse(
                    "a read that waited on the plan mutex across a bracket was applied",
                    add != -1 && (remove == -1 || add < remove),
                )
            } finally {
                f.close()
            }
        }

    @Test fun unbracketedEmissionCannotBeOverwrittenByAnOlderRefresh() =
        runTest {
            lateinit var gate: GatedLookup
            val f = AdapterFixture(this, mediaLookup = { real -> GatedLookup(real).also { gate = it } })
            try {
                val ids = f.seed(3)
                f.attach()
                f.start(ids.take(2), 65_000)
                awaitAdapter { f.player.ids() == ids.take(2) }
                awaitProjectionReady(f, ids[0])
                val generation = f.sessions.virtualQueue(20).generation
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                gate.barrier = {
                    reached.complete(Unit)
                    release.await()
                }
                val second = f.player.getMediaItemAt(1)
                f.player.removeMediaItem(1)
                f.player.addMediaItem(1, second)
                awaitAdapter("refresh never reached the input barrier") { reached.isCompleted }
                // A background writer has no player-thread own-write bracket.
                f.queue.addLast(ids.takeLast(1))
                awaitAdapter { f.player.ids() == ids && f.index[ids[2]] != null }
                assertEquals(generation, f.sessions.virtualQueue(20).generation)
                release.complete(Unit)
                var dropped = false
                var stable = 0
                var iter = 0
                while (iter++ < 2_000 && stable < 40) {
                    runCurrent()
                    shadowOf(Looper.getMainLooper()).idle()
                    if (f.player.ids() != ids || f.index[ids[2]] == null) dropped = true
                    stable = if (f.player.ids() == ids && f.index[ids[2]] != null) stable + 1 else 0
                    Thread.sleep(2)
                }
                assertFalse("an older refresh overwrote the newer collected emission", dropped)
                assertEquals(ids, f.player.ids())
                assertNotNull(f.index[ids[2]])
            } finally {
                f.close()
            }
        }

    private suspend fun TestScope.awaitProjectionReady(
        fixture: AdapterFixture,
        currentId: Long,
    ) {
        // Synchronous applyStart is not evidence that the Room collector has emitted.
        val title = "Projection ready"
        fixture.db.podcastDao().setCustomTitle(fixture.lookup.get(currentId)!!.podcastId, title)
        awaitAdapter("The Room projection never reached the player") {
            fixture.player.currentMediaItem
                ?.mediaMetadata
                ?.albumTitle
                ?.toString() == title
        }
    }
}
