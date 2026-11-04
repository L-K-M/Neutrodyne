// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.FeedGate
import ch.lkmc.neutrodyne.youtube.api.FeedOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys as Keys

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeHealthTest {
    @Test fun outageBackoffPersistsAcrossRestartAndRecoveryResets() =
        runTest {
            val f = fixture()
            for ((index, hours) in listOf(1, 2, 4, 6, 6).withIndex()) {
                f.health.reportFeedOutage(f.clock.now())
                f.health.awaitLoaded()
                assertEquals(minOf(index + 1, 4), f.health.state.value.feedOutageLevel)
                assertEquals(f.clock.now() + hours * HOUR, f.health.state.value.feedOutageUntil)
            }
            val restarted = DefaultYouTubeHealth(f.settings, f.clock, backgroundScope) {}
            restarted.awaitLoaded()
            assertEquals(f.health.state.value, restarted.state.value)
            restarted.reportFeedRecovered()
            restarted.awaitLoaded()
            assertEquals(0, restarted.state.value.feedOutageLevel)
            assertNull(restarted.state.value.feedOutageUntil)
            assertEquals(0, f.settings.get(Keys.FEED_OUTAGE_LEVEL))
            assertEquals(ExtractionGate.Allow, restarted.extractionGate(f.clock.now()))
        }

    @Test fun rateLimitDoublesHonoursRetryAfterAndResetsOnSuccess() =
        runTest {
            val f = fixture()
            for (minutes in listOf(30, 60, 120, 240, 360, 360)) {
                f.health.reportFeedRateLimited(f.clock.now(), null)
                f.health.awaitLoaded()
                assertEquals(f.clock.now() + minutes * 60_000L, f.health.state.value.feedRateLimitedUntil)
            }
            val restarted = DefaultYouTubeHealth(f.settings, f.clock, backgroundScope) {}
            restarted.awaitLoaded()
            restarted.reportFeedRateLimited(f.clock.now(), 8 * HOUR)
            restarted.awaitLoaded()
            assertEquals(f.clock.now() + 8 * HOUR, restarted.state.value.feedRateLimitedUntil)
            restarted.reportFeedRecovered()
            restarted.reportFeedRateLimited(f.clock.now(), null)
            restarted.awaitLoaded()
            assertEquals(f.clock.now() + HOUR / 2, restarted.state.value.feedRateLimitedUntil)
        }

    @Test fun queuedReportsLoadFirstAndRetryKeepsLevelAndSchedules() =
        runTest {
            val f = fixture()
            f.health.reportFeedOutage(f.clock.now())
            f.health.reportFeedRateLimited(f.clock.now(), HOUR)
            f.health.retryNow()
            f.health.awaitLoaded()
            assertEquals(1, f.health.state.value.feedOutageLevel)
            assertEquals(f.clock.now(), f.health.state.value.feedOutageUntil)
            assertNull(f.health.state.value.feedRateLimitedUntil)
            assertEquals(1, f.retries)
            assertEquals(FeedGate.Probe, f.monitor.gate(1, f.clock.now()))
        }

    @Test fun threeOfFirstFourDefersRestAndLateSuccessDoesNotClearOutage() =
        runTest {
            val f = fixture(total = 10)
            f.report(1, FeedOutcome.SUCCESS)
            for (id in 2L..4L) f.report(id)
            assertEquals(1, f.health.state.value.feedOutageLevel)
            assertIs<FeedGate.Deferred>(f.monitor.gate(5, f.clock.now()))
            f.report(6, FeedOutcome.SUCCESS)
            assertEquals(1, f.health.state.value.feedOutageLevel)
            f.finish()
            assertEquals(1, f.health.state.value.feedOutageLevel)
        }

    @Test fun endOfRunMajorityRequiresThreeAndCountsDistinctChannels() =
        runTest {
            val f = fixture(total = 10)
            f.report(1, FeedOutcome.SUCCESS)
            f.report(2, FeedOutcome.SUCCESS)
            for (id in 3L..5L) f.report(id)
            assertEquals(0, f.health.state.value.feedOutageLevel)
            f.finish()
            assertEquals(1, f.health.state.value.feedOutageLevel)
            val duplicate = fixture(total = 10)
            repeat(5) { duplicate.report(1) }
            duplicate.finish()
            assertEquals(0, duplicate.health.state.value.feedOutageLevel)
        }

    @Test fun partialRefreshAndHalfFailuresDoNotDeclare() =
        runTest {
            for ((total, attempted) in listOf(2 to 1, 10 to 1, 2 to 2, 10 to 4)) {
                val f = fixture(total)
                for (id in 1..attempted) {
                    val outcome = if (id == 1) FeedOutcome.FAILURE else FeedOutcome.SUCCESS
                    f.report(id.toLong(), outcome)
                }
                f.finish()
                assertEquals(0, f.health.state.value.feedOutageLevel)
                assertEquals(0, f.controls)
            }
        }

    @Test fun allSmallLibraryFailuresNeedFailingControls() =
        runTest {
            for (total in 1..2) {
                for (control in FeedOutcome.entries) {
                    val f = fixture(total, control)
                    for (id in 1..total) f.report(id.toLong())
                    f.finish()
                    val expectedLevel = if (control == FeedOutcome.FAILURE) 1 else 0
                    assertEquals(expectedLevel, f.health.state.value.feedOutageLevel)
                    assertEquals(1, f.controls)
                }
            }
        }

    @Test fun workingControlsCacheForExactlyOneHour() =
        runTest {
            val f = fixture(total = 1, control = FeedOutcome.SUCCESS)
            repeat(2) {
                f.report(1)
                f.finish()
            }
            assertEquals(1, f.controls)
            advanceTimeBy(HOUR - 1)
            f.report(1)
            f.finish()
            assertEquals(1, f.controls)
            advanceTimeBy(1)
            f.report(1)
            f.finish()
            assertEquals(2, f.controls)
        }

    @Test fun offlineAndCancellationExcludedAndReleaseProbeWithoutIo() =
        runTest {
            val f = fixture(total = 1)
            repeat(4) { f.report(it.toLong(), FeedOutcome.INCONCLUSIVE) }
            f.finish()
            assertEquals(0, f.controls)
            f.health.reportFeedOutage(f.clock.now())
            f.health.retryNow()
            f.health.awaitLoaded()
            assertEquals(FeedGate.Probe, f.monitor.gate(1, f.clock.now()))
            val cancelled =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        f.report(1, FeedOutcome.INCONCLUSIVE)
                    }
                }
            runCurrent()
            cancelled.cancel()
            cancelled.join()
            assertEquals(FeedGate.Probe, f.monitor.gate(2, f.clock.now()))
            assertEquals(0, f.controls)
        }

    @Test fun exactlyOneProbeWaiterReevaluatesAfterRecovery() =
        runTest {
            val f = fixture()
            f.health.reportFeedOutage(f.clock.now())
            f.health.retryNow()
            f.health.awaitLoaded()
            assertEquals(FeedGate.Probe, f.monitor.gate(1, f.clock.now()))
            val waiter = async { f.monitor.gate(2, f.clock.now()) }
            runCurrent()
            assertTrue(!waiter.isCompleted)
            f.report(1, FeedOutcome.SUCCESS)
            assertEquals(FeedGate.Fetch, waiter.await())
            assertEquals(0, f.health.state.value.feedOutageLevel)
        }

    @Test fun probeWaitIsBoundedToTwentySecondsAndFailureRaisesBackoff() =
        runTest {
            val f = fixture()
            f.health.reportFeedOutage(f.clock.now())
            f.health.retryNow()
            f.health.awaitLoaded()
            assertEquals(FeedGate.Probe, f.monitor.gate(1, f.clock.now()))
            val waiter = async { f.monitor.gate(2, f.clock.now()) }
            runCurrent()
            advanceTimeBy(20_000)
            runCurrent()
            assertIs<FeedGate.Deferred>(waiter.await())
            f.report(1)
            assertEquals(2, f.health.state.value.feedOutageLevel)
            assertEquals(f.clock.now() + 2 * HOUR, f.health.state.value.feedOutageUntil)
        }

    @Test fun deletedChannelProbeClearsOutageWhenControlsWork() =
        runTest {
            val f = fixture(total = 1, control = FeedOutcome.SUCCESS)
            f.health.reportFeedOutage(f.clock.now())
            f.health.retryNow()
            f.health.awaitLoaded()
            f.monitor.gate(1, f.clock.now())
            f.report(1)
            assertEquals(0, f.health.state.value.feedOutageLevel)
            f.finish()
            assertEquals(0, f.health.state.value.feedOutageLevel)
            assertEquals(1, f.controls)
        }

    @Test fun finalizationControlsBoundedAndCancelledRunCountersReset() =
        runTest {
            val f = fixture(total = 1)
            val entered = CompletableDeferred<Unit>()
            val monitor =
                YouTubeOutageMonitor(f.health, f.clock, { 1 }) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            monitor.report(1, FeedOutcome.FAILURE, f.clock.now())
            val finish = async { monitor.onRunFinished(1, f.clock.now()) }
            entered.await()
            advanceTimeBy(20_000)
            runCurrent()
            finish.await()
            assertEquals(0, f.health.state.value.feedOutageLevel)
            monitor.onRunFinished(1, f.clock.now()) // No stale failure invokes controls again.
            monitor.report(1, FeedOutcome.FAILURE, f.clock.now())
            val cancelled = launch { monitor.onRunFinished(1, f.clock.now()) }
            runCurrent()
            cancelled.cancel()
            cancelled.join()
            monitor.onRunFinished(1, f.clock.now())
        }

    @Test fun rateLimitDefersWithoutControlChecks() =
        runTest {
            val f = fixture(total = 1)
            f.report(1)
            f.health.reportFeedRateLimited(f.clock.now(), HOUR)
            f.health.awaitLoaded()
            f.finish()
            assertEquals(0, f.controls)
            assertEquals(0, f.health.state.value.feedOutageLevel)
            assertEquals(FeedGate.Deferred(f.clock.now() + HOUR), f.monitor.gate(2, f.clock.now()))
        }

    private fun TestScope.fixture(
        total: Int = 10,
        control: FeedOutcome = FeedOutcome.FAILURE,
    ): Fixture = Fixture(this, total, control)

    private class Fixture(
        scope: TestScope,
        private val total: Int,
        control: FeedOutcome,
    ) {
        val clock = TestClock.from(scope.testScheduler)
        val settings = FakeSettingsRepository()
        var retries = 0
        var controls = 0
        val health = DefaultYouTubeHealth(settings, clock, scope.backgroundScope) { retries++ }
        val monitor =
            YouTubeOutageMonitor(health, clock, { total }) {
                controls++
                control
            }

        suspend fun report(
            id: Long,
            outcome: FeedOutcome = FeedOutcome.FAILURE,
        ) = monitor.report(id, outcome, clock.now())

        suspend fun finish() = monitor.onRunFinished(total, clock.now())
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
