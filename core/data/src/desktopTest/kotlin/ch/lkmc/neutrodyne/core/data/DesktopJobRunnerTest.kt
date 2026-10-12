// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.ConnectionPoolEvictor
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `DesktopJobRunnerTest` — the M1a legs of 11's Testing row (fake lanes, `TestClock`), the
 * runner half of PLAN M1 AC10: the first tick launches every lane after the settle, a `poke`
 * runs a lane within one dispatch and pokes coalesce, a running lane is never started twice and
 * a poke during a run reruns it once, a poke bypasses an active backoff, failures back off
 * `min(2^(n−1) min, 30 min)` and clear on success, a throw outside the lane's
 * `suspendRunCatching` guard ends only that lane, a return to "online" pokes 11 step 6's
 * network lanes, and `stop(grace)` cancels an in-flight run, awaits its finalizer and drops the
 * armed rerun. Whether a *lane* considers work due at all is the lane's own gate — 03's
 * `DesktopRefreshLaneTest` owns that side.
 *
 * The tick loop rides the virtual scheduler; lane bodies run on `Dispatchers.IO` threads, so
 * every wait below is a real-time-bounded pump of [runCurrent] — never a bare sleep.
 *
 * The Testing row's last leg is M1a too (PLAN M1 AC10 tags it, MD2 AC6 re-runs it against the
 * fuller lane set): a simulated 3-hour sleep wakes every overdue lane within 2 min — covered
 * both ways the doc splits it, a `Resumed` notice and a silent wall-clock jump. The MD2-only
 * halves (lane fairness beyond the artwork bound, quitting-with-pending-work policies) have no
 * M1a behaviour to pin down.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy, runCurrent, TestClock.from(scheduler)
class DesktopJobRunnerTest {
    // --- First tick and pokes ----------------------------------------------------------------

    @Test
    fun theFirstTickLaunchesEveryLaneAfterTheSettle() =
        runTest {
            val refresh = QuietLane("refresh")
            val sync = QuietLane("sync")
            val runner = runner(refresh, sync)
            runner.start()

            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(refresh, 1)
            awaitRuns(sync, 1)

            runner.stop(3.seconds)
        }

    @Test
    fun aPokeRunsTheLaneWithinOneDispatchAndCoalesces() =
        runTest {
            val refresh = QuietLane("refresh")
            val sync = QuietLane("sync")
            val runner = runner(refresh, sync)
            runner.start()
            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(refresh, 1)
            awaitRuns(sync, 1)

            // One poke: an early tick, not the next minute — every idle lane launches on it.
            runner.poke("refresh")
            runCurrent()
            awaitRuns(refresh, 2)
            awaitRuns(sync, 2)

            // Two pokes inside one wait window still produce a single tick.
            runner.poke("refresh")
            runner.poke("refresh")
            runCurrent()
            awaitRuns(refresh, 3)
            awaitRuns(sync, 3)

            runner.stop(3.seconds)
        }

    @Test
    fun aPokeDuringARunRerunsOnceAndNeverConcurrently() =
        runTest {
            val lane = GateLane("refresh")
            val runner = runner(lane)
            runner.start()
            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(lane, 1)

            // Two pokes while the run is in flight arm a single rerun.
            runner.poke("refresh")
            runner.poke("refresh")
            runCurrent()
            lane.gates.trySend(Outcome.SUCCEED)
            awaitRuns(lane, 2)
            assertEquals(1, lane.maxRunning.get(), "the lane ran twice at once")

            // The flag was consumed: the released rerun does not trigger another.
            lane.gates.trySend(Outcome.SUCCEED)
            expectQuietly { lane.runCount.get() == 2 }
            assertEquals(2, lane.runCount.get(), "the consumed rerun must not chain")

            runner.stop(3.seconds)
        }

    // --- Failures and backoff ------------------------------------------------------------------

    @Test
    fun aPokeBypassesAnActiveBackoff() =
        runTest {
            val lane = FailingLane("refresh")
            val runner = runner(lane)
            runner.start()
            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(lane, 1)
            awaitStatus(runner, lane.name) { it.failures == 1L && it.backoffUntil != null }

            runner.poke("refresh")
            runCurrent()
            awaitRuns(lane, 2)

            runner.stop(3.seconds)
        }

