// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeLocalMediaIndex
import ch.lkmc.neutrodyne.core.testing.FakePositionStore
import ch.lkmc.neutrodyne.core.testing.FakeQueueRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueWindowPlannerTest {
    private val repo = FakeQueueRepository()
    private val caps = FakeYouTubeCapabilitiesSource()
    private val positions = FakePositionStore()
    private val local = FakeLocalMediaIndex()
    private val clock = TestClock()
    private val planner =
        QueueWindowPlanner(repo, caps, positions, local, clock, WindowDiff(), StartPositionRule(), PlayedRule())

    @Test fun boundedWindowUsesFullStoredSizeToSuppressContext() {
        val media = (1L..300).associateWith(::media)
        for (count in listOf(99, 100, 101, 200)) {
            val snapshot = queue(upNext = (2L..count + 1).toList(), context = (250L..279).toList())
            val result = planner.window(snapshot, media)
            assertEquals(1 + minOf(count, 100) + if (count > 100) 0 else 20, result.size)
            assertEquals(1L, result.first().item.episodeId)
            assertEquals(count, snapshot.upNext.size)
        }
    }

    @Test fun unavailableAndExternalTailAreFilteredWhileCurrentAlwaysRemains() {
        val snapshot = queue(upNext = listOf(2, 3, 4), context = listOf(5))
        val info = (1L..5).associateWith(::media).toMutableMap()
        info[1] = media(1, SourceType.YOUTUBE_CHANNEL).copy(availability = Availability.UNAVAILABLE)
        info[2] = media(2).copy(availability = Availability.PRIVATE)
        info[3] = media(3, SourceType.YOUTUBE_CHANNEL)
        assertEquals(listOf(1L, 4, 5), planner.window(snapshot, info).map { it.item.episodeId })
        assertEquals(listOf(2L, 3, 4), snapshot.upNext.map { it.episodeId })
    }

    @Test fun playableItemsBeyondFirstHundredCanFillWindowButContextStaysSuppressed() {
        val info =
            (1L..150).associateWith(::media).mapValues { (id, value) ->
                if (id in 2..101) value.copy(availability = Availability.UNAVAILABLE) else value
            }
        val snapshot = queue(upNext = (2L..149).toList(), context = listOf(150))
        assertEquals(listOf(1L) + (102L..149), planner.window(snapshot, info).map { it.item.episodeId })
    }

    @Test fun nullCurrentClearsWindowAndMissingCurrentMetadataDoesNotFilterIt() {
        assertTrue(planner.window(queue(current = null), emptyMap()).isEmpty())
        assertEquals(listOf(1L), planner.window(queue(), emptyMap()).map { it.item.episodeId })
    }

    @Test fun snapshotObservationRequestsOnlyTwentyContextItems() =
        runTest {
            repo.queue.value = queue(context = (4L..80).toList())
            assertEquals(
                20,
                planner
                    .snapshots()
                    .first()
                    .contextTail.size,
            )
            assertEquals(listOf(20), repo.requestedWindows)
        }

    @Test fun ownWriteStashesLatestSnapshotAndIgnoresReadBeforeCommit() =
        runTest {
            val info = (1L..10).associateWith(::media)
            planner.appliedOwnGeneration(1)
            planner.beginOwnWrite()
            assertNull(planner.plan(queue(generation = 1), info, PlayerWindow()))
            assertNull(planner.plan(queue(generation = 2, upNext = listOf(7)), info, PlayerWindow()))
            assertEquals(listOf(7L), planner.endOwnWrite(2)!!.upNext.map { it.episodeId })
            assertNull(planner.plan(queue(generation = 1), info, PlayerWindow()))
        }

    @Test fun lowerGenerationStashedDuringTransitionCannotRevertCurrent() =
        runTest {
            planner.appliedOwnGeneration(1)
            planner.beginOwnWrite()
            assertNull(planner.plan(queue(generation = 1), emptyMap(), PlayerWindow()))
            assertNull(planner.endOwnWrite(2))
            val result =
                planner.plan(
                    queue(generation = 2, current = 2),
                    (1L..5).associateWith(::media),
                    PlayerWindow(listOf(entry(2))),
                )!!
            assertEquals(WindowPlayback.KEEP, result.playback)
            assertTrue(result.operations.none { it is WindowOp.SetAll })
        }

    @Test fun nestedWritesWaitForAllAndRollbackDoesNotInventGeneration() =
        runTest {
            planner.appliedOwnGeneration(3)
            planner.beginOwnWrite()
            planner.beginOwnWrite()
            val latest = queue(generation = 4)
            planner.plan(latest, emptyMap(), PlayerWindow())
            assertNull(planner.endOwnWrite(null))
            assertEquals(latest, planner.endOwnWrite(4))
            planner.beginOwnWrite()
            planner.plan(latest, emptyMap(), PlayerWindow())
            assertEquals(latest, planner.endOwnWrite(null))
        }

    @Test fun capabilityFlipRediffsSameGenerationWithoutRemovingCurrent() =
        runTest {
            val info =
                mapOf(
                    1L to media(1, SourceType.YOUTUBE_CHANNEL),
                    2L to media(2, SourceType.YOUTUBE_CHANNEL),
                    3L to media(3),
                )
            caps.capabilities.value = caps.capabilities.value.copy(inAppPlayback = true, externalReason = null)
            val snapshot = queue(context = emptyList())
            planner.appliedOwnGeneration(1)
            val before = planner.plan(snapshot, info, PlayerWindow())!!
            caps.capabilities.value = caps.capabilities.value.copy(inAppPlayback = false)
            val result = planner.plan(snapshot, info, PlayerWindow(before.entries))!!
            assertEquals(listOf(1L, 3), result.entries.map { it.item.episodeId })
            assertEquals(WindowPlayback.KEEP, result.playback)
            assertTrue(result.operations.none { it is WindowOp.SetAll })
        }

    @Test fun foreignAndInitialSessionsLoadPausedAtStoredPosition() =
        runTest {
            positions.positions.value = mapOf(2L to StoredPosition(2, 45_000, clock.now(), null))
            val result =
                planner.plan(
                    queue(generation = 9, current = 2),
                    (1L..5).associateWith(::media),
                    PlayerWindow(listOf(entry(1)), isPlaying = true),
                )!!
            assertEquals(WindowPlayback.LOAD_PAUSED, result.playback)
            assertEquals(45_000L, assertIs<WindowOp.SetAll>(result.operations.single()).startPositionMs)
            assertEquals(CurrentAction.KEEP, result.currentAction)
        }

    @Test fun restoredSameCurrentLoadsItsStoredPositionPaused() =
        runTest {
            planner.appliedOwnGeneration(1)
            positions.positions.value = mapOf(1L to StoredPosition(1, 60_000, clock.now(), null))
            val result =
                planner.plan(
                    queue(generation = 2),
                    (1L..5).associateWith(::media),
                    PlayerWindow(listOf(entry(1)), positionMs = 40_000),
                )!!
            assertEquals(WindowPlayback.LOAD_PAUSED, result.playback)
            assertEquals(60_000L, assertIs<WindowOp.SetAll>(result.operations.first()).startPositionMs)
            assertTrue(positions.calls.isEmpty())
        }

    @Test fun recentLocalMarkAdvancesOnceAndOldReplayMarkDoesNot() =
        runTest {
            planner.appliedOwnGeneration(1)
            val player = PlayerWindow(listOf(entry(1)), currentSinceMs = 100)
            val info = (1L..5).associateWith(::media).toMutableMap()
            info[1] = media(1).copy(playedAt = 99)
            assertEquals(CurrentAction.KEEP, planner.plan(queue(), info, player)!!.currentAction)
            info[1] = media(1).copy(playedAt = 100)
            assertEquals(CurrentAction.ADVANCE, planner.plan(queue(), info, player)!!.currentAction)
            assertEquals(CurrentAction.KEEP, planner.plan(queue(), info, player)!!.currentAction)
        }

    @Test fun markedLastItemEndsAndFreezeSuppressesAdvance() =
        runTest {
            planner.appliedOwnGeneration(1)
            val info = mapOf(1L to media(1).copy(playedAt = 100))
            val player = PlayerWindow(listOf(entry(1)), currentSinceMs = 99)
            assertEquals(
                CurrentAction.KEEP,
                planner
                    .plan(
                        queue(upNext = emptyList(), context = emptyList()),
                        info,
                        player,
                    ) {
                        true
                    }!!
                    .currentAction,
            )
            assertEquals(
                CurrentAction.END,
                planner.plan(queue(upNext = emptyList(), context = emptyList()), info, player)!!.currentAction,
            )
        }

    @Test fun newYoutubeItemsAndNewNextPrewarmButLocalItemsDoNot() =
        runTest {
            caps.capabilities.value = caps.capabilities.value.copy(inAppPlayback = true)
            planner.appliedOwnGeneration(1)
            val info = (1L..5).associateWith { media(it, SourceType.YOUTUBE_CHANNEL) }
            local.files[4] = "file:///four"
            val player = PlayerWindow(listOf(1L, 2, 3).map { WindowEntry(item(it), info[it]) })
            val result = planner.plan(queue(upNext = listOf(3, 2, 4), context = listOf(5)), info, player)!!
            assertEquals(setOf(3L, 5L), result.prewarmEpisodeIds)
            assertFalse(result.prewarmEpisodeIds.contains(4))
        }

    @Test fun ownWriteDuringSuspendedPositionReadInvalidatesOldPlan() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val delayed =
                object : PositionStore by positions {
                    override fun observe(episodeId: Long): Flow<StoredPosition?> =
                        flow {
                            release.await()
                            emit(null)
                        }
                }
            val planner =
                QueueWindowPlanner(repo, caps, delayed, local, clock, WindowDiff(), StartPositionRule(), PlayedRule())
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    planner.plan(queue(), (1L..5).associateWith(::media), PlayerWindow())
                }
            planner.beginOwnWrite()
            planner.endOwnWrite(2)
            release.complete(Unit)
            assertNull(pending.await())
        }

    @Test fun contextClearedElsewhereKeepsAlreadyPlayingCurrentRunning() =
        runTest {
            planner.appliedOwnGeneration(1)
            val result =
                planner.plan(
                    queue(generation = 2, context = emptyList()),
                    (1L..5).associateWith(::media),
                    PlayerWindow(listOf(entry(1)), isPlaying = true),
                )!!
            assertEquals(WindowPlayback.KEEP, result.playback)
            assertTrue(result.operations.none { it is WindowOp.SetAll })
        }
}
