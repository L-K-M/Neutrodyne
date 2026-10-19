// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.domain.ContextEligibility
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PlayContextResolver
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.domain.StartGate
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeLocalMediaIndex
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlayContextResolver
import ch.lkmc.neutrodyne.core.testing.FakePlaySessionStore
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakePositionStore
import ch.lkmc.neutrodyne.core.testing.FakeQueueRepository
import ch.lkmc.neutrodyne.core.testing.FakeStartGate
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayStarterTest {
    private val contexts = FakePlayContextResolver()
    private val queues = FakeQueueRepository()
    private val sessions = FakePlaySessionStore()
    private val lookup = FakePlaybackMediaLookup()
    private val positions = FakePositionStore()
    private val caps = FakeYouTubeCapabilitiesSource()
    private val local = FakeLocalMediaIndex()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val clock = TestClock()
    private val gate = FakeStartGate()
    private var publishCommit = true
    private val writer =
        object : PlaySessionStore by sessions {
            override suspend fun onTransition(
                departure: Departure?,
                arrivedEpisodeId: Long,
                arrivedOrigin: QueueOrigin,
                now: Long,
            ): Long {
                val generation = sessions.onTransition(departure, arrivedEpisodeId, arrivedOrigin, now)
                if (publishCommit) {
                    sessions.queue =
                        sessions.queue.copy(
                            generation = generation,
                            current = item(arrivedEpisodeId),
                        )
                }
                return generation
            }

            override suspend fun commitStart(
                start: SessionStart,
                now: Long,
            ): Long {
                val generation = sessions.commitStart(start, now)
                val episodeId = start.currentEpisodeId
                // W1 fake reads are caller-published: emulate only the committed fixture, not SQL.
                if (publishCommit) {
                    sessions.queue =
                        sessions.queue.copy(
                            generation = generation,
                            current = item(episodeId),
                            upNext = sessions.queue.upNext.filterNot { it.episodeId == episodeId },
                            contextTail = sessions.queue.contextTail.filterNot { it.episodeId == episodeId },
                        )
                }
                return generation
            }
        }

    private fun starter(
        resolver: PlayContextResolver = contexts,
        gates: Set<StartGate> = setOf(gate),
        store: PlaySessionStore = writer,
    ): PlayStarter {
        queues.queue.value = sessions.queue
        return PlayStarter(
            resolver,
            queues,
            store,
            lookup,
            positions,
            caps,
            local,
            network,
            clock,
            StartPositionRule(),
            PlayedRule(),
            gates,
        )
    }

    private val feed = StartRequest.Feed(FeedSource.Group(7), FeedFilters(), FeedOrder.NEWEST_FIRST)

    private suspend fun committed(request: StartRequest) = assertIs<StartOutcome.Committed>(starter().start(request))

    private suspend fun alreadyCurrent(request: StartRequest) =
        assertIs<StartOutcome.AlreadyCurrent>(starter().start(request, loadedEpisodeId = 1))

    private suspend fun refusalReason(request: StartRequest): UnplayableReason {
        val result = assertIs<StartOutcome.Refused>(starter().start(request)).result
        return assertIs<PlayResult.NotPlayable>(result).reason
    }

    init {
        sessions.queue = queue(current = null)
        lookup.items.value = (1L..8).associateWith(::media)
        contexts.specResult = contexts.specResult.copy(type = ContextType.GROUP, contextId = 7)
        contexts.startResult = 4
    }

    @Test fun groupStartSelectsFirstQueuedAndKeepsAnchorNullAndRemainingOrder() =
        runTest {
            val result = committed(feed)
            assertEquals(2L, result.queue.current!!.episodeId)
            assertEquals(listOf(3L), result.queue.upNext.map { it.episodeId })
            assertEquals(listOf(4L, 5), result.queue.contextTail.map { it.episodeId })
            val start = sessions.calls.single().start!!
            assertEquals(contexts.specResult, start.context)
            assertEquals(ContextRetention.REPLACE, start.contextRetention)
            assertNull(start.anchorEpisodeId)
            assertNull(start.anchorSortDate)
            assertTrue(contexts.startCalls.isEmpty())
            assertEquals(listOf(20), queues.requestedWindows)
            assertEquals(listOf(20), sessions.requestedWindows)
            assertEquals(result.queue.generation, sessions.generation)
        }

    @Test fun automaticStartSkipsUnavailableAndExternalRowsWithoutDeletingThem() =
        runTest {
            lookup.items.value = lookup.items.value +
                mapOf(
                    2L to media(2, SourceType.YOUTUBE_CHANNEL),
                    3L to media(3).copy(availability = Availability.UNAVAILABLE),
                )
            sessions.queue = queue(current = null, upNext = listOf(2, 3, 6))
            val result = committed(feed)
            assertEquals(6L, result.queue.current!!.episodeId)
            assertEquals(listOf(2L, 3), result.queue.upNext.map { it.episodeId })
            assertNull(
                sessions.calls
                    .single()
                    .start!!
                    .anchorEpisodeId,
            )
        }

    @Test fun playableEntryBeyondFirstHundredCanStart() =
        runTest {
            sessions.queue = queue(current = null, upNext = (10L..120).toList())
            lookup.items.value =
                (10L..120).associateWith { id ->
                    val availability = if (id == 120L) Availability.AVAILABLE else Availability.UNAVAILABLE
                    media(id).copy(availability = availability)
                }
            assertEquals(120L, committed(feed).queue.current!!.episodeId)
            assertEquals(110, sessions.queue.upNext.size)
        }

    @Test fun explicitFeedRowWinsOverQueueAndCarriesItsKeysetAnchor() =
        runTest {
            val result = committed(feed.copy(startEpisodeId = 5))
            assertEquals(5L, result.queue.current!!.episodeId)
            assertEquals(listOf(2L, 3), result.queue.upNext.map { it.episodeId })
            assertEquals(
                5L,
                sessions.calls
                    .single()
                    .start!!
                    .anchorEpisodeId,
            )
            assertEquals(
                1005L,
                sessions.calls
                    .single()
                    .start!!
                    .anchorSortDate,
            )
        }

    @Test fun noPlayableQueueFallsBackToContextWithAnchor() =
        runTest {
            sessions.queue = queue(current = null, upNext = listOf(2))
            lookup.items.value += 2L to media(2).copy(availability = Availability.UNAVAILABLE)
            assertEquals(4L, committed(feed).queue.current!!.episodeId)
            assertEquals(
                4L,
                sessions.calls
                    .single()
                    .start!!
                    .anchorEpisodeId,
            )
            assertEquals(listOf(ContextEligibility.PLAYABLE), contexts.eligibilityCalls)
        }

    @Test fun episodeInQueueKeepsContextAndUnqueuedEpisodeReplacesIt() =
        runTest {
            starter().start(StartRequest.Episode(3))
            assertEquals(
                ContextRetention.KEEP,
                sessions.calls
                    .last()
                    .start!!
                    .contextRetention,
            )
            starter().start(StartRequest.Episode(8))
            assertEquals(
                ContextRetention.REPLACE,
                sessions.calls
                    .last()
                    .start!!
                    .contextRetention,
            )
            assertNull(
                sessions.calls
                    .last()
                    .start!!
                    .context,
            )
        }

    @Test fun currentEpisodeDoesNotCommitOrApplyImplicitStartSeek() =
        runTest {
            sessions.queue = queue()
            positions.positions.value = mapOf(1L to StoredPosition(1, 10_000, clock.now(), null))
            assertNull(alreadyCurrent(StartRequest.Episode(1)).seekPositionMs)
            assertEquals(42L, alreadyCurrent(StartRequest.Episode(1, 42)).seekPositionMs)
            assertTrue(sessions.calls.isEmpty())
        }

    @Test fun resumeUsesCurrentThenPlayableQueueThenContext() =
        runTest {
            sessions.queue = queue()
            alreadyCurrent(StartRequest.Resume)
            sessions.queue = queue(current = null)
            assertEquals(2L, committed(StartRequest.Resume).queue.current!!.episodeId)
            sessions.queue = queue(current = null, upNext = emptyList())
            assertEquals(4L, committed(StartRequest.Resume).queue.current!!.episodeId)
            assertEquals(QueueOrigin.CONTEXT, sessions.calls.last().origin)
        }

    @Test fun nothingToPlayDistinguishesExternalOnlyContextAndQueue() =
        runTest {
            sessions.queue = queue(current = null, upNext = emptyList(), context = emptyList())
            contexts.startResult = null
            assertEquals(StartOutcome.Refused(PlayResult.NothingToPlay()), starter().start(feed))
            val resolver =
                object : PlayContextResolver by contexts {
                    override suspend fun startItem(
                        spec: PlayContextSpec,
                        eligibility: ContextEligibility,
                    ): Long? = if (eligibility == ContextEligibility.INCLUDE_EXTERNAL_YOUTUBE) 8 else null
                }
            assertEquals(StartOutcome.Refused(PlayResult.NothingToPlay(true)), starter(resolver).start(feed))
            sessions.queue = queue(current = null, upNext = listOf(2), context = emptyList())
            lookup.items.value = mapOf(2L to media(2, SourceType.YOUTUBE_CHANNEL))
            assertEquals(StartOutcome.Refused(PlayResult.NothingToPlay(true)), starter().start(feed))
            assertTrue(sessions.calls.isEmpty())
        }

    @Test fun allExternalReasonsRefuseExplicitRowsEvenWithLocalFiles() =
        runTest {
            for (reason in ExternalReason.entries) {
                caps.capabilities.value = caps.capabilities.value.copy(externalReason = reason)
                lookup.items.value = mapOf(2L to media(2, SourceType.YOUTUBE_CHANNEL))
                local.files[2] = "file:///two"
                val result = assertIs<StartOutcome.Refused>(starter().start(StartRequest.Episode(2)))
                val refusal = assertIs<PlayResult.NotPlayable>(result.result)
                assertEquals(UnplayableReason.YouTubeExternal(reason), refusal.reason)
            }
            assertTrue(gate.calls.isEmpty())
            assertTrue(sessions.calls.isEmpty())
        }

    @Test fun noMediaAvailabilityAndOfflineGatesPrecedePlatformGate() =
        runTest {
            val request = StartRequest.Episode(8)
            val missing = media(8).copy(enclosureUrl = null, availability = Availability.UNAVAILABLE)
            lookup.items.value = mapOf(8L to missing)
            assertEquals(
                UnplayableReason.NoMedia,
                refusalReason(request),
            )
            lookup.items.value = mapOf(8L to media(8).copy(availability = Availability.PRIVATE))
            assertEquals(
                UnplayableReason.YouTube(Availability.PRIVATE),
                refusalReason(request),
            )
            lookup.items.value = mapOf(8L to media(8))
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            assertEquals(StartOutcome.Refused(PlayResult.Offline), starter().start(request))
            assertTrue(gate.calls.isEmpty())
            assertTrue(sessions.calls.isEmpty())
        }

    @Test fun localMediaBypassesOfflineButStillRunsPlatformGate() =
        runTest {
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            local.files[8] = "file:///eight"
            gate.refusal = PlayResult.MeteredBlocked
            assertEquals(StartOutcome.Refused(gate.refusal!!), starter().start(StartRequest.Episode(8)))
            assertEquals("file:///eight", gate.calls.single().second)
            assertTrue(sessions.calls.isEmpty())
        }

    @Test fun startPositionUsesSavedProgressAndExplicitPositionOverridesRules() =
        runTest {
            positions.positions.value = mapOf(8L to StoredPosition(8, 70_000, clock.now(), null))
            assertEquals(70_000L, committed(StartRequest.Episode(8)).startPositionMs)
            sessions.queue = queue(current = null)
            lookup.items.value = lookup.items.value + (8L to media(8).copy(playedAt = 1))
            assertEquals(0L, committed(StartRequest.Episode(8)).startPositionMs)
            sessions.queue = queue(current = null)
            assertEquals(500L, committed(StartRequest.Episode(8, 500)).startPositionMs)
        }

    @Test fun smartResumeSettingReachesColdStartsAndExplicitPositionsWin() =
        runTest {
            positions.positions.value = mapOf(8L to StoredPosition(8, 70_000, clock.now() - 300_001, null))
            val enabled = starter().start(StartRequest.Episode(8), smartResume = true)
            assertEquals(65_000, assertIs<StartOutcome.Committed>(enabled).startPositionMs)
            val disabled = starter().start(StartRequest.Episode(8), smartResume = false)
            assertEquals(70_000, assertIs<StartOutcome.Committed>(disabled).startPositionMs)
            val explicit = starter().start(StartRequest.Episode(8, 754_000), smartResume = true)
            assertEquals(754_000, assertIs<StartOutcome.Committed>(explicit).startPositionMs)
            sessions.queue = queue(current = 8)
            val resumed = starter().start(StartRequest.Resume, smartResume = true)
            assertEquals(65_000, assertIs<StartOutcome.Committed>(resumed).startPositionMs)
        }

    @Test fun failuresMapToServiceUnavailableAndCancellationPropagates() =
        runTest {
            lookup.failure = IllegalStateException("read failure")
            assertEquals(StartOutcome.Refused(PlayResult.ServiceUnavailable), starter().start(feed))
            lookup.failure = null
            val brokenGate = StartGate { _, _ -> error("gate failure") }
            val failedGate = starter(gates = setOf(brokenGate)).start(feed)
            assertEquals(StartOutcome.Refused(PlayResult.ServiceUnavailable), failedGate)
            val cancelledGate = StartGate { _, _ -> throw CancellationException("cancelled") }
            assertFailsWith<CancellationException> { starter(gates = setOf(cancelledGate)).start(feed) }
            assertTrue(sessions.calls.isEmpty())
        }

    @Test fun commitFailureCannotProduceCommittedAndStalePostCommitReadCannotAutoplay() =
        runTest {
            val brokenWriter =
                object : PlaySessionStore by writer {
                    override suspend fun commitStart(
                        start: SessionStart,
                        now: Long,
                    ): Long = error("SQLITE_FULL")
                }
            val broken = starter(store = brokenWriter)
            assertIs<StartOutcome.Refused>(broken.start(feed))
            assertNull(sessions.queue.current)
            publishCommit = false
            assertIs<StartOutcome.Refused>(starter().start(feed))
            assertEquals(1, sessions.calls.size)
        }

    @Test fun unavailableExternalRowsDoNotClaimExternalOnlyEmptiness() =
        runTest {
            sessions.queue = queue(current = null, upNext = listOf(2), context = emptyList())
            val unavailable = media(2, SourceType.YOUTUBE_CHANNEL).copy(availability = Availability.UNAVAILABLE)
            lookup.items.value = mapOf(2L to unavailable)
            contexts.startResult = null
            assertEquals(StartOutcome.Refused(PlayResult.NothingToPlay()), starter().start(feed))
        }

    @Test fun coldResumptionLoadsStoredCurrentWithoutCommittingAgain() =
        runTest {
            sessions.queue = queue()
            positions.positions.value = mapOf(1L to StoredPosition(1, 60_000, clock.now(), null))
            val result = committed(StartRequest.Resume)
            assertEquals(60_000L, result.startPositionMs)
            assertEquals(1L, result.queue.current!!.episodeId)
            assertTrue(sessions.calls.isEmpty())
        }

    @Test fun foreignSessionWhileColdStartGatesWaitCannotBeAutoplayed() =
        runTest {
            sessions.queue = queue()
            val restoring =
                StartGate { _, _ ->
                    sessions.queue = queue(generation = 9, current = 8)
                    null
                }
            assertEquals(
                StartOutcome.Refused(PlayResult.ServiceUnavailable),
                starter(gates = setOf(restoring)).start(StartRequest.Resume),
            )
            assertTrue(sessions.calls.isEmpty())
        }

    @Test fun capabilityFlipWhilePlatformGateWaitsRefusesBeforeCommit() =
        runTest {
            caps.capabilities.value = caps.capabilities.value.copy(inAppPlayback = true, externalReason = null)
            lookup.items.value = mapOf(2L to media(2, SourceType.YOUTUBE_CHANNEL))
            val flipping =
                StartGate { _, _ ->
                    caps.capabilities.value =
                        caps.capabilities.value.copy(
                            inAppPlayback = false,
                            externalReason = ExternalReason.DISABLED_BY_USER,
                        )
                    null
                }
            val result = starter(gates = setOf(flipping)).start(StartRequest.Episode(2))
            assertEquals(
                StartOutcome.Refused(
                    PlayResult.NotPlayable(
                        2,
                        UnplayableReason.YouTubeExternal(ExternalReason.DISABLED_BY_USER),
                    ),
                ),
                result,
            )
            assertTrue(sessions.calls.isEmpty())
        }

    @Test fun acceptedStartCapturesOutgoingAfterGatesForTheAtomicStartTransaction() =
        runTest {
            sessions.queue = queue()
            val saver = PositionSaver(positions, clock, PlayedRule())
            saver.load(1, clock.now(), PositionSource.STREAM)
            saver.startPositionApplied()
            saver.onPlaying()
            var position = 100_000L
            val waiting =
                StartGate { _, _ ->
                    position = 1_199_000
                    null
                }
            val result =
                starter(gates = setOf(waiting)).start(
                    StartRequest.Episode(2),
                    loadedEpisodeId = 1,
                    captureDeparture = { saver.capture(position, 1_200_000, PlaybackExit.NEW_START)?.departure },
                )
            assertIs<StartOutcome.Committed>(result)
            val departure =
                sessions.calls
                    .single()
                    .start!!
                    .departure!!
            assertEquals(1L, departure.episodeId)
            assertEquals(1_199_000L, departure.positionMs)
            assertTrue(departure.markPlayed)
            assertTrue(departure.writePosition)
            assertTrue(positions.calls.isEmpty())
        }

    @Test fun refusedStartNeverCapturesOrWritesDeparture() =
        runTest {
            var captured = false
            gate.refusal = PlayResult.MeteredBlocked
            starter().start(StartRequest.Episode(2), captureDeparture = {
                captured = true
                null
            })
            assertTrue(!captured)
            assertTrue(sessions.calls.isEmpty())
            assertTrue(positions.calls.isEmpty())
        }
}
