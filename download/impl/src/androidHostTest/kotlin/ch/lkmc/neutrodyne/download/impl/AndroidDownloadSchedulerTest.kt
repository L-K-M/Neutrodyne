// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 33, 34, 36])
class AndroidDownloadSchedulerTest {
    @Test fun autoNeverUsesUidtExpeditedOrForegroundWait() =
        runTest {
            val f = SchedulerFixture()
            f.rows += downloadRow().copy(lane = DownloadLane.AUTO)
            f.visible = true
            f.limited = true
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            val request = f.enqueued.single().second
            assertEquals(0, f.uidtCalls)
            assertFalse(request.workSpec.expedited)
            assertEquals("AUTO", request.workSpec.input.getString(AndroidDownloadWork.KEY_LANE))
            assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
            assertTrue(request.workSpec.constraints.requiresStorageNotLow())
            assertFalse(request.workSpec.constraints.requiresCharging())
            assertTrue(f.notices.isEmpty())
            assertEquals(WaitReason.NONE, f.rows.single().waitReason)
            if (Build.VERSION.SDK_INT >= 28) {
                assertTrue(
                    checkNotNull(request.workSpec.constraints.requiredNetworkRequest).hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                    ),
                )
            }
        }

    @Test fun manualAndAutoPendingAndWorkerHistoryAreIndependent() =
        runTest {
            val f = SchedulerFixture()
            f.rows += downloadRow().copy(lane = DownloadLane.AUTO)
            f.rows += downloadRow(2)
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            f.ensure()
            assertEquals(2, f.enqueued.size)
            assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.KEEP), f.enqueued.map { it.first })
            val token = RunnerToken("work:auto")
            f.scheduler.workerStarted(token, DownloadLane.AUTO)
            f.scheduler.registry(DownloadLane.AUTO).unregister(token)
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, f.enqueued.last().first)
            f.ensure()
            assertEquals(3, f.enqueued.size)
        }

    @Test fun autoWakeOnlyCoversAutoQueuedUnclaimedRssRows() =
        runTest {
            val f = SchedulerFixture()
            f.rows += downloadRow().copy(nextAttemptAt = f.clock.now() + 1)
            f.rows += downloadRow(2).copy(lane = DownloadLane.AUTO, state = DownloadState.PAUSED)
            f.rows += downloadRow(3).copy(lane = DownloadLane.AUTO, runnerToken = "work:live")
            f.rows += downloadRow(4).copy(lane = DownloadLane.AUTO, sourceKind = SourceKind.YOUTUBE)
            f.scheduler.scheduleWake(DownloadLane.AUTO, f.clock.now() + 500, WaitReason.BACKOFF)
            assertNull(f.wakes.last())
            f.rows += downloadRow(5).copy(lane = DownloadLane.AUTO, nextAttemptAt = f.clock.now() + 90_000)
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            val wake = checkNotNull(f.wakes.last())
            assertEquals("AUTO", wake.workSpec.input.getString(AndroidDownloadWork.KEY_LANE))
            assertEquals(90_000, wake.workSpec.initialDelay)
            assertTrue(wake.workSpec.constraints.requiresBatteryNotLow())
            assertFalse(wake.workSpec.expedited)
            assertTrue(f.enqueued.isEmpty())
            f.rows.removeAll { it.episodeId == 5L }
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            assertNull(f.wakes.last())
        }

    @Test fun chargingHeldAutoArmsChargingWakeAndRunsAfterPlugIn() =
        runTest {
            val f = SchedulerFixture()
            f.charging = false
            f.rows += downloadRow().copy(lane = DownloadLane.AUTO, requireCharging = true)
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            assertTrue(f.enqueued.isEmpty())
            assertTrue(checkNotNull(f.wakes.last()).workSpec.constraints.requiresCharging())
            f.charging = true
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            assertEquals(1, f.enqueued.size)
            assertFalse(
                f.enqueued
                    .single()
                    .second.workSpec.constraints
                    .requiresCharging(),
            )
            assertNull(f.wakes.last())
        }

    @Test fun mixedAutoWakeDelayUsesOnlyRowsCoveredByNetworkAndCharging() =
        runTest {
            val f = SchedulerFixture()
            f.connected = false
            f.charging = false
            f.rows +=
                downloadRow().copy(
                    lane = DownloadLane.AUTO,
                    allowMetered = false,
                    nextAttemptAt = f.clock.now() + 1_000,
                )
            f.rows +=
                downloadRow(2).copy(
                    lane = DownloadLane.AUTO,
                    requireCharging = true,
                    nextAttemptAt = f.clock.now() + 2_000,
                )
            f.rows += downloadRow(3).copy(lane = DownloadLane.AUTO, nextAttemptAt = f.clock.now() + 90_000)
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            assertEquals(90_000, checkNotNull(f.wakes.last()).workSpec.initialDelay)
            assertFalse(checkNotNull(f.wakes.last()).workSpec.constraints.requiresCharging())
        }

    @Test fun disjointChargingAndNetworkNeedsStillCoverOneAutoRow() =
        runTest {
            val f = SchedulerFixture()
            f.connected = false
            f.charging = false
            f.rows +=
                downloadRow().copy(
                    lane = DownloadLane.AUTO,
                    allowMetered = false,
                    nextAttemptAt = f.clock.now() + 1_000,
                )
            f.rows +=
                downloadRow(2).copy(
                    lane = DownloadLane.AUTO,
                    requireCharging = true,
                    nextAttemptAt = f.clock.now() + 2_000,
                )
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            val wake = checkNotNull(f.wakes.last())
            assertEquals(1_000, wake.workSpec.initialDelay)
            assertFalse(wake.workSpec.constraints.requiresCharging())
            if (Build.VERSION.SDK_INT >= 28) {
                assertTrue(
                    checkNotNull(wake.workSpec.constraints.requiredNetworkRequest)
                        .hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                )
            }
        }

    @Test fun autoDeadlineAppendsContinuationEvenWhileUidtRuns() =
        runTest {
            val f = SchedulerFixture()
            f.rows += downloadRow().copy(lane = DownloadLane.AUTO)
            f.registry.uidtStarted(RunnerToken("uidt:manual"))
            val token = RunnerToken("work:auto")
            f.scheduler.workerStarted(token, DownloadLane.AUTO)
            val exit = f.scheduler.exit(DownloadLane.AUTO)
            assertTrue(exit.tryClose(token, DrainOutcome.DeadlineReached))
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, f.enqueued.single().first)
            assertFalse(
                f.enqueued
                    .single()
                    .second.workSpec.expedited,
            )
            assertEquals(setOf("uidt:manual"), f.scheduler.liveTokens())
        }

    @Test fun enqueueDuringAutoClosingReopensOnlyAuto() =
        runTest {
            val f = SchedulerFixture()
            val token = RunnerToken("work:auto")
            val exit = f.scheduler.exit(DownloadLane.AUTO)
            exit.register(token, setOf(DownloadLane.AUTO))
            val close = async(start = CoroutineStart.UNDISPATCHED) { exit.tryClose(token, DrainOutcome.Empty) }
            f.rows += downloadRow().copy(lane = DownloadLane.AUTO)
            f.scheduler.ensureScheduled(DownloadLane.AUTO)
            assertFalse(close.await())
            assertEquals(LaneRegistry.Phase.ACTIVE, f.scheduler.registry(DownloadLane.AUTO).phase())
            assertEquals(LaneRegistry.Phase.IDLE, f.registry.phase())
            assertTrue(f.enqueued.isEmpty())
            exit.unregister(token)
        }

    @Test fun cleanupRequestHasDailyCadenceAndSixHourFlex() {
        val request = AndroidDownloadWork.cleanup()
        assertEquals("download-cleanup", AndroidDownloadWork.CLEANUP)
        assertEquals(24 * 60 * 60_000L, request.workSpec.intervalDuration)
        assertEquals(6 * 60 * 60_000L, request.workSpec.flexDuration)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertFalse(request.workSpec.constraints.requiresCharging())
        assertFalse(request.workSpec.expedited)
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test fun emptyAndBackoffQueuesNeverStartIdleRunner() =
        runTest {
            val f = SchedulerFixture()
            f.ensure()
            assertTrue(f.enqueued.isEmpty())
            f.rows += downloadRow().copy(nextAttemptAt = f.clock.now() + 90_000)
            f.ensure()
            assertTrue(f.enqueued.isEmpty())
            assertEquals(
                90_000,
                f.wakes
                    .last()!!
                    .workSpec.initialDelay,
            )
            assertEquals(0, f.uidtCalls)
        }

    @Test fun wakeDelayConsidersOnlyCoveredRows() =
        runTest {
            val f = SchedulerFixture()
            f.rows += downloadRow(1).copy(allowMetered = false, nextAttemptAt = f.clock.now() + 10_000)
            f.rows += downloadRow(2).copy(allowMetered = true, nextAttemptAt = f.clock.now() + 80_000)
            f.ensure()
            assertEquals(
                80_000,
                f.wakes
                    .last()!!
                    .workSpec.initialDelay,
            )
        }

    @Test fun unvalidatedNetworkDoesNotSpinThePre28Fallback() =
        runTest {
            val f = SchedulerFixture()
            f.rows += downloadRow()
            f.validated = false
            f.ensure()
            assertTrue(f.enqueued.isEmpty())
            assertEquals(
                if (Build.VERSION.SDK_INT < 28) 60_000L else 0L,
                f.wakes
                    .last()!!
                    .workSpec.initialDelay,
            )
        }

    @Test fun pendingTapCoalescesAndOnlyPre31Upgrades() =
        runTest {
            val f = SchedulerFixture()
            f.rows += downloadRow()
            f.ensure()
            f.ensure()
            assertEquals(listOf(ExistingWorkPolicy.KEEP), f.enqueued.map { it.first })
            f.visible = true
            f.uidtAllowed = false
            f.ensure()
            if (Build.VERSION.SDK_INT < 31) {
                assertEquals(ExistingWorkPolicy.REPLACE, f.enqueued.last().first)
                assertTrue(
                    f.enqueued
                        .last()
                        .second.workSpec.expedited,
                )
                assertEquals(2, f.enqueued.size)
            } else {
                assertEquals(1, f.enqueued.size)
            }
        }

    @Test fun runningUidtNeverReschedulesWhenNeedsChange() =
        runTest {
            val f = SchedulerFixture()
            val token = RunnerToken("uidt:live")
            f.registry.uidtStarted(token)
            f.rows += downloadRow()
            f.visible = true
            f.ensure()
            f.rows += downloadRow(2).copy(allowMetered = true)
            f.ensure()
            assertEquals(0, f.uidtCalls)
            assertTrue(f.enqueued.isEmpty())
            assertEquals(2, f.pokes)
        }

    @Test fun visibilityAndApiChooseUidtOrWorker() =
        runTest {
            val f = SchedulerFixture()
            f.rows += downloadRow()
            f.visible = true
            f.ensure()
            if (Build.VERSION.SDK_INT >= 34) {
                assertEquals(1, f.uidtCalls)
                assertTrue(f.enqueued.isEmpty())
            } else {
                assertEquals(0, f.uidtCalls)
                assertTrue(
                    f.enqueued
                        .single()
                        .second.workSpec.expedited,
                )
            }
        }

    @Test fun deniedUidtFallsBackWithoutForegroundOn34() =
        runTest {
            val f = SchedulerFixture()
            f.visible = true
            f.uidtAllowed = false
            f.rows += downloadRow()
            f.ensure()
            assertEquals(ExistingWorkPolicy.KEEP, f.enqueued.single().first)
            assertEquals(
                Build.VERSION.SDK_INT >= 31 || f.visible,
                f.enqueued
                    .single()
                    .second.workSpec.expedited,
            )
        }

    @Test fun workerExitUsesAppendSoKeepCannotSwallowSuccessor() =
        runTest {
            val f = SchedulerFixture()
            f.registry.workerStarted()
            val token = RunnerToken("work:1")
            val exit = f.scheduler.exit()
            exit.register(token, setOf(DownloadLane.MANUAL))
            assertTrue(exit.tryClose(token, DrainOutcome.Empty))
            f.rows += downloadRow()
            f.ensure()
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, f.enqueued.single().first)
        }

    @Test fun enqueueDuringClosingReopensRunner() =
        runTest {
            val f = SchedulerFixture()
            val token = RunnerToken("work:1")
            val exit = f.scheduler.exit()
            exit.register(token, setOf(DownloadLane.MANUAL))
            val close = async(start = CoroutineStart.UNDISPATCHED) { exit.tryClose(token, DrainOutcome.Empty) }
            assertEquals(LaneRegistry.Phase.CLOSING, f.registry.phase())
            f.rows += downloadRow()
            f.ensure()
            assertFalse(close.await())
            assertEquals(LaneRegistry.Phase.ACTIVE, f.registry.phase())
            assertTrue(f.enqueued.isEmpty())
        }

    @Test fun deadlineAppendsOneContinuation() =
        runTest {
            val f = SchedulerFixture()
            val token = RunnerToken("work:1")
            f.rows += downloadRow()
            f.registry.workerStarted()
            val exit = f.scheduler.exit()
            exit.register(token, setOf(DownloadLane.MANUAL))
            assertTrue(exit.tryClose(token, DrainOutcome.DeadlineReached))
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, f.enqueued.single().first)
        }

    @Test fun restrictedBackgroundPersistsWaitWithoutSpinning() =
        runTest {
            val f = SchedulerFixture()
            f.rows += downloadRow()
            f.limited = true
            f.ensure()
            assertEquals(WaitReason.NEEDS_FOREGROUND, f.rows.single().waitReason)
            assertTrue(f.notices.last())
            assertTrue(f.enqueued.isEmpty())
            assertNull(f.wakes.last())
            f.visible = true
            f.ensure()
            assertEquals(WaitReason.NONE, f.rows.single().waitReason)
            assertFalse(f.notices.last())
            f.rows.clear()
            f.notices.clear()
            f.ensure()
            assertEquals(listOf(false), f.notices)
        }

    @Test fun offlineRowsArmWakeAndNoLane() =
        runTest {
            val f = SchedulerFixture()
            f.connected = false
            f.rows += downloadRow().copy(allowMetered = false)
            f.ensure()
            assertTrue(f.enqueued.isEmpty())
            assertNotNull(f.wakes.last())
        }
}

