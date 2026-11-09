// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.testing.EngineUpdateFixtures
import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineUpdatePolicy
import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateHooks
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateTrigger
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Test

/**
 * The desktop lane's own cadence: the persisted once-a-day + jitter gate, the immediate-check
 * breaker flag, the bounded retry arm, and the initial bundled prepare. Everything below the
 * lane (the updater, the child, the store) stays faked — the lane is the business rule.
 */
class DesktopEngineUpdateLaneTest {
    private val pokes = mutableListOf<String>()
    private val scheduler =
        DesktopEngineUpdateScheduler { JobLanePoker { name -> pokes += name } }
    private val store = FakeEngineStore()
    private val settings = FakeEngineSettings()
    private val transport = FakeYtxTransport()
    private val preflight = FakeEnginePlatformPreflight()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val clock = TestClock()
    private val hooks = FakeHooks()
    private val ping = { YtxControlRequest(YtxContext("en", "US", "fixture/1")) }

    private fun lane(jitterSeed: Long = 0) =
        DesktopEngineUpdateLane(
            store,
            transport,
            settings,
            preflight,
            scheduler,
            hooks,
            network,
            clock,
            ping,
            jitterSeed,
        )

    private fun now(deltaMs: Long = 0): Instant =
        Instant.fromEpochMilliseconds(clock.now() + deltaMs)

    private fun markCheckedAt(msAgo: Long) {
        settings.state.value = settings.state.value.copy(lastCheckAtMs = clock.now() - msAgo)
    }

    private fun armPrepare() {
        store.prepare = {
            PreparedEngine("/engine-test/lib".toPath(), CompileMarker(1, BUNDLED_VERSION, "3.14"))
        }
        transport.answer = { call ->
            when (call.method) {
                YtxMethod.PING -> """{"ready":true}"""
                else -> throw IllegalStateException("unexpected ${call.method}")
            }
        }
    }

    @Test fun laneNameMatchesTheRunnerOrder() {
        assertEquals("engine-update", DesktopEngineUpdateLane.NAME)
    }

