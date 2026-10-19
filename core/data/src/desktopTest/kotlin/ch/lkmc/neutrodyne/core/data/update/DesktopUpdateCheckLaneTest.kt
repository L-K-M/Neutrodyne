// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.ConnectionPoolEvictor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys as Keys

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopUpdateCheckLaneTest {
    private class Harness(
        test: TestScope,
        val store: MemoryUpdateStore = MemoryUpdateStore(),
        val clock: TestClock = TestClock(),
        val settings: FakeSettingsRepository = FakeSettingsRepository(),
        build: BuildInfo = updateBuild(platform = BuildInfo.Platform.DESKTOP),
        jitter: Long = HOUR,
    ) {
        val source = TestUpdateSource()
        val notifications = TestUpdateNotifications()
        val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
        var pokes = 0
        lateinit var checker: AppUpdateCheckerImpl
        val runner =
            LaneUpdateCheckRunner({ checker }, {
                ch.lkmc.neutrodyne.core.common
                    .JobLanePoker { pokes++ }
            })
        val lane = DesktopUpdateCheckLane({ checker }, network)

        init {
            checker =
                AppUpdateCheckerImpl(
                    build,
                    runtime(UpdateRuntimeTarget.Desktop("24.04")),
                    settings,
                    source,
                    store,
                    DesktopAssetSelector(),
                    notifications,
                    { runner },
                    network,
                    clock,
                    UpdateJitter { jitter },
                    test.backgroundScope,
                )
        }

        suspend fun tick() = lane.run(Instant.fromEpochMilliseconds(clock.now()))

        fun due() {
            clock.nowMs = store.value!!.nextDueAtMs
            clock.elapsedMs += DAY
        }
    }

    @Test fun firstDelaySurvivesRepeatedRestartsAndPokes() =
        runTest {
            val first = Harness(this)
            first.tick()
            assertEquals(TestClock.DEFAULT_NOW + DAY, first.store.value!!.nextDueAtMs)
            first.clock.nowMs += HOUR
            val restart = Harness(this, first.store, first.clock)
            repeat(4) { restart.tick() }
            assertEquals(0, restart.source.calls)
            assertEquals(TestClock.DEFAULT_NOW + DAY, restart.store.value!!.nextDueAtMs)
            restart.due()
            restart.tick()
            assertEquals(1, restart.source.calls)
            assertEquals(restart.clock.now() + DAY + HOUR, restart.store.value!!.nextDueAtMs)
            repeat(3) { restart.tick() }
            assertEquals(1, restart.source.calls)
        }

    @Test fun hourlyFailureBackoffPersistsAndCapsAtOneDay() =
        runTest {
            var h = Harness(this)
            h.tick()
            for (hours in listOf(1, 2, 4, 8, 16, 24, 24)) {
                h.due()
                h.source.result = Outcome.Failure(UpdateCheckError.RATE_LIMITED)
                h.tick()
                assertEquals(h.clock.now() + hours * HOUR, h.store.value!!.nextDueAtMs)
                h = Harness(this, h.store, h.clock)
                h.tick()
                assertEquals(0, h.source.calls)
            }
            h.due()
            h.tick()
            assertEquals(0, h.store.value!!.retryAttempt)
        }

    @Test fun offAndDevStayInertButManualWhileOffRunsImmediatelyWithoutPosting() =
        runTest {
            val settings = FakeSettingsRepository()
            settings.set(Keys.CHECK_ENABLED, false)
            val h = Harness(this, settings = settings)
            h.tick()
            h.due()
            h.tick()
            assertEquals(0, h.source.calls)
            assertEquals(0, h.pokes)
            assertIs<UpdateCheckState.Available>(h.checker.checkNow())
            assertEquals(1, h.source.calls)
            assertTrue(h.notifications.posted.isEmpty())
            assertEquals(1000095L, settings.get(Keys.NOTIFIED_VERSION_CODE))
            val dev = Harness(this, build = updateBuild(platform = BuildInfo.Platform.DESKTOP, kind = InstallKind.DEV))
            dev.tick()
            dev.due()
            dev.tick()
            dev.checker.checkNow()
            assertEquals(0, dev.source.calls)
        }

    @Test fun offlineTickPreservesOverdueTimeForResume() =
        runTest {
            val h = Harness(this)
            h.tick()
            h.due()
            val due = h.store.value!!.nextDueAtMs
            h.network.setStatus(FakeNetworkMonitor.OFFLINE)
            h.tick()
            assertEquals(due, h.store.value!!.nextDueAtMs)
            h.network.setStatus(FakeNetworkMonitor.ONLINE)
            h.tick()
            assertEquals(1, h.source.calls)
        }

    @Test fun scheduledNotificationsOncePerVersionAndSkipCancel() =
        runTest {
            val h = Harness(this)
            h.tick()
            repeat(2) {
                h.due()
                h.tick()
            }
            assertEquals(1, h.notifications.posted.size)
            h.checker.skip(1000095L)
            runCurrent()
            assertEquals(1, h.notifications.cancelled)
            h.due()
            h.tick()
            assertEquals(1, h.notifications.posted.size)
        }

    @Test fun cancellingLaneStopsItsGet() =
        runTest {
            val h = Harness(this)
            h.tick()
            h.due()
            val started = CompletableDeferred<Unit>()
            var cancelled = false
            h.source.action = {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val run = async { h.tick() }
            started.await()
            run.cancelAndJoin()
            runCurrent()
            assertTrue(cancelled)
            assertTrue(h.notifications.posted.isEmpty())
        }

    @Test fun minuteTicksAndWakePokesObeyDueTimeAndStopCancelsActiveGet() =
        runTest {
            val h = Harness(this)
            val events = MutableSharedFlow<PowerEvent>(extraBufferCapacity = 1)
            val runner =
                DesktopJobRunner(
                    setOf(h.lane),
                    h.clock,
                    object : PowerMonitor {
                        override val events = events
                    },
                    h.network,
                    object : ConnectionPoolEvictor {
                        override fun evict() = Unit
                    },
                    backgroundScope,
                )
            runner.start()
            advanceTimeBy(6_000)
            pumpUntil { h.store.value != null }
            assertEquals(0, h.source.calls)
            runner.poke(UPDATE_PERIODIC_WORK)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, h.source.calls)
            h.due()
            events.tryEmit(PowerEvent.Resumed)
            pumpUntil { h.source.calls == 1 && h.notifications.posted.size == 1 }
            assertTrue(runner.lastWakeAt.value != null)
            // Subsequent minute ticks and pokes cannot bypass the newly persisted daily due time.
            runner.poke(UPDATE_PERIODIC_WORK)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, h.source.calls)
            h.due()
            val entered = CompletableDeferred<Unit>()
            var cancelled = false
            h.source.action = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            runner.poke(UPDATE_PERIODIC_WORK)
            pumpUntil { entered.isCompleted }
            runner.stop(3.seconds)
            runCurrent()
            assertTrue(cancelled)
            val calls = h.source.calls
            advanceTimeBy(DAY)
            runCurrent()
            assertEquals(calls, h.source.calls)
        }

    private fun TestScope.pumpUntil(condition: () -> Boolean) {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Desktop runner did not complete the expected transition" }
            runCurrent()
            Thread.sleep(5)
        }
        runCurrent()
    }

    @Test fun jitterIncludesBothEndpoints() =
        runTest {
            for (delay in listOf(0L, HOUR)) {
                val h = Harness(this, jitter = delay)
                h.tick()
                h.due()
                h.tick()
                assertEquals(h.clock.now() + DAY + delay, h.store.value!!.nextDueAtMs)
            }
        }
}