    @Test
    fun failuresBackOffExponentiallyUpToTheCapAndSuccessClearsThem() =
        runTest {
            val lane = FailingLane("refresh")
            // The probe rides every tick: its run count proves each minute really elapsed.
            val probe = QuietLane("sync")
            // A manual clock: a lane fold reads `now()` on an I/O thread, where the scheduler's
            // virtual time would depend on when the thread happens to run.
            val clock = TestClock()
            val runner = runner(lane, probe, clock = clock)
            runner.start()
            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(lane, 1)
            var probeRuns = 1
            awaitRuns(probe, probeRuns)
            awaitStatus(runner, lane.name) { it.failures == 1L }

            // 1 min: the failure at t≈5 s is due again at the first minute tick.
            advanceTick(probe, ++probeRuns, clock)
            awaitRuns(lane, 2)
            awaitStatus(runner, lane.name) { it.failures == 2L }

            // 2 min: one skipped tick, then a run.
            advanceTick(probe, ++probeRuns, clock)
            expectQuietly { lane.runCount.get() == 2 }
            advanceTick(probe, ++probeRuns, clock)
            awaitRuns(lane, 3)
            awaitStatus(runner, lane.name) { it.failures == 3L }

            // 4, 8 and 16 min: k−1 skipped ticks each, then a run.
            var laneRuns = 3
            for (k in BACKOFF_LEGS_MIN) {
                repeat(k - 1) {
                    advanceTick(probe, ++probeRuns, clock)
                    expectQuietly { lane.runCount.get() == laneRuns }
                }
                advanceTick(probe, ++probeRuns, clock)
                awaitRuns(lane, ++laneRuns)
                awaitStatus(runner, lane.name) { it.failures == laneRuns.toLong() }
            }
            awaitStatus(runner, lane.name) { it.failures == 6L }

            // failure 6's 2^5 = 32 min clamps at the 30-min cap: 29 skips, then a run.
            repeat(29) {
                advanceTick(probe, ++probeRuns, clock)
                expectQuietly { lane.runCount.get() == laneRuns }
            }

            // That run succeeds: it clears the hold (`LaneStatus.failures` is the cumulative
            // diagnostics counter, not the backoff one — the hold clearing is the tell).
            lane.outcome = Outcome.SUCCEED
            advanceTick(probe, ++probeRuns, clock)
            awaitRuns(lane, ++laneRuns)
            awaitStatus(runner, lane.name) {
                it.backoffUntil == null && it.lastError == null && !it.running
            }

            runner.stop(3.seconds)
        }

    @Test
    fun aFailingLaneLeavesTheOtherLanesRunning() =
        runTest {
            val lane = FailingLane("refresh")
            val healthy = QuietLane("sync")
            val runner = runner(lane, healthy)
            runner.start()
            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(lane, 1)
            awaitRuns(healthy, 1)

            // The failing lane sits out its minute of backoff while the healthy one keeps ticking.
            advanceTimeBy(60.seconds)
            awaitRuns(healthy, 2)
            assertEquals(1, lane.runCount.get(), "the failing lane must sit out its backoff")

            runner.stop(3.seconds)
        }

    @Test
    fun aThrowOutsideTheLaneGuardEndsOnlyThatLane() =
        runTest {
            // `message` throws when the runner's failure fold reads it — outside the
            // `suspendRunCatching` around `lane.run` — and the lane coroutine dies. The tick loop
            // and the sibling lane must survive it (laneScope is a supervisor child).
            val nasty = NastyLane("refresh")
            val healthy = QuietLane("sync")
            val runner = runner(nasty, healthy)
            runner.start()
            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(nasty, 1)
            awaitRuns(healthy, 1)

            advanceTimeBy(60.seconds)
            awaitRuns(healthy, 2)

            runner.stop(3.seconds)
        }

    // --- Network regained -----------------------------------------------------------------------

