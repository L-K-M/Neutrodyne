// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real WorkManager persistence, constraints and cancellation through its TestDriver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33, 34, 36])
class DownloadWorkManagerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val rows = CopyOnWriteArrayList<ch.lkmc.neutrodyne.core.database.DownloadEntity>()
    private val registry = LaneRegistry()
    private val requests = CopyOnWriteArrayList<DrainRequest>()
    private val stops = CopyOnWriteArrayList<StopIntent>()
    private var run: suspend (DrainRequest) -> DrainOutcome = { request ->
        rows.clear()
        request.exit.tryClose(request.runnerToken, DrainOutcome.Empty)
        DrainOutcome.Empty
    }
    private val queue = DownloadWorkQueue(app)
    private val scheduler =
        AndroidDownloadScheduler(
            rows = { rows.toList() },
            markForeground = { _, _ -> },
            conditions = { Conditions(true, true, true, false, true) },
            visible = { false },
            backgroundLimited = { false },
            scheduleUidt = { error("Invisible UIDT scheduling") },
            enqueue = queue::lane,
            armWake = { if (it == null) queue.cancelWake() else queue.wake(it) },
            runtimeWake = {},
            waitingNotice = {},
            clock = clock,
            registry = registry,
            sdk = android.os.Build.VERSION.SDK_INT,
        )
    private val runtime =
        AndroidDownloadRuntime(
            drain = {
                requests += it
                run(it)
            },
            reconcile = {},
            stop = { _, intent -> stops += intent },
            wake = {},
            controller = { FakeDownloadController() },
        )
    private val manager get() = WorkManager.getInstance(app)
    private val driver get() = checkNotNull(WorkManagerTestInitHelper.getTestDriver(app))

    @Test fun constraintsGateRealWorkerAndLaterEnqueueSurvivesReturningWorker() =
        runBlocking {
            initialize()
            rows += downloadRow()
            scheduler.ensureScheduled(DownloadLane.MANUAL)
            val first = manager.getWorkInfosForUniqueWork(AndroidDownloadWork.LANE).get().single()
            assertEquals(WorkInfo.State.ENQUEUED, first.state)
            assertTrue(requests.isEmpty())
            driver.setAllConstraintsMet(first.id)
            awaitState(first.id, WorkInfo.State.SUCCEEDED)
            rows += downloadRow(2)
            scheduler.ensureScheduled(DownloadLane.MANUAL)
            val next = manager.getWorkInfosForUniqueWork(AndroidDownloadWork.LANE).get().single { it.id != first.id }
            driver.setAllConstraintsMet(next.id)
            awaitState(next.id, WorkInfo.State.SUCCEEDED)
            assertEquals(2, requests.size)
            assertTrue(registry.liveTokens().isEmpty())
        }

    @Test fun allBackoffUsesDelayedWakeThenRearmsManualLane() =
        runBlocking {
            initialize()
            rows += downloadRow().copy(nextAttemptAt = clock.now() + 60_000)
            scheduler.ensureScheduled(DownloadLane.MANUAL)
            assertTrue(manager.getWorkInfosForUniqueWork(AndroidDownloadWork.LANE).get().isEmpty())
            val wake = manager.getWorkInfosForUniqueWork(AndroidDownloadWork.WAKE).get().single()
            driver.setAllConstraintsMet(wake.id)
            assertTrue(requests.isEmpty())
            clock.nowMs += 60_000
            driver.setInitialDelayMet(wake.id)
            withTimeout(AWAIT_MS) {
                while (manager.getWorkInfosForUniqueWork(AndroidDownloadWork.LANE).get().isEmpty()) delay(5)
            }
            val lane = manager.getWorkInfosForUniqueWork(AndroidDownloadWork.LANE).get().single()
            driver.setAllConstraintsMet(lane.id)
            awaitState(lane.id, WorkInfo.State.SUCCEEDED)
            assertEquals(1, requests.size)
        }

    @Test fun cancellationDeliversWorkManagerStopReasonAndCleansLiveToken() =
        runBlocking {
            initialize()
            val entered = CompletableDeferred<Unit>()
            var stopBeforeCleanup: StopIntent? = null
            run = { request ->
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopBeforeCleanup = request.cancellationIntent()
                }
            }
            rows += downloadRow()
            scheduler.ensureScheduled(DownloadLane.MANUAL)
            val lane = manager.getWorkInfosForUniqueWork(AndroidDownloadWork.LANE).get().single()
            driver.setAllConstraintsMet(lane.id)
            withTimeout(AWAIT_MS) { entered.await() }
            manager.cancelWorkById(lane.id).result.get()
            awaitState(lane.id, WorkInfo.State.CANCELLED)
            withTimeout(AWAIT_MS) { while (stops.isEmpty() || registry.liveTokens().isNotEmpty()) delay(5) }
            assertEquals(
                WorkInfo.STOP_REASON_CANCELLED_BY_APP,
                (stops.single() as StopIntent.RunnerStopped).platformStopReason,
            )
            assertEquals(stops.single(), stopBeforeCleanup)
        }

    private fun initialize() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setExecutor { it.run() }
                .setTaskExecutor { it.run() }
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? =
                            when (workerClassName) {
                                DownloadLaneWorker::class.java.name -> {
                                    DownloadLaneWorker(
                                        appContext,
                                        workerParameters,
                                        lazy { scheduler },
                                        lazy { runtime },
                                        lazy { DownloadNotifications(app, clock) },
                                        DownloadAppVisibility(),
                                        clock,
                                    )
                                }

                                DownloadWakeWorker::class.java.name -> {
                                    DownloadWakeWorker(appContext, workerParameters, lazy { scheduler })
                                }

                                else -> {
                                    null
                                }
                            }
                    },
                ).build(),
        )
    }

    private suspend fun awaitState(
        id: java.util.UUID,
        state: WorkInfo.State,
    ) {
        withTimeout(AWAIT_MS) { while (manager.getWorkInfoById(id).get()?.state != state) delay(5) }
    }

    private companion object {
        const val AWAIT_MS = 15_000L
    }
}
