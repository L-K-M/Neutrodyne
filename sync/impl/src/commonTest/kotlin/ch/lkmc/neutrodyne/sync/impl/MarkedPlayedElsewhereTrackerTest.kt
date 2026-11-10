// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakePlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.MarkedPlayedElsewhere
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.api.nextNowPlayingLoadId
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The persistent "Marked played on {device}" line (08 Sync notices): the tracker retains the
 * replay-0 notice for the loaded item only, so a player mounting later still shows it while an
 * unrelated or previous-item notice can never bleed onto another title.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkedPlayedElsewhereTrackerTest {
    private val state = FakePlaybackStateSource()
    private val bus = SyncNoticeBus()

    @Test
    fun theStartupGateDefersObservationButArmsThePostTimeStamper() =
        runTest {
            state.nowPlaying.value = now(7)
            val ready = CompletableDeferred<Unit>()
            val tracker = MarkedPlayedElsewhereTracker(state, bus, backgroundScope)
            tracker.start { ready.await() }
            runCurrent()
            assertEquals(
                0,
                state.nowPlaying.subscriptionCount.value,
                "no playback observation before the database gate",
            )
            bus.post(notice(7, "Laptop"))
            assertNull(tracker.markedPlayedElsewhere.value)
            ready.complete(Unit)
            runCurrent()
            tracker.assertLine(7, "Laptop")
            assertEquals(1, state.nowPlaying.subscriptionCount.value)
        }

    /** Each call mints a fresh [NowPlaying.loadId], like a real producer loading the item. */
    private fun now(
        id: Long,
        playing: Boolean = true,
    ) = NowPlaying(
        episodeId = id,
        podcastId = 2,
        title = "Episode $id",
        podcastTitle = "Podcast",
        artwork = ArtworkRef("art", null, 0),
        sourceType = SourceType.RSS,
        isVideo = false,
        phase = PlayerPhase.READY,
        isPlaying = playing,
        playWhenReady = playing,
        position = PositionSnapshot(id, 0, 0, 60_000, 1f, playing, 0),
        hasNext = false,
        stream = StreamKind.STREAM,
        issue = null,
        context = null,
        loadId = nextNowPlayingLoadId(),
    )

    private fun notice(
        id: Long,
        device: String = "Pixel 8",
    ) = SyncNotice.MarkedPlayedElsewhere(id, "Episode $id", device)

    private fun TestScope.tracker(): MarkedPlayedElsewhereTracker {
        val tracker = MarkedPlayedElsewhereTracker(state, bus, backgroundScope)
        tracker.start()
        return tracker
    }

    /** Field assertions keep the opaque minted [MarkedPlayedElsewhere.loadId] out of expecteds. */
    private fun MarkedPlayedElsewhereTracker.assertLine(
        id: Long,
        device: String,
    ) {
        val line = markedPlayedElsewhere.value
        assertEquals(id to device, line?.let { it.episodeId to it.deviceName })
    }

    @Test fun noticeBeforePlayerMountsIsRetainedForTheLoadedItem() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            // No idle wait: start() must arm the post-time stamper synchronously, or this
            // replay-0 notice would be lost forever before a scheduled collector existed.
            bus.post(notice(7, "Work PC"))
            runCurrent()
            tracker.assertLine(7, "Work PC")
        }

    @Test fun lineSurvivesPausePlayAndSameItemStateEmissions() =
        runTest {
            val item = now(7)
            state.nowPlaying.value = item
            val tracker = tracker()
            bus.post(notice(7))
            // The same loaded occurrence: pause/play and position updates keep the loadId.
            state.nowPlaying.value = item.copy(isPlaying = false, playWhenReady = false)
            state.nowPlaying.value = item.copy()
            runCurrent()
            tracker.assertLine(7, "Pixel 8")
        }

    @Test fun itemChangeClearsTheLine() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            bus.post(notice(7))
            state.nowPlaying.value = now(9)
            runCurrent()
            assertNull(tracker.markedPlayedElsewhere.value)
        }

    @Test fun unloadClearsTheLine() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            bus.post(notice(7))
            state.nowPlaying.value = null
            runCurrent()
            assertNull(tracker.markedPlayedElsewhere.value)
        }

    @Test fun noticeForAnotherItemIsRejected() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            bus.post(notice(9))
            runCurrent()
            assertNull(tracker.markedPlayedElsewhere.value)
        }

    @Test fun anUnrelatedNoticeLeavesTheCurrentItemsLineAlone() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            bus.post(notice(7, "Work PC"))
            runCurrent()
            tracker.assertLine(7, "Work PC")
            // A notice for another item is not a replacement: the retained line stays.
            bus.post(notice(9, "Laptop"))
            runCurrent()
            tracker.assertLine(7, "Work PC")
        }

    @Test fun noticeArrivingBeforeTheItemLoadsIsRejected() =
        runTest {
            val tracker = tracker()
            bus.post(notice(7))
            runCurrent()
            state.nowPlaying.value = now(7)
            runCurrent()
            assertNull(tracker.markedPlayedElsewhere.value)
        }

    @Test fun previousItemNoticeCannotBleedAfterTheSwitch() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            bus.post(notice(7))
            runCurrent()
            state.nowPlaying.value = now(9)
            runCurrent()
            bus.post(notice(7))
            runCurrent()
            assertNull(tracker.markedPlayedElsewhere.value)
        }

    @Test fun leavingAndReloadingDoesNotReintroduceTheLine() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            bus.post(notice(7))
            runCurrent()
            // No pump between: conflated StateFlow updates are what loadId exists for.
            state.nowPlaying.value = null
            state.nowPlaying.value = now(7)
            runCurrent()
            assertNull(tracker.markedPlayedElsewhere.value)
        }

    @Test fun rapidItemChangeWithoutAFreshNoticeClears() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            bus.post(notice(7))
            runCurrent()
            // A→B→A with no collector pass in between: episodeId alone could not see the
            // switch at all, but each load mints a different occurrence token.
            state.nowPlaying.value = now(9)
            state.nowPlaying.value = now(7)
            runCurrent()
            assertNull(tracker.markedPlayedElsewhere.value)
        }

    @Test fun aNoticeQueuedBehindAnItemSwitchCannotLatch() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            // The mark stamps the load current at post time; the switch then publishes a new
            // occurrence, so this notice can never appear under the new item.
            bus.post(notice(7))
            state.nowPlaying.value = now(9)
            runCurrent()
            assertNull(tracker.markedPlayedElsewhere.value)
        }

    /**
     * The real hub's merged `nowPlaying` lags a synchronous bridge commit by a dispatcher pass;
     * the stamper reads `currentNowPlaying`, so a notice for the just-committed item still lands.
     */
    @Test fun aNoticeForTheJustCommittedLiveItemIsStampedNotLost() =
        runTest {
            state.nowPlaying.value = now(7)
            val committed = now(9)
            val source =
                object : ch.lkmc.neutrodyne.playback.api.PlaybackStateSource by state {
                    override val currentNowPlaying get() = committed
                }
            val tracker = MarkedPlayedElsewhereTracker(source, bus, backgroundScope)
            tracker.start()
            // Posted in the lag window: the merged view still shows 7 while live already is 9.
            bus.post(notice(9, "Laptop"))
            state.nowPlaying.value = committed
            runCurrent()
            tracker.assertLine(9, "Laptop")
        }

    /** The inverse lag: the outgoing item's notice must not ride the stale merged view. */
    @Test fun aNoticeForTheOutgoingItemCannotRideTheStaleView() =
        runTest {
            state.nowPlaying.value = now(7)
            val source =
                object : ch.lkmc.neutrodyne.playback.api.PlaybackStateSource by state {
                    override val currentNowPlaying get() = now(9)
                }
            val tracker = MarkedPlayedElsewhereTracker(source, bus, backgroundScope)
            tracker.start()
            bus.post(notice(7))
            runCurrent()
            assertNull(tracker.markedPlayedElsewhere.value)
        }

    @Test fun aFreshNoticeReArmsTheReloadedItem() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            bus.post(notice(7))
            runCurrent()
            state.nowPlaying.value = null
            state.nowPlaying.value = now(7)
            runCurrent()
            bus.post(notice(7, "Laptop"))
            runCurrent()
            tracker.assertLine(7, "Laptop")
        }

    @Test fun theLatestMatchingNoticeWins() =
        runTest {
            state.nowPlaying.value = now(7)
            val tracker = tracker()
            bus.post(notice(7, "Pixel 8"))
            bus.post(notice(7, "Laptop"))
            runCurrent()
            tracker.assertLine(7, "Laptop")
        }
}
