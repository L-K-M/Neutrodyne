// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.domain.IngestionEvents
import ch.lkmc.neutrodyne.core.model.NewEpisodes
import ch.lkmc.neutrodyne.core.testing.FakeAutoDownloadPlanner
import ch.lkmc.neutrodyne.core.testing.FakeCleanupPlanner
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidDownloadTriggersTest {
    private val planner = FakeAutoDownloadPlanner()
    private val cleanup = FakeCleanupPlanner()
    private val resolver = FakeEffectiveSettingsResolver()
    private val refresh = FakeRefreshController()
    private val events = MutableSharedFlow<NewEpisodes>()
    private val ingestion =
        object : IngestionEvents {
            override val newEpisodes = events
        }
    private val host = AndroidDownloadTriggers(planner, cleanup, resolver, ingestion, refresh)

    private fun TestScope.start() {
        backgroundScope.launch { host.observe() }
        runCurrent()
    }

    @Test fun ingestionBatchesFiveSecondsAndIgnoresInitialFetches() =
        runTest {
            start()
            events.emit(NewEpisodes(9, listOf(9), true))
            events.emit(NewEpisodes(1, listOf(1), false))
            advanceTimeBy(2_001)
            runCurrent()
            planner.calls.clear() // The first settings emission has its own two-second pass.
            events.emit(NewEpisodes(1, listOf(2), false))
            events.emit(NewEpisodes(2, listOf(3), false))
            advanceTimeBy(2_998)
            runCurrent()
            assertTrue(planner.calls.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(setOf(1L, 2L)), planner.calls.map { it.toSet() })
        }

    @Test fun continuousRefreshEventsCannotPostponeFirstBatch() =
        runTest {
            start()
            advanceTimeBy(2_001)
            runCurrent()
            planner.calls.clear()
            repeat(5) { id ->
                events.emit(NewEpisodes(id.toLong(), listOf(id.toLong()), false))
                advanceTimeBy(1_000)
                runCurrent()
            }
            assertEquals(listOf((0L..4L).toSet()), planner.calls.map { it.toSet() })
        }

    @Test fun eventsDuringSuspendedPlanAreBufferedForNextBatch() =
        runTest {
            start()
            advanceTimeBy(2_001)
            runCurrent()
            planner.calls.clear()
            val gate = CompletableDeferred<Unit>()
            planner.onPlan = { gate.await() }
            events.emit(NewEpisodes(1, listOf(1), false))
            advanceTimeBy(5_000)
            runCurrent()
            events.emit(NewEpisodes(2, listOf(2), false))
            runCurrent()
            assertEquals(listOf(listOf(1L)), planner.calls)
            gate.complete(Unit)
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(listOf(1L), listOf(2L)), planner.calls)
        }

    @Test fun policyChangesDebounceAndPlanOnlyChangedEntries() =
        runTest {
            resolver.autoDownloadValues.value = mapOf(1L to autoPolicy(), 2L to autoPolicy())
            start()
            advanceTimeBy(1_999)
            runCurrent()
            assertTrue(planner.calls.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(setOf(1L, 2L)), planner.calls.map { it.toSet() })
            resolver.autoDownloadValues.value = mapOf(1L to autoPolicy().copy(keepLatest = 4), 2L to autoPolicy())
            runCurrent()
            advanceTimeBy(1_000)
            resolver.autoDownloadValues.value = mapOf(1L to autoPolicy().copy(enabled = false), 2L to autoPolicy())
            runCurrent()
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(1, planner.calls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(1L), planner.calls.last())
            assertEquals(listOf(CleanupReason.POLICY_CHANGED, CleanupReason.POLICY_CHANGED), cleanup.calls)
        }

    @Test fun removedPodcastIsIncludedInChangedSetAndEmptyMapStillWatermarks() =
        runTest {
            resolver.autoDownloadValues.value = mapOf(1L to autoPolicy())
            start()
            advanceTimeBy(2_000)
            runCurrent()
            resolver.autoDownloadValues.value = emptyMap()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(listOf(listOf(1L), listOf(1L)), planner.calls)
        }

    @Test fun refreshIdleIsTransitionOnlyAndReplansAllEnabledPodcasts() =
        runTest {
            resolver.autoDownloadValues.value = mapOf(1L to autoPolicy(), 2L to autoPolicy().copy(enabled = false))
            start()
            assertTrue(planner.calls.isEmpty())
            refresh.status.value = refresh.status.value.copy(running = true)
            runCurrent()
            refresh.status.value = refresh.status.value.copy(done = 3)
            runCurrent()
            refresh.status.value = refresh.status.value.copy(running = false)
            runCurrent()
            assertEquals(listOf(listOf(1L)), planner.calls)
            refresh.status.value = refresh.status.value.copy(done = 4)
            runCurrent()
            assertEquals(1, planner.calls.size)
        }

    @Test fun failedTriggerDoesNotKillLaterRefreshOrPolicyEvents() =
        runTest {
            planner.onPlan = { error("database busy") }
            start()
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(cleanup.calls.isEmpty())
            planner.onPlan = {}
            resolver.autoDownloadValues.value = mapOf(1L to autoPolicy())
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(listOf(1L), planner.calls.last())
            assertEquals(listOf(CleanupReason.POLICY_CHANGED), cleanup.calls)
            refresh.status.value = refresh.status.value.copy(running = true)
            runCurrent()
            refresh.status.value = refresh.status.value.copy(running = false)
            runCurrent()
            assertEquals(listOf(1L), planner.calls.last())
        }

    @Test fun cancellationReleasesCollectorsAndDiscardsUnflushedEvents() =
        runTest {
            val job = backgroundScope.launch { host.observe() }
            runCurrent()
            events.emit(NewEpisodes(1, listOf(1), false))
            job.cancel(CancellationException("process stopped"))
            job.join()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(planner.calls.isEmpty())
            assertEquals(0, events.subscriptionCount.value)
        }
}
