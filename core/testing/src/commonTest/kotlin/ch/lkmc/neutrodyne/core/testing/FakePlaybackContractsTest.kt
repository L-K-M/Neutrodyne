// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakePlaybackContractsTest {
    @Test fun m5FakesRecordCommandsAndPreserveEmptyDefaultsAndSourceIsolation() =
        runTest {
            val controller = FakePlaybackController()
            assertEquals(SleepTimerState.Off, controller.state.sleepTimer.value)
            assertNull(controller.state.currentChapter.value)
            controller.setSleepTimer(SleepTimerMode.Minutes(30))
            controller.extendSleepTimer(10)
            controller.nextChapter()
            controller.previousChapter()
            assertEquals(
                listOf(
                    FakePlaybackController.SleepSet(SleepTimerMode.Minutes(30)),
                    FakePlaybackController.SleepExtend(10),
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.CHAPTER_NEXT),
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.CHAPTER_PREV),
                ),
                controller.calls,
            )
            assertEquals(SleepTimerState.Off, controller.state.sleepTimer.value)
            controller.state.events.test {
                controller.state.events.emit(PlaybackEvent.SleepTimerFired)
                assertEquals(PlaybackEvent.SleepTimerFired, awaitItem())
            }
            val repo = FakeChapterRepository()
            assertTrue(repo.observe(1).first().isEmpty())
            val psc =
                EpisodeChapter(
                    0,
                    null,
                    "PSC",
                    null,
                    null,
                    ChapterSource.PSC,
                    false,
                )
            repo.replace(1, psc.source, listOf(psc))
            repo.replace(1, psc.source, listOf(psc))
            val id3 = psc.copy(source = ChapterSource.ID3)
            repo.replace(1, id3.source, listOf(id3))
            assertEquals(2, repo.writes.size)
            assertEquals(listOf(psc), repo.observe(1).first())
            repo.ensureLoaded(1)
            assertEquals(listOf(1L), repo.loads)
            repo.failure = IllegalStateException("write failed")
            assertFailsWith<IllegalStateException> { repo.replace(1, psc.source, emptyList()) }
            assertEquals(listOf(psc), repo.observe(1).first())
            val positions = FakePositionStore()
            positions.setMeasuredDuration(1, 0)
            positions.setMeasuredDuration(1, 2000)
            positions.setMeasuredDuration(1, 3000)
            assertEquals(2000L, positions.measuredDurations[1])
            assertNull(positions.observe(1).first())
        }

    @Test fun recordingControllerPreservesArgumentsOrderAndPauseCompatibility() =
        runTest {
            val f = FakePlaybackController()
            assertEquals(PlayResult.ServiceUnavailable, f.play())
            f.playResult = PlayResult.NeedsMeteredConsent(7)
            assertEquals(f.playResult, f.playEpisode(7))
            f.playEpisodeAt(7, 9000)
            val filters = FeedFilters(inProgressOnly = true, minSortDate = 123)
            f.playFeed(FeedSource.Group(8), filters, FeedOrder.OLDEST_FIRST, 7)
            assertEquals(FakePlaybackController.Call.Episode(7, 9000), f.calls[2])
            assertEquals(
                FakePlaybackController.Call.Feed(FeedSource.Group(8), filters, FeedOrder.OLDEST_FIRST, 7),
                f.calls[3],
            )
            f.pause()
            f.togglePlayPause()
            f.seekTo(42)
            f.skipBack()
            f.skipForward()
            f.skipToNext()
            f.skipToPrevious()
            f.grantMeteredStreaming()
            f.dismiss()
            assertEquals(1, f.pauseCalls)
            assertEquals(FakePlaybackController.Call.Transport(FakePlaybackController.Action.DISMISS), f.calls.last())
            f.scopeResult = ScopeWriteResult.FAILED
            assertEquals(ScopeWriteResult.FAILED, f.setSpeed(1.5f, SettingScope.GROUP))
            f.setSkipSilence(true, SettingScope.PODCAST)
            val ids = mutableListOf(1L, 2L)
            f.playNext(ids)
            f.playLast(ids)
            ids.clear()
            assertEquals(listOf(1L, 2L), (f.calls.last() as FakePlaybackController.Call.Queue).ids)
            assertNull(f.state.nowPlaying.value)
        }

    @Test fun stateTicksAndEventsAreSettableWithoutEventReplay() =
        runTest {
            val f = FakePlaybackStateSource()
            f.events.emit(PlaybackEvent.StartRefused(PlayResult.Offline))
            f.events.test {
                expectNoEvents()
                f.events.emit(PlaybackEvent.MarkedPlayed(1))
                assertEquals(PlaybackEvent.MarkedPlayed(1), awaitItem())
            }
            f.positionTicks.test {
                val tick = PositionSnapshot(1, 10, 20, null, 1f, false, 0)
                f.positionTicks.emit(tick)
                assertEquals(tick, awaitItem())
            }
            assertNull(f.effectivePlayback.value)
            f.pendingMeteredConsent.value = PlayResult.NeedsMeteredConsent(7)
            assertEquals(7L, f.pendingMeteredConsent.value?.episodeId)
        }

    @Test fun queueAndContextFakesExposeConfiguredReadsAndRecordWrites() =
        runTest {
            val q = FakeQueueRepository()
            q.queue.value =
                VirtualQueue(
                    7,
                    null,
                    (1L..105L).map { QueueItem(it, QueueOrigin.UP_NEXT) },
                    listOf(QueueItem(200, QueueOrigin.CONTEXT)),
                )
            assertEquals(
                105,
                q
                    .observeVirtualQueue(0)
                    .first()
                    .upNext.size,
            )
            assertTrue(
                q
                    .observeVirtualQueue(0)
                    .first()
                    .contextTail
                    .isEmpty(),
            )
            q.addNext(listOf(1))
            q.addLast(listOf(2))
            q.move(1, 1)
            q.remove(listOf(2))
            q.clearUpNext()
            q.clearContext()
            assertEquals(FakeQueueRepository.Operation.entries, q.calls.map { it.operation })
            assertEquals(7L, q.queue.value.generation)
            assertNull(q.observeSession().first())
            assertTrue(q.observeUpNext().first().isEmpty())
            val c = FakePlayContextResolver()
            assertEquals(c.specResult, c.spec(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST))
            assertNull(c.startItem(c.specResult))
            c.startResult = 9
            assertEquals(9L, c.startItem(c.specResult))
            q.failure = IllegalStateException("full")
            assertFailsWith<IllegalStateException> { q.clearUpNext() }
        }

    @Test fun sessionFakeRecordsAtomicWriteInputsAndFailureDoesNotAdvanceGeneration() =
        runTest {
            val f = FakePlaySessionStore()
            val departure = Departure(1, 3000, null, PositionSource.STREAM, 5, false, true)
            val start = SessionStart(1, null, ContextRetention.REPLACE, null, null)
            assertEquals(1L, f.commitStart(start, 10))
            assertEquals(2L, f.onTransition(departure, 2, QueueOrigin.UP_NEXT, 11))
            assertEquals(departure, f.calls.last().departure)
            assertEquals(2L, f.calls.last().arrivalId)
            f.goBack(QueueItem(1, QueueOrigin.CONTEXT), departure, 12)
            f.endSession(departure, 13)
            f.clearCurrent(null, 14)
            assertEquals(FakePlaySessionStore.Operation.entries, f.calls.map { it.operation })
            f.failure = IllegalStateException("full")
            assertFailsWith<IllegalStateException> { f.commitStart(start, 15) }
            assertEquals(5L, f.generation)
            f.failure = null
            assertNull(f.virtualQueue(0).current)
            assertFailsWith<IllegalArgumentException> { f.virtualQueue(-1) }
        }

    @Test fun positionFakeHonorsSaveGuardsResetReplayAndFailure() =
        runTest {
            val f = FakePositionStore()
            assertTrue(f.save(1, 9000, 20000, PositionSource.DOWNLOAD, 10, 1) { true })
            assertFalse(f.save(1, 0, null, PositionSource.STREAM, 11, 1) { true })
            var checked = false
            assertFalse(
                f.save(1, 10000, null, PositionSource.STREAM, 12, 1) {
                    checked = true
                    false
                },
            )
            assertTrue(checked)
            assertEquals(9000L, f.observe(1).first()?.positionMs)
            f.markPlayed(1, 20)
            assertFalse(f.save(1, 1000, null, PositionSource.STREAM, 21, 20) { true })
            f.markUnplayedForReplay(1, 22)
            assertNull(f.observe(1).first()?.playedAt)
            f.reset(1, 24)
            assertEquals(0L, f.observe(1).first()?.positionMs)
            f.failure = IllegalStateException("full")
            assertFailsWith<IllegalStateException> { f.reset(1, 25) }
            assertEquals(24L, f.observe(1).first()?.updatedAt)
        }

    @Test fun positionFakeDoesNotInventRowsForStateOnlyWrites() =
        runTest {
            val f = FakePositionStore()
            f.reset(1, 10)
            assertNull(f.observe(1).first())
            f.markPlayed(1, 20)
            assertNull(f.observe(1).first())
            assertFalse(f.save(1, 900, null, PositionSource.STREAM, 21, 20) { true })
            f.markUnplayedForReplay(1, 22)
            assertNull(f.observe(1).first())
            assertTrue(f.save(1, 900, null, PositionSource.STREAM, 23, 20) { true })
            assertNull(f.observe(1).first()?.playedAt)
        }

    @Test fun mediaLookupAndStartGateRemainIndependentOfStorageAndPlayers() =
        runTest {
            val f = FakePlaybackMediaLookup()
            assertNull(f.get(1))
            val item =
                PlaybackMediaInfo(
                    1,
                    2,
                    "Episode",
                    "Podcast",
                    ArtworkRef("art", null, 0),
                    SourceType.RSS,
                    Availability.AVAILABLE,
                    false,
                    "https://example.com/a.mp3",
                    null,
                    null,
                    null,
                    null,
                    null,
                )
            f.items.value = mapOf(1L to item)
            assertEquals(item, f.get(1))
            assertTrue(f.observe(setOf(2)).first().isEmpty())
            val gate = FakeStartGate()
            assertNull(gate.check(item, "file:///a.mp3"))
            gate.refusal = PlayResult.MeteredBlocked
            assertEquals(gate.refusal, gate.check(item, null))
            assertEquals(item to null, gate.calls.last())
        }

    @Test fun localYoutubeAndMaintenanceFakesHaveTruthfulDefaults() =
        runTest {
            val local = FakeLocalMediaIndex()
            assertNull(local.localUriOrNull(1))
            local.files[1] = "file:///a.mp3"
            assertEquals(local.files[1], local.localUriOrNull(1))
            val caps = FakeYouTubeCapabilitiesSource().capabilities
            assertFalse(caps.value.inAppPlayback)
            assertEquals(ExternalReason.NOT_YET_AVAILABLE, caps.value.externalReason)
            val resolver = FakeYouTubeStreamResolver()
            assertEquals(ResolveResult.Unsupported, resolver.resolveAudio("id", AudioPref()))
            resolver.invalidate("id")
            resolver.invalidateAll()
            assertEquals(listOf("id"), resolver.invalidated)
            assertEquals(1, resolver.invalidateAllCalls)
            val cache = FakePlaybackMaintenance()
            cache.bytes = 123
            assertEquals(123L, cache.streamingCacheBytes())
            cache.clearStreamingCache()
            assertEquals(0L, cache.streamingCacheBytes())
            cache.failure = IllegalStateException("busy")
            assertFailsWith<IllegalStateException> { cache.clearStreamingCache() }
        }

    @Test fun interpolationUsesElapsedTimeSpeedAndKnownDuration() {
        val sample = PositionSnapshot(1, 1000, 2000, 3000, 1.5f, true, 100)
        assertEquals(2500L, sample.positionAt(1100))
        assertEquals(3000L, sample.positionAt(10100))
        assertEquals(1000L, sample.positionAt(0))
        assertEquals(1000L, sample.copy(advancing = false).positionAt(10100))
        assertEquals(16000L, sample.copy(durationMs = null).positionAt(10100))
    }
}