    @Test
    fun aReturnToOnlinePokesTheNetworkLanesOnly() =
        runTest {
            // Both lanes are in a 1-min failure backoff, so only a *poke* launches them now: the
            // plain tick would skip them. `refresh` is in 11 step 6's set, `maintenance` is not.
            val refresh = FailingLane("refresh")
            val maintenance = FailingLane("maintenance")
            val network = FakeNetworkMonitor(FakeNetworkMonitor.OFFLINE)
            val runner = runner(refresh, maintenance, network = network)
            runner.start()
            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(refresh, 1)
            awaitRuns(maintenance, 1)

            network.setStatus(FakeNetworkMonitor.ONLINE)
            runCurrent()

            awaitRuns(refresh, 2)
            runCurrent()
            assertEquals(1, maintenance.runCount.get(), "a regain must not poke non-network lanes")

            runner.stop(3.seconds)
        }

    // --- Wake catch-up (PLAN M1 AC10's 3-hour sleep, 11 Testing's last leg) -----------------------

    @Test
    fun aResumedEventAfterASleepWakesEveryLaneIntoAnImmediateRun() =
        runTest {
            // Three failures park the lane in a 4-min backoff — without the wake its next run
            // misses the 2-min bound by half of it. `evictions` proves the wake ran its full
            // course (11 Wake: pools first, then every lane poked).
            val lane = FailingLane("refresh")
            val probe = QuietLane("sync")
            val power = FakePowerMonitor()
            val evictions = AtomicInteger(0)
            val clock = TestClock()
            val runner =
                runner(
                    lane,
                    probe,
                    power = power,
                    clock = clock,
                    evictor = CountingEvictor(evictions),
                )
            runner.start()
            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(lane, 1)
            awaitStatus(runner, lane.name) { it.failures == 1L }

            // f2 after one skipped tick, f3 after two: backoff 1 min, then 2, then 4.
            advanceTick(probe, 2, clock)
            awaitRuns(lane, 2)
            awaitStatus(runner, lane.name) { it.failures == 2L }
            advanceTick(probe, 3, clock)
            advanceTick(probe, 4, clock)
            awaitRuns(lane, 3)
            awaitStatus(runner, lane.name) { it.failures == 3L && it.backoffUntil != null }

            // The suspend: wall time jumps 3 h while the monotonic count holds still.
            clock.nowMs += SLEEP.inWholeMilliseconds
            power.resume()

            awaitRuns(lane, 4)
            awaitRuns(probe, 5)
            assertTrue(evictions.get() >= 1, "a wake must evict the connection pools")
            assertNotNull(runner.lastWakeAt.value)

            runner.stop(3.seconds)
        }

    @Test
    fun aWallClockJumpWithoutANoticeWakesEveryLane() =
        runTest {
            // The same sleep with no `Resumed`: the >90 s wall/monotonic drift at the next tick
            // is the only tell (11 Wake detection), so it lands one tick later than the notice.
            val lane = FailingLane("refresh")
            val probe = QuietLane("sync")
            val evictions = AtomicInteger(0)
            val clock = TestClock()
            val runner =
                runner(
                    lane,
                    probe,
                    clock = clock,
                    evictor = CountingEvictor(evictions),
                )
            runner.start()
            advanceTimeBy(DesktopJobRunner.START_SETTLE_MS + 1.seconds)
            awaitRuns(lane, 1)
            awaitStatus(runner, lane.name) { it.failures == 1L }
            advanceTick(probe, 2, clock)
            awaitRuns(lane, 2)
            awaitStatus(runner, lane.name) { it.failures == 2L }
            advanceTick(probe, 3, clock)
            advanceTick(probe, 4, clock)
            awaitRuns(lane, 3)
            awaitStatus(runner, lane.name) { it.failures == 3L && it.backoffUntil != null }

            clock.nowMs += SLEEP.inWholeMilliseconds
            clock.advanceBy(60.seconds)
            advanceTimeBy(60.seconds)

            awaitRuns(lane, 4)
            assertTrue(evictions.get() >= 1, "a wake must evict the connection pools")
            assertNotNull(runner.lastWakeAt.value)

            runner.stop(3.seconds)
        }

    // --- Shutdown ------------------------------------------------------------------------------

