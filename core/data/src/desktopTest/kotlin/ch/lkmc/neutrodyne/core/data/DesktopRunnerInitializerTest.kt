// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.ConnectionPoolEvictor
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.data.refresh.DesktopRefreshScheduler
import ch.lkmc.neutrodyne.core.data.refresh.DesktopRunnerInitializer
import ch.lkmc.neutrodyne.core.data.refresh.NextRefreshRebaser
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `DesktopRunnerInitializer` at band 200 (03's `reschedulePeriodic` row): the order-200 start-up
 * initializer calls it "on both platforms" — on the desktop that means `runner.start()` first,
 * then the `nextRefreshAt` rebase through the scheduler. The desktop rebase is
 * `NextRefreshRebaser.rebase()` only (03's "step 2 only"), observable through a row whose
 * persisted schedule postdates the interval target.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy, runCurrent, StandardTestDispatcher
class DesktopRunnerInitializerTest {
    @Test
    fun runStartsTheRunnerThenReschedulesPeriodic() =
        runTest {
            val clock = TestClock()
            val db =
                TestDb.inMemory(clock = clock, queryContext = StandardTestDispatcher(testScheduler))
            try {
                val events = mutableListOf<String>()
                val lane = SilentLane()
                val runner =
                    DesktopJobRunner(
                        lanes = setOf(lane),
                        clock = clock,
                        power = NoPower,
                        network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                        poolEvictor = NoopPoolEvictor,
                        scope = backgroundScope,
                    )
                val scheduler =
                    DesktopRefreshScheduler(
                        db = db,
                        rebaser = NextRefreshRebaser(db, EffectiveSettingsResolverImpl(db, FakeSettingsRepository())),
                        poker = Provider { JobLanePoker {} },
                        appScope = backgroundScope,
                    )
                val initializer =
                    DesktopRunnerInitializer(
                        runner =
                            Provider {
                                events += "runner"
                                runner
                            },
                        scheduler =
                            Provider {
                                events += "scheduler"
                                scheduler
                            },
                    )
                // A healthy row scheduled far past its interval target: `rebase()` pulls it
                // forward to `subscribedAt + I` (I = the 240-min default).
                val id =
                    seedPodcast(
                        db,
                        feedUrl = "https://a.example.com/f",
                        subscribedAt = NOW,
                        nextRefreshAt = NOW + 10 * DAY_MS,
                    )

                initializer.run()

                // The scheduler resolves only after the runner — 03's "calls it" order.
                assertEquals(listOf("runner", "scheduler"), events)
                assertEquals(
                    NOW + INTERVAL_MS,
                    db.podcastDao().byId(id)!!.nextRefreshAt,
                )

                // The runner really started: the first post-settle tick launches the lane.
                advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
                awaitRuns(lane, 1)
            } finally {
                db.close()
            }
        }

    /** Real-time-bounded `runCurrent` pump — lane bodies ride `Dispatchers.IO` threads. */
    private suspend fun TestScope.awaitRuns(
        lane: SilentLane,
        n: Int,
    ) {
        val deadline = System.nanoTime() + AWAIT_NS
        while (lane.runCount.get() < n) {
            check(System.nanoTime() < deadline) { "timed out waiting for ${lane.name} run #$n" }
            runCurrent()
            Thread.sleep(2)
        }
    }

    private class SilentLane : JobLane {
        override val name: String = "refresh"
        val runCount = AtomicInteger(0)

        override suspend fun run(now: Instant) {
            runCount.incrementAndGet()
        }
    }

    private object NoPower : PowerMonitor {
        override val events: Flow<PowerEvent> = emptyFlow()
    }

    private object NoopPoolEvictor : ConnectionPoolEvictor {
        override fun evict() = Unit
    }

    private companion object {
        const val NOW = TestClock.DEFAULT_NOW
        const val DAY_MS = 86_400_000L
        const val INTERVAL_MS = 240 * 60_000L
        const val AWAIT_NS = 10_000_000_000L
    }
}
