// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.media3.common.Player
import ch.lkmc.neutrodyne.core.model.PositionSource
import kotlinx.coroutines.Job
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PositionTrackerTest {
    @Test fun existingFiveSecondTickCapturesOnlyPlayingCurrentAndStopsAtDetach() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                f.attach()
                f.start(listOf(id), 81_000)
                awaitAdapter { f.player.episodeIdOrNull() == id }
                f.player.advancing = false
                runCurrent()
                advanceTimeBy(5_000)
                runCurrent()
                assertTrue(f.ticks.isEmpty())
                f.player.advancing = true
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(id, f.ticks.single().episodeId)
                assertEquals(f.player.currentPosition, f.ticks.single().positionMs)
                f.tracker.detach(null)
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(1, f.ticks.size)
            } finally {
                f.close()
            }
        }

    @Test fun outgoingDiscontinuityUsesOldPositionBeforeNextListener() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val ids = f.seed(2)
                f.attach()
                f.start(ids, 81_000)
                awaitAdapter { f.player.ids() == ids }
                var observed: Long? = null
                f.player.addListener(
                    object : Player.Listener {
                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int,
                        ) {
                            if (oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) {
                                // Projector already consumed the outgoing capture and loaded the arrival.
                                observed = f.tracker.currentSinceMs
                            }
                        }
                    },
                )
                f.player.seekToNextMediaItem()
                awaitAdapter { f.player.ids() == ids.drop(1) }
                assertNotNull(observed)
                assertEquals(
                    81_000L,
                    f.db
                        .positionDao()
                        .byEpisode(ids[0])!!
                        .positionMs,
                )
                assertEquals(
                    ids[1],
                    f.sessions
                        .virtualQueue(20)
                        .current!!
                        .episodeId,
                )
                assertNull(
                    f.db
                        .episodeStateDao()
                        .byEpisode(ids[0])
                        ?.playedAt,
                )
            } finally {
                f.close()
            }
        }

    @Test fun durableStartSeekCannotSaveTinyPosition() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                f.positions.save(id, 80_000, null, PositionSource.STREAM, f.clock.now(), 0) { true }
                f.tracker.attach(f.player)
                f.player.setMediaItems(listOf(f.factory.build(f.lookup.get(id)!!)), 0, 0)
                f.tracker.load(id, false)
                f.player.seekTo(100)
                f.tracker.flush(PositionFlushReason.PAUSE)
                runCurrent()
                assertEquals(
                    80_000L,
                    f.db
                        .positionDao()
                        .byEpisode(id)!!
                        .positionMs,
                )
                f.tracker.coreSeek { f.player.seekTo(80_000) }
                f.tracker.startPositionApplied()
                f.tracker.flush(PositionFlushReason.PAUSE)
                runCurrent()
                assertEquals(
                    80_000L,
                    f.db
                        .positionDao()
                        .byEpisode(id)!!
                        .positionMs,
                )
            } finally {
                f.close()
            }
        }

    @Test fun userSeekResetsBelowOneSecondAndStartsOnlyOnce() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                f.attach()
                f.start(listOf(id), 50_000)
                awaitAdapter { f.player.episodeIdOrNull() == id }
                f.tracker.flush(PositionFlushReason.PAUSE)
                runCurrent()
                f.awaitSaved(id, 50_000)
                val started =
                    f.db
                        .episodeStateDao()
                        .byEpisode(id)!!
                        .startedAt
                f.fakeClock.advanceTime(10_000)
                f.player.seekTo(70_000)
                runCurrent()
                f.awaitSaved(id, 70_000)
                assertEquals(
                    started,
                    f.db
                        .episodeStateDao()
                        .byEpisode(id)!!
                        .startedAt,
                )
                f.player.seekTo(999)
                runCurrent()
                f.awaitSaved(id, 0)
                assertEquals(
                    0L,
                    f.db
                        .positionDao()
                        .byEpisode(id)!!
                        .positionMs,
                )
                assertNull(
                    f.db
                        .episodeStateDao()
                        .byEpisode(id)!!
                        .startedAt,
                )
            } finally {
                f.close()
            }
        }

    @Test fun teardownSnapshotSurvivesCancelledServiceAndReleasedPlayer() =
        runTest {
            val f = AdapterFixture(this)
            val id = f.seed(1).single()
            f.attach()
            f.start(listOf(id), 97_000)
            awaitAdapter { f.player.episodeIdOrNull() == id }
            val snapshot = f.tracker.flush(PositionFlushReason.DESTROY)
            f.projector.detach()
            val saved = f.tracker.detach(snapshot)
            val settingsStopped = f.applier.detach()
            f.exo.release()
            f.serviceJob.cancel()
            settingsStopped.join()
            saved?.join()
            runCurrent()
            assertEquals(
                97_000L,
                f.db
                    .positionDao()
                    .byEpisode(id)!!
                    .positionMs,
            )
            assertTrue(f.failures.toString(), f.failures.isEmpty())
            assertNull(f.settings.effectivePlayback.value)
            f.db.close()
        }

    @Test fun pauseFlushKeepsZeroGuardAndPlayedAfterStartGuard() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                f.attach()
                f.start(listOf(id), 40_000)
                awaitAdapter { f.player.episodeIdOrNull() == id }
                f.tracker.flush(PositionFlushReason.PAUSE)
                runCurrent()
                f.awaitSaved(id, 40_000)
                f.tracker.coreSeek { f.player.seekTo(0) }
                f.tracker.flush(PositionFlushReason.PAUSE)
                runCurrent()
                assertEquals(
                    40_000L,
                    f.db
                        .positionDao()
                        .byEpisode(id)!!
                        .positionMs,
                )
                f.positions.markPlayed(id, f.clock.now())
                f.tracker.coreSeek { f.player.seekTo(33_000) }
                f.tracker.flush(PositionFlushReason.PAUSE)
                runCurrent()
                assertEquals(
                    0L,
                    f.db
                        .positionDao()
                        .byEpisode(id)!!
                        .positionMs,
                )
            } finally {
                f.close()
            }
        }

    @Test fun tickSavesOnlyAfterFiveSecondsWithStreamSource() =
        runTest {
            val f = AdapterFixture(this)
            try {
                val id = f.seed(1).single()
                f.attach()
                f.start(listOf(id), 45_000)
                awaitAdapter { f.player.episodeIdOrNull() == id }
                f.player.advancing = true
                f.tracker.onIsPlayingChanged(true)
                runCurrent()
                f.fakeClock.advanceTime(4_999)
                advanceTimeBy(4_999)
                runCurrent()
                assertNull(f.db.positionDao().byEpisode(id))
                f.fakeClock.advanceTime(1)
                advanceTimeBy(1)
                runCurrent()
                f.awaitSaved(id, 45_000)
                assertEquals(
                    45_000L,
                    f.db
                        .positionDao()
                        .byEpisode(id)!!
                        .positionMs,
                )
                assertEquals(
                    PositionSource.STREAM,
                    f.db
                        .positionDao()
                        .byEpisode(id)!!
                        .positionSource,
                )
                f.player.advancing = false
            } finally {
                f.close()
            }
        }
}