    /**
     * Runs on the real clock, not `runTest`: `stop(grace)` spends its timeout through the
     * caller's context, and the virtual scheduler would elapse all of it while the lane's real
     * `NonCancellable` flush still runs — only a real grace exercises the join.
     */
    @Test
    fun stopAwaitsTheInFlightFinalizerAndSuppressesTheArmedRerun() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val lane = FinalizerLane("refresh")
                val runner =
                    DesktopJobRunner(
                        lanes = setOf(lane),
                        clock = TestClock(),
                        power = NoPower,
                        network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                        poolEvictor = NoopPoolEvictor,
                        scope = scope,
                    )
                runner.start()
                awaitBlocking("${lane.name} run #1") { lane.runCount.get() >= 1 }

                runner.poke("refresh") // arms the rerun while the run is in flight
                Thread.sleep(POKE_SETTLE_MS) // give the tick its dispatch to consume the poke

                runner.stop(3.seconds)

                assertEquals(
                    1,
                    lane.finalized.get(),
                    "stop(grace) must await the NonCancellable flush",
                )
                assertEquals(1, lane.runCount.get(), "stop must suppress the armed rerun")
            } finally {
                scope.cancel()
            }
        }

    // --- Lanes, fakes and pumping ---------------------------------------------------------------

    /** What a `GateLane`/`FailingLane` run does at release time. */
    private enum class Outcome { SUCCEED, FAIL }

    private interface CountingLane : JobLane {
        val runCount: AtomicInteger
    }

    /** Returns at once — its run count is the proof a tick really processed. */
    private class QuietLane(
        override val name: String,
    ) : CountingLane {
        override val runCount = AtomicInteger(0)

        override suspend fun run(now: Instant) {
            runCount.incrementAndGet()
        }
    }

    /** Blocks inside `run` until the test sends an [Outcome]; records its peak concurrency. */
    private class GateLane(
        override val name: String,
    ) : CountingLane {
        val gates = Channel<Outcome>(Channel.UNLIMITED)
        override val runCount = AtomicInteger(0)
        val runningNow = AtomicInteger(0)
        val maxRunning = AtomicInteger(0)

        override suspend fun run(now: Instant) {
            runCount.incrementAndGet()
            val c = runningNow.incrementAndGet()
            maxRunning.updateAndGet { m -> max(m, c) }
            try {
                if (gates.receive() == Outcome.FAIL) throw RuntimeException("lane $name failed")
            } finally {
                runningNow.decrementAndGet()
            }
        }
    }

    /** Fails at once until [outcome] flips — the backoff legs need no gate. */
    private class FailingLane(
        override val name: String,
    ) : CountingLane {
        override val runCount = AtomicInteger(0)

        @Volatile var outcome = Outcome.FAIL

        override suspend fun run(now: Instant) {
            runCount.incrementAndGet()
            if (outcome == Outcome.FAIL) throw RuntimeException("lane $name failed")
        }
    }

    /**
     * Fails with an [Error] whose `message` detonates when the runner's failure fold reads it —
     * the throw lands outside `lane.run`'s `suspendRunCatching` and fails the lane coroutine.
     */
    private class NastyLane(
        override val name: String,
    ) : CountingLane {
        override val runCount = AtomicInteger(0)

        override suspend fun run(now: Instant) {
            runCount.incrementAndGet()
            throw object : Error() {
                override val message: String
                    get() = throw IllegalStateException("fold-side throw")
            }
        }
    }

    /**
     * Blocks until cancelled, then does the engine's `NonCancellable` finalization — the
     * `FeedRefresher` run's buffered flush and diagnostics write (03 Engine run).
     */
    private class FinalizerLane(
        override val name: String,
    ) : CountingLane {
        val gates = Channel<Unit>(Channel.UNLIMITED)
        val finalized = AtomicInteger(0)
        override val runCount = AtomicInteger(0)

        override suspend fun run(now: Instant) {
            runCount.incrementAndGet()
            try {
                gates.receive()
            } finally {
                withContext(NonCancellable) {
                    delay(FINALIZE_MS)
                    finalized.incrementAndGet()
                }
            }
        }
    }

    private object NoPower : PowerMonitor {
        override val events: Flow<PowerEvent> = emptyFlow()
    }

    /** A resume notice source: `resume()` delivers one `Resumed`, like a real OS suspend. */
    private class FakePowerMonitor : PowerMonitor {
        private val _events = MutableSharedFlow<PowerEvent>(extraBufferCapacity = 1)
        override val events: Flow<PowerEvent> = _events

        fun resume() {
            check(_events.tryEmit(PowerEvent.Resumed))
        }
    }

    private object NoopPoolEvictor : ConnectionPoolEvictor {
        override fun evict() = Unit
    }

    /** Wake coverage counts the pool evictions, not just the poked runs. */
    private class CountingEvictor(
        private val count: AtomicInteger,
    ) : ConnectionPoolEvictor {
        override fun evict() {
            count.incrementAndGet()
        }
    }

    private fun TestScope.runner(
        vararg lanes: JobLane,
        network: FakeNetworkMonitor = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
        power: PowerMonitor = NoPower,
        clock: TestClock = TestClock(),
        evictor: ConnectionPoolEvictor = NoopPoolEvictor,
    ): DesktopJobRunner =
        DesktopJobRunner(
            lanes = lanes.toSet(),
            clock = clock,
            power = power,
            network = network,
            poolEvictor = evictor,
            scope = backgroundScope,
        )

    /**
     * Waits for [n] starts of [lane] while virtual time stays frozen: pumping only [runCurrent]
     * means a rerun can come from the handoff or a delivered poke alone, never from the next
     * 60-s tick. Real-time bounded because lanes run on `Dispatchers.IO` threads.
     */
    private suspend fun TestScope.awaitRuns(
        lane: CountingLane,
        n: Int,
    ) = awaitCondition("${lane.name} run #$n") { lane.runCount.get() >= n }

    /**
     * Waits for a settled `LaneStatus` shape: [predicate] and the lane released. The runner
     * publishes `running = false` only after it gives up ownership, so the next tick is
     * guaranteed to see the lane idle — no real-time beat needed under load.
     */
    private suspend fun TestScope.awaitStatus(
        runner: DesktopJobRunner,
        lane: String,
        predicate: (ch.lkmc.neutrodyne.core.data.refresh.LaneStatus) -> Boolean,
    ) {
        awaitCondition("$lane's status") {
            runner.status.value[lane]?.let { !it.running && predicate(it) } == true
        }
        runCurrent()
    }

    private suspend fun TestScope.awaitCondition(
        what: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + AWAIT_NS
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            runCurrent()
            Thread.sleep(2)
        }
    }

    /** [awaitCondition] for the `runBlocking` leg, where there is no scheduler to pump. */
    private fun awaitBlocking(
        what: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + AWAIT_NS
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(2)
        }
    }

    /**
     * Pumps the scheduler and waits a real beat, then lets the caller assert a count did not
     * change — the negative half of [awaitRuns], for "must not have run" legs.
     */
    private suspend fun TestScope.expectQuietly(quiet: () -> Boolean) {
        val deadline = System.nanoTime() + QUIET_NS
        while (System.nanoTime() < deadline) {
            runCurrent()
            Thread.sleep(2)
        }
        assertTrue(quiet(), "a lane ran though it should have been skipped")
    }

    /**
     * Advances one minute on both the clock the runner stamps instants from and the scheduler
     * that drives the tick loop — the two move independently, so a leg's `nextRefreshAt`-style
     * arithmetic stays deterministic only when they advance together.
     */
    private suspend fun TestScope.advanceTick(
        probe: CountingLane,
        n: Int,
        clock: TestClock,
    ) {
        clock.advanceBy(60.seconds)
        advanceTimeBy(60.seconds)
        awaitRuns(probe, n)
    }

    private companion object {
        /** The doc's progression `min(2^(n−1) min, 30 min)`, as minutes until the next run. */
        val BACKOFF_LEGS_MIN = listOf(4, 8, 16)

        /** AC10's simulated suspend. */
        val SLEEP = 3.hours

        const val FINALIZE_MS = 150L
        const val POKE_SETTLE_MS = 200L
        const val AWAIT_NS = 10_000_000_000L
        const val QUIET_NS = 150_000_000L
    }
}