    @Test fun dueRunDispatchesTheScheduledTrigger() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            scheduler.scheduleDaily(true)
            lane().run(now())
            assertEquals(listOf(EngineUpdateTrigger.SCHEDULED), hooks.triggers)
        }

    @Test fun notYetDueRunsNothing() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            scheduler.scheduleDaily(true)
            markCheckedAt(DesktopEngineUpdateLane.CHECK_INTERVAL_MS - 1)
            lane().run(now())
            assertTrue(hooks.triggers.isEmpty())
        }

    @Test fun dueBoundaryRunsAtIntervalPlusPerInstallJitter() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            scheduler.scheduleDaily(true)
            // jitterSeed=0 → offset 0: exactly 24 h since the last check is due.
            markCheckedAt(DesktopEngineUpdateLane.CHECK_INTERVAL_MS)
            lane(jitterSeed = 0).run(now())
            assertEquals(listOf(EngineUpdateTrigger.SCHEDULED), hooks.triggers)
        }

    @Test fun jitterDefersTheDueCheckByUpToAnHour() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            scheduler.scheduleDaily(true)
            val seed = 7L // jitter = 7 ms inside [0, JITTER_MS)
            markCheckedAt(DesktopEngineUpdateLane.CHECK_INTERVAL_MS + 6)
            lane(jitterSeed = seed).run(now())
            assertTrue(hooks.triggers.isEmpty())
            markCheckedAt(DesktopEngineUpdateLane.CHECK_INTERVAL_MS + 7)
            lane(jitterSeed = seed).run(now())
            assertEquals(listOf(EngineUpdateTrigger.SCHEDULED), hooks.triggers)
        }

    @Test fun dailyGateOffRunsNothingEvenWhenDue() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            lane().run(now())
            assertTrue(hooks.triggers.isEmpty())
        }

    @Test fun disabledEngineSkipsTheUpdateAndThePrepare() =
        runTest {
            settings.state.value = settings.state.value.copy(enabled = false)
            scheduler.scheduleDaily(true)
            lane().run(now())
            assertTrue(hooks.triggers.isEmpty())
            assertEquals(0, store.preparations)
        }

    @Test fun theLaneReadsOnlyTheDailyFlagPolicyGatingStaysWithTheInitializer() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            settings.state.value = settings.state.value.copy(updatePolicy = EngineUpdatePolicy.OFF)
            scheduler.scheduleDaily(true)
            lane().run(now())
            // The lane itself only reads the daily flag; the initializer owns policy gating.
            assertEquals(listOf(EngineUpdateTrigger.SCHEDULED), hooks.triggers)
        }

    @Test fun preflightLimitedSkipsEverything() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            preflight.result = PlatformLimitation.HOST_UNUSABLE
            scheduler.scheduleDaily(true)
            lane().run(now())
            assertTrue(hooks.triggers.isEmpty())
            assertEquals(0, store.preparations)
        }

    @Test fun offlineTickRunsNeitherImmediateNorDaily() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            scheduler.scheduleDaily(true)
            scheduler.requestImmediateCheck()
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            lane().run(now())
            assertTrue(hooks.triggers.isEmpty())
            assertTrue(scheduler.immediatePending)
        }

    @Test fun immediateCheckPokesTheLaneAndRunsTheBreakerTrigger() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            scheduler.scheduleDaily(true)
            scheduler.requestImmediateCheck()
            assertEquals(listOf("engine-update"), pokes)
            lane().run(now())
            assertEquals(listOf(EngineUpdateTrigger.BREAKER), hooks.triggers)
            assertTrue(!scheduler.immediatePending)
        }

    @Test fun immediateCheckBeatsTheDailyGate() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            markCheckedAt(DesktopEngineUpdateLane.CHECK_INTERVAL_MS) // due anyway
            scheduler.requestImmediateCheck()
            lane().run(now())
            assertEquals(listOf(EngineUpdateTrigger.BREAKER), hooks.triggers)
        }

    @Test fun failedOutcomeReArmsBoundedAttempts() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            hooks.outcome = EngineUpdateOutcome.Failed(TransientKind.NETWORK)
            scheduler.requestImmediateCheck()
            val lane = lane()
            // First run: the breaker trigger; retries re-arm the SCHEDULED trigger.
            assertFailsWith<EngineUpdateLaneRetry> { lane.run(now()) }
            for (attempt in 2 until DesktopEngineUpdateLane.MAX_ATTEMPTS) {
                assertFailsWith<EngineUpdateLaneRetry> { lane.run(now()) }
            }
            lane.run(now()) // the bound passes: outcome swallowed, counter cleared
            assertEquals(
                listOf(EngineUpdateTrigger.BREAKER) +
                    List(DesktopEngineUpdateLane.MAX_ATTEMPTS - 1) { EngineUpdateTrigger.SCHEDULED },
                hooks.triggers,
            )
        }

    @Test fun stagedOutcomeReArmsLikeAFailure() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            hooks.outcome = EngineUpdateOutcome.Staged(EngineUpdateFixtures.NEW_VERSION)
            scheduler.requestImmediateCheck()
            assertFailsWith<EngineUpdateLaneRetry> { lane().run(now()) }
            assertEquals(listOf(EngineUpdateTrigger.BREAKER), hooks.triggers)
        }

    @Test fun verdictClearsTheRetryCounter() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            hooks.outcome = EngineUpdateOutcome.Failed(TransientKind.NETWORK)
            scheduler.requestImmediateCheck()
            val lane = lane()
            assertFailsWith<EngineUpdateLaneRetry> { lane.run(now()) }
            hooks.outcome = EngineUpdateOutcome.UpToDate
            lane.run(now())
            // Cleared: the next run is the daily gate again, not a retry.
            scheduler.scheduleDaily(true)
            lane.run(now())
            assertEquals(
                listOf(
                    EngineUpdateTrigger.BREAKER,
                    EngineUpdateTrigger.SCHEDULED,
                    EngineUpdateTrigger.SCHEDULED,
                ),
                hooks.triggers,
            )
        }

    @Test fun cancellationPropagatesUnchanged() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            scheduler.scheduleDaily(true)
            hooks.fault = CancellationException("caller")
            assertFailsWith<CancellationException> { lane().run(now()) }
        }

    @Test fun unpreparedEngineGetsTheBundledPreparePass() =
        runTest {
            armPrepare()
            scheduler.scheduleDaily(true)
            lane().run(now())
            assertEquals(1, store.preparations)
            assertTrue(transport.calls.any { it.method == YtxMethod.PING })
        }

    @Test fun compiledEngineSkipsThePreparePass() =
        runTest {
            store.preparation.value = EnginePreparation.COMPILED
            scheduler.scheduleDaily(true)
            lane().run(now())
            assertEquals(0, store.preparations)
            assertTrue(transport.calls.none { it.method == YtxMethod.PING })
        }

    @Test fun failedPrepareStillRunsTheUpdate() =
        runTest {
            store.prepare = { throw IllegalStateException("zip missing") }
            scheduler.scheduleDaily(true)
            lane().run(now())
            assertEquals(listOf(EngineUpdateTrigger.SCHEDULED), hooks.triggers)
        }

    private class FakeHooks : EngineUpdateHooks {
        val triggers = mutableListOf<EngineUpdateTrigger>()
        var outcome: EngineUpdateOutcome = EngineUpdateOutcome.UpToDate
        var fault: Throwable? = null
        var resets = 0

        override suspend fun runUpdate(trigger: EngineUpdateTrigger): EngineUpdateOutcome {
            triggers += trigger
            fault?.let { throw it }
            return outcome
        }

        override suspend fun resetToBundled() {
            resets++
        }

        override fun requestImmediateCheck() = Unit
    }
}
