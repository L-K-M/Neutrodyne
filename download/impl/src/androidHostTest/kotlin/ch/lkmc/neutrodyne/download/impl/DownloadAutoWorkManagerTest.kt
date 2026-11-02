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
import ch.lkmc.neutrodyne.core.domain.DownloadMover
import ch.lkmc.neutrodyne.core.domain.MoveResult
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.core.testing.TestClock
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

/** Exercise unique names, UPDATE and APPEND_OR_REPLACE against WorkManager's database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33, 34, 36])
class DownloadAutoWorkManagerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val rows = CopyOnWriteArrayList<ch.lkmc.neutrodyne.core.database.DownloadEntity>()
    private val registry = LaneRegistry()
    private val queue = DownloadWorkQueue(app)
    private val requests = CopyOnWriteArrayList<DrainRequest>()
    private val store = AndroidDownloadTestStore()
    private val moves = CopyOnWriteArrayList<Pair<String, Long?>>()
    private var dailyCalls = 0
    private val scheduler =
        AndroidDownloadScheduler(
            rows = { rows.toList() },
            markForeground = { _, _ -> error("AUTO cannot wait for foreground") },
            conditions = { Conditions(true, true, true, false, true) },
            visible = { true },
            backgroundLimited = { true },
            scheduleUidt = { error("AUTO cannot schedule UIDT") },
            enqueue = queue::lane,
            armWake = { lane, request -> if (request == null) queue.cancelWake(lane) else queue.wake(lane, request) },
            runtimeWake = {},
            waitingNotice = {},
            clock = clock,
            registry = registry,
            sdk = android.os.Build.VERSION.SDK_INT,
        )
    private val runtime =
        AndroidDownloadRuntime(
            drain = { request ->
                requests += request
                rows.removeAll { it.lane in request.lanes }
                request.exit.tryClose(request.runnerToken, DrainOutcome.Empty)
                DrainOutcome.Empty
            },
            reconcile = {},
            stop = { _, _ -> },
            wake = {},
            controller = { FakeDownloadController() },
            daily = { dailyCalls++ },
        )
    private val manager get() = WorkManager.getInstance(app)
    private val driver get() = checkNotNull(WorkManagerTestInitHelper.getTestDriver(app))
    private val autoName get() = AndroidDownloadWork.laneName(DownloadLane.AUTO)
    private val wakeName get() = AndroidDownloadWork.wakeName(DownloadLane.AUTO)

    @Test fun autoUniqueLaneCoalescesAndLaterWorkSurvivesCompletion() =
        runBlocking {
            initialize()
            rows += downloadRow().copy(lane = DownloadLane.AUTO)
            scheduler.ensureScheduled(DownloadLane.AUTO)
            repeat(50) { scheduler.ensureScheduled(DownloadLane.AUTO) }
            val first = manager.getWorkInfosForUniqueWork(autoName).get().single()
            assertEquals(WorkInfo.State.ENQUEUED, first.state)
            assertTrue(manager.getWorkInfosForUniqueWork(AndroidDownloadWork.LANE).get().isEmpty())
            assertTrue(requests.isEmpty())
            driver.setAllConstraintsMet(first.id)
            awaitState(first.id, WorkInfo.State.SUCCEEDED)
            assertEquals(setOf(DownloadLane.AUTO), requests.single().lanes)
            assertEquals(DownloadLaneWorker.SOFT_DEADLINE_MS, requests.single().softDeadlineElapsed)
            rows += downloadRow(2).copy(lane = DownloadLane.AUTO)
            scheduler.ensureScheduled(DownloadLane.AUTO)
            val next = manager.getWorkInfosForUniqueWork(autoName).get().single { it.id != first.id }
            driver.setAllConstraintsMet(next.id)
            awaitState(next.id, WorkInfo.State.SUCCEEDED)
            assertEquals(2, requests.size)
            assertTrue(scheduler.liveTokens().isEmpty())
        }

    @Test fun autoBackoffWakeRearmsOnlyAutoAfterDelayAndConstraints() =
        runBlocking {
            initialize()
            rows += downloadRow().copy(lane = DownloadLane.AUTO, nextAttemptAt = clock.now() + 60_000)
            scheduler.ensureScheduled(DownloadLane.AUTO)
            assertTrue(manager.getWorkInfosForUniqueWork(autoName).get().isEmpty())
            assertTrue(manager.getWorkInfosForUniqueWork(AndroidDownloadWork.WAKE).get().isEmpty())
            val wake = manager.getWorkInfosForUniqueWork(wakeName).get().single()
            driver.setAllConstraintsMet(wake.id)
            assertTrue(requests.isEmpty())
            clock.nowMs += 60_000
            driver.setInitialDelayMet(wake.id)
            withTimeout(AWAIT_MS) {
                while (manager.getWorkInfosForUniqueWork(autoName).get().isEmpty()) delay(5)
            }
            val lane = manager.getWorkInfosForUniqueWork(autoName).get().single()
            driver.setAllConstraintsMet(lane.id)
            awaitState(lane.id, WorkInfo.State.SUCCEEDED)
            assertEquals(setOf(DownloadLane.AUTO), requests.single().lanes)
        }

    @Test fun periodicCleanupUpdateKeepsOneScheduleAndRunsNextPeriod() =
        runBlocking {
            initialize()
            queue.cleanup()
            val first = manager.getWorkInfosForUniqueWork(AndroidDownloadWork.CLEANUP).get().single()
            queue.cleanup()
            assertEquals(
                first.id,
                manager
                    .getWorkInfosForUniqueWork(AndroidDownloadWork.CLEANUP)
                    .get()
                    .single()
                    .id,
            )
            driver.setAllConstraintsMet(first.id)
            withTimeout(AWAIT_MS) { while (dailyCalls != 1) delay(5) }
            awaitState(first.id, WorkInfo.State.ENQUEUED)
            driver.setPeriodDelayMet(first.id)
            // TestDriver resets constraint satisfaction after every periodic execution.
            driver.setAllConstraintsMet(first.id)
            withTimeout(AWAIT_MS) { while (dailyCalls != 2) delay(5) }
            assertTrue(requests.isEmpty())
        }

    @Test fun moveDeadlineAppendsSuccessorBehindRunningUniqueWork() =
        runBlocking {
            initialize()
            store.set(DownloadSettingKeys.MOVE_TARGET, "ext:card")
            queue.move("ext:card")
            val first = manager.getWorkInfosForUniqueWork(DownloadMoveWorker.NAME).get().single()
            driver.setAllConstraintsMet(first.id)
            awaitState(first.id, WorkInfo.State.SUCCEEDED)
            val next = manager.getWorkInfosForUniqueWork(DownloadMoveWorker.NAME).get().single { it.id != first.id }
            driver.setAllConstraintsMet(next.id)
            awaitState(next.id, WorkInfo.State.SUCCEEDED)
            assertEquals(listOf<Pair<String, Long?>>("ext:card" to 480_000L, "ext:card" to 480_000L), moves)
            assertEquals(2, manager.getWorkInfosForUniqueWork(DownloadMoveWorker.NAME).get().size)
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
                                        lazy { error("AUTO cannot use notifications") },
                                        DownloadAppVisibility().apply { update(true) },
                                        clock,
                                    )
                                }

                                DownloadWakeWorker::class.java.name -> {
                                    DownloadWakeWorker(appContext, workerParameters, lazy { scheduler })
                                }

                                CleanupWorker::class.java.name -> {
                                    CleanupWorker(appContext, workerParameters, lazy { runtime })
                                }

                                DownloadMoveWorker::class.java.name -> {
                                    DownloadMoveWorker(
                                        appContext,
                                        workerParameters,
                                        object : DownloadMover {
                                            override suspend fun run(
                                                target: String,
                                                deadline: Long?,
                                            ): MoveResult {
                                                moves += target to deadline
                                                return if (moves.size == 1) MoveResult.DEADLINE else MoveResult.COMPLETE
                                            }
                                        },
                                        store,
                                        clock,
                                    )
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
