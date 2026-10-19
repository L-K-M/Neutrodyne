// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
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
import ch.lkmc.neutrodyne.playback.core.StartOutcome
import kotlinx.coroutines.flow.first
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
                val items = f.projector.prepareExternalStart(StartOutcome.Committed(f.sessions.virtualQueue(20), 0))
                assertEquals(
                    ids[0],
                    f.sessions
                        .virtualQueue(20)
                        .current!!
                        .episodeId,
                )
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
                AdapterFixture(this) { real ->
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
                }
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
}
