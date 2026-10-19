// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import android.os.Build
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 33, 34, 36])
class DownloadWorkersTest {
    private val context = RuntimeEnvironment.getApplication()
    private val fixture = SchedulerFixture()
    private val visibility = DownloadAppVisibility()
    private val requests = mutableListOf<DrainRequest>()
    private val stops = mutableListOf<StopIntent>()
    private var reconcile: suspend () -> Unit = {}
    private var drain: suspend (DrainRequest) -> DrainOutcome = { DrainOutcome.Empty }
    private val runtime =
        AndroidDownloadRuntime(drain = {
            requests += it
            drain(it)
        }, reconcile = { reconcile() }, stop = {
            _,
            intent,
            ->
            stops += intent
        }, wake = {}, controller = { FakeDownloadController() })

    @Test fun backgroundHasEightMinuteDeadline() =
        runTest {
            val worker = worker()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(
                fixture.clock.elapsedRealtime() + DownloadLaneWorker.SOFT_DEADLINE_MS,
                requests.single().softDeadlineElapsed,
            )
            assertEquals(setOf(DownloadLane.MANUAL), requests.single().lanes)
            assertEquals(LaneRegistry.Phase.IDLE, fixture.registry.phase())
        }

    @Test fun visiblePromotionAllowedOnlyBefore34() =
        runTest {
            visibility.update(true)
            val worker = worker()
            var promotions = 0
            worker.promote = { promotions++ }
            worker.doWork()
            assertEquals(if (Build.VERSION.SDK_INT < 34) 1 else 0, promotions)
            assertEquals(
                if (Build.VERSION.SDK_INT <
                    34
                ) {
                    null
                } else {
                    DownloadLaneWorker.SOFT_DEADLINE_MS
                },
                requests.single().softDeadlineElapsed,
            )
        }

    @Test fun foregroundDenialFallsBackToDeadline() =
        runTest {
            visibility.update(true)
            val worker = worker()
            worker.promote = { throw IllegalStateException("FGS denied") }
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(DownloadLaneWorker.SOFT_DEADLINE_MS, requests.single().softDeadlineElapsed)
        }

    @Test fun foregroundCancellationIsNeverSwallowedAsDenial() =
        runTest {
            if (Build.VERSION.SDK_INT >= 34) return@runTest
            visibility.update(true)
            val worker = worker()
            worker.promote = { throw CancellationException("stop") }
            assertFailsWith<CancellationException> { worker.doWork() }
            assertTrue(requests.isEmpty())
            assertIs<StopIntent.RunnerStopped>(stops.single())
            assertTrue(fixture.registry.liveTokens().isEmpty())
        }

    @Test fun startupHeldReconcileDoesNotLosePendingOrLiveOwnership() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            reconcile = { gate.await() }
            fixture.registry.pending(java.util.UUID.randomUUID(), false)
            val worker = worker()
            val run = async(start = CoroutineStart.UNDISPATCHED) { worker.doWork() }
            assertEquals(LaneRegistry.Phase.ACTIVE, fixture.registry.phase())
            assertTrue(requests.isEmpty())
            gate.complete(Unit)
            assertEquals(ListenableWorker.Result.success(), run.await())
            assertEquals(1, requests.size)
        }

    @Test fun cancellationStopsOnlyThisTokenAndUnregistersAfterCleanup() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            drain = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val worker = worker()
            val run = async { worker.doWork() }
            entered.await()
            assertEquals(1, fixture.registry.liveTokens().size)
            run.cancel()
            run.join()
            assertIs<StopIntent.RunnerStopped>(stops.single())
            assertTrue(fixture.registry.liveTokens().isEmpty())
        }

    @Test fun pre31ExpeditedForegroundInfoSurvivesVisibilityChange() =
        runTest {
            if (Build.VERSION.SDK_INT >= 31) return@runTest
            val worker = worker()
            val info = worker.getForegroundInfo()
            assertEquals(DownloadNotifications.PROGRESS_ID, info.notificationId)
            visibility.update(false)
            worker.doWork()
            assertNull(requests.single().softDeadlineElapsed)
        }

    @Test fun api34NeverProvidesDataSyncInfo() =
        runTest {
            if (Build.VERSION.SDK_INT < 34) return@runTest
            assertFailsWith<IllegalStateException> { worker().getForegroundInfo() }
        }

    @Test fun uidtTakesFutureClaimsWithoutCancellingWorker() =
        runTest {
            fixture.rows += downloadRow()
            drain = { request ->
                assertTrue(request.claimsAllowed())
                fixture.registry.uidtStarted(RunnerToken("uidt:other"))
                assertFalse(request.claimsAllowed())
                assertTrue(request.exit.tryClose(request.runnerToken, DrainOutcome.Empty))
                DrainOutcome.Empty
            }
            worker().doWork()
            assertTrue(stops.isEmpty())
            assertTrue(fixture.enqueued.isEmpty())
        }

    private fun worker(): DownloadLaneWorker =
        TestListenableWorkerBuilder<DownloadLaneWorker>(context)
            .setInputData(androidx.work.workDataOf(AndroidDownloadWork.KEY_LANE to AndroidDownloadWork.MANUAL))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        DownloadLaneWorker(
                            appContext,
                            workerParameters,
                            lazy { fixture.scheduler },
                            lazy { runtime },
                            lazy { DownloadNotifications(context, fixture.clock) },
                            visibility,
                            fixture.clock,
                        )
                },
            ).build()
}