internal fun downloadRow(id: Long = 1) =
    DownloadEntity(
        id,
        DownloadLane.MANUAL,
        DownloadState.QUEUED,
        priority = 100,
        requestedAt = 0,
        sourceKind = SourceKind.RSS_ENCLOSURE,
        sourceRef = "https://example.test/$id.mp3",
        rootId = "int",
        allowMetered = true,
    )

internal class SchedulerFixture {
    val clock = TestClock()
    val rows = mutableListOf<DownloadEntity>()
    var visible = false
    var limited = false
    var connected = true
    var validated = true
    var unmetered = true
    var charging = true
    var uidtAllowed = true
    var uidtCalls = 0
    var pokes = 0
    val registry = LaneRegistry()
    val enqueued = mutableListOf<Pair<ExistingWorkPolicy, OneTimeWorkRequest>>()
    val wakes = mutableListOf<OneTimeWorkRequest?>()
    val notices = mutableListOf<Boolean>()
    val scheduler =
        AndroidDownloadScheduler(
            rows = { rows.toList() },
            markForeground = { ids, reason ->
                rows.replaceAll {
                    if (it.episodeId in
                        ids
                    ) {
                        it.copy(waitReason = reason)
                    } else {
                        it
                    }
                }
            },
            conditions = { Conditions(connected, unmetered, charging, false, connected && validated) },
            visible = { visible },
            backgroundLimited = { limited },
            scheduleUidt = {
                uidtCalls++
                uidtAllowed
            },
            enqueue = { policy, request -> enqueued += policy to request },
            armWake = { _, request -> wakes += request },
            runtimeWake = { pokes++ },
            waitingNotice = { notices += it },
            clock = clock,
            registry = registry,
            sdk = Build.VERSION.SDK_INT,
        )

    suspend fun ensure() = scheduler.ensureScheduled(DownloadLane.MANUAL)
}
