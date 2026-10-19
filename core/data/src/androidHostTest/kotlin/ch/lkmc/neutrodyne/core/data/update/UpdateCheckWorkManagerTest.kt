// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.testing.FakeAppUpdateChecker
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys as Keys

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UpdateCheckWorkManagerTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = FakeSettingsRepository()
    private val source = TestUpdateSource()
    private val store = MemoryUpdateStore()
    private val notices = TestUpdateNotifications()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val clock = TestClock()
    private lateinit var checker: AppUpdateCheckerImpl
    private val runner = WorkUpdateCheckRunner(application, { checker })
    private val workManager get() = WorkManager.getInstance(application)
    private val driver get() = checkNotNull(WorkManagerTestInitHelper.getTestDriver(application))

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration
                .Builder()
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? =
                            if (workerClassName == UpdateCheckWorker::class.qualifiedName) {
                                UpdateCheckWorker(appContext, workerParameters, { checker })
                            } else {
                                null
                            }
                    },
                ).build(),
        )
        checker = newChecker()
    }

    @After fun cleanup() {
        workManager.cancelAllWork().result.get()
        scope.cancel()
    }

    private fun newChecker(debug: Boolean = false) =
        AppUpdateCheckerImpl(
            updateBuild(debug = debug),
            runtime(),
            settings,
            source,
            store,
            DesktopAssetSelector(),
            notices,
            { runner },
            network,
            clock,
            UpdateJitter { 0 },
            scope,
        )

    @Test fun periodicSpecHasFlexDelayNetworkAndHourlyBackoffAndUpdateKeepsIdentity() =
        runBlocking<Unit> {
            checker.initialize()
            val info = unique(UPDATE_PERIODIC_WORK)
            val spec = spec(info.id)
            assertEquals(DAY, spec.intervalDuration)
            assertEquals(6 * HOUR, spec.flexDuration)
            assertEquals(DAY, spec.initialDelay)
            assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
            assertEquals(HOUR, spec.backoffDelayDuration)
            runner.setAutomaticChecks(UpdateScheduling.ENABLED)
            assertEquals(info.id, unique(UPDATE_PERIODIC_WORK).id)
            assertEquals(0, source.calls)
            driver.setInitialDelayMet(info.id)
            assertEquals(0, source.calls)
            driver.setAllConstraintsMet(info.id)
            awaitPeriod(info.id, 1)
            assertEquals(1, source.calls)
            assertEquals(1, notices.posted.size)
            driver.setPeriodDelayMet(info.id)
            driver.setAllConstraintsMet(info.id)
            awaitPeriod(info.id, 2)
            assertEquals(2, source.calls)
            assertEquals(1, notices.posted.size)
        }

    @Test fun scheduledErrorsRetryButManualRateLimitFinishesWithoutNetworkConstraintOrRetry() =
        runBlocking<Unit> {
            source.result = Outcome.Failure(UpdateCheckError.RATE_LIMITED)
            checker.initialize()
            val periodic = unique(UPDATE_PERIODIC_WORK)
            driver.setInitialDelayMet(periodic.id)
            driver.setAllConstraintsMet(periodic.id)
            awaitInfo(periodic.id) { it.runAttemptCount == 1 && it.state == WorkInfo.State.ENQUEUED }
            clock.elapsedMs += HOUR
            assertIs<UpdateCheckState.Failed>(checker.checkNow())
            val manual = unique(UPDATE_MANUAL_WORK)
            assertEquals(WorkInfo.State.SUCCEEDED, manual.state)
            assertEquals(NetworkType.NOT_REQUIRED, spec(manual.id).constraints.requiredNetworkType)
            // WorkInfo counts starts, including the single successful execution.
            assertEquals(1, manual.runAttemptCount)
            assertEquals(2, source.calls)
            assertTrue(notices.posted.isEmpty())
        }

    @Test fun offlineManualEnqueuesNothingAndManualWhileOffRunsOnce() =
        runBlocking<Unit> {
            settings.set(Keys.CHECK_ENABLED, false)
            checker.initialize()
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            assertIs<UpdateCheckState.Failed>(checker.checkNow())
            assertTrue(workManager.getWorkInfosForUniqueWork(UPDATE_MANUAL_WORK).get().isEmpty())
            assertEquals(0, source.calls)
            network.setStatus(FakeNetworkMonitor.ONLINE)
            assertIs<UpdateCheckState.Available>(checker.checkNow())
            checker.checkNow()
            assertEquals(1, source.calls)
            assertTrue(notices.posted.isEmpty())
            assertEquals(1000095L, settings.get(Keys.NOTIFIED_VERSION_CODE))
            assertTrue(workManager.getWorkInfosForUniqueWork(UPDATE_PERIODIC_WORK).get().none { !it.state.isFinished })
        }

    @Test fun freshWorkerLoadsOffChoiceAndDoesNotFetchQueuedWork() =
        runBlocking<Unit> {
            checker.initialize()
            val periodic = unique(UPDATE_PERIODIC_WORK)
            // Lose every observer while WorkManager retains its constrained request.
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            settings.set(Keys.CHECK_ENABLED, false)
            checker = newChecker()
            driver.setInitialDelayMet(periodic.id)
            driver.setAllConstraintsMet(periodic.id)
            awaitInfo(periodic.id) { it.state == WorkInfo.State.CANCELLED }
            assertEquals(0, source.calls)
            assertIs<UpdateCheckState.Disabled>(checker.state.value)
            settings.set(Keys.CHECK_ENABLED, true)
            withTimeout(10_000) { checker.state.first { it is UpdateCheckState.Idle } }
            assertEquals(0, source.calls)
        }

    @Test fun debugCancelsExistingPeriodicAndRefusesManualWork() =
        runBlocking<Unit> {
            runner.setAutomaticChecks(UpdateScheduling.ENABLED)
            checker = newChecker(debug = true)
            checker.initialize()
            assertIs<UpdateCheckState.Disabled>(checker.checkNow())
            assertEquals(WorkInfo.State.CANCELLED, unique(UPDATE_PERIODIC_WORK).state)
            assertTrue(workManager.getWorkInfosForUniqueWork(UPDATE_MANUAL_WORK).get().isEmpty())
            assertEquals(0, source.calls)
        }

    @Test fun switchingOffCancelsTheManualWorkAndItsGetWithoutPublishing() =
        runBlocking<Unit> {
            checker.initialize()
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            source.action = {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            val request = scope.async { checker.checkNow() }
            withTimeout(10_000) { started.await() }
            settings.set(Keys.CHECK_ENABLED, false)
            withTimeout(10_000) { stopped.await() }
            val terminal = awaitInfo(unique(UPDATE_MANUAL_WORK).id) { it.state.isFinished }
            // Coordinator cancellation may reach the worker before WorkManager's stop signal.
            assertTrue(terminal.state == WorkInfo.State.CANCELLED || terminal.state == WorkInfo.State.FAILED)
            withTimeout(10_000) { request.join() }
            assertIs<UpdateCheckState.Disabled>(checker.state.value)
            assertTrue(notices.posted.isEmpty())
        }

    @Test fun unavailableNotificationLeavesTheUpdateCardAndNoSuppressionMarker() =
        runBlocking<Unit> {
            notices.result = UpdatePostResult.UNAVAILABLE
            checker.initialize()
            val info = unique(UPDATE_PERIODIC_WORK)
            driver.setInitialDelayMet(info.id)
            driver.setAllConstraintsMet(info.id)
            awaitPeriod(info.id, 1)
            assertIs<UpdateCheckState.Available>(checker.state.value)
            assertEquals(Keys.ABSENT, settings.get(Keys.NOTIFIED_VERSION_CODE))
            notices.result = UpdatePostResult.ACCEPTED
            driver.setPeriodDelayMet(info.id)
            driver.setAllConstraintsMet(info.id)
            awaitPeriod(info.id, 2)
            assertEquals(1000095L, settings.get(Keys.NOTIFIED_VERSION_CODE))
        }

    @Test fun manualRunnerWaitsForResultWhenCheckingReappearsAfterWorkerCompletion() =
        runBlocking<Unit> {
            val state = FakeAppUpdateChecker(UpdateCheckState.Checking)
            val manualRunner = WorkUpdateCheckRunner(application, { state })
            val request = scope.async { manualRunner.runNow() }
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(10)
            while (workManager.getWorkInfosForUniqueWork(UPDATE_MANUAL_WORK).get().isEmpty()) {
                check(System.nanoTime() < deadline) { "Manual request was not enqueued" }
                Thread.sleep(10)
            }
            awaitInfo(unique(UPDATE_MANUAL_WORK).id) { it.state == WorkInfo.State.SUCCEEDED }
            // Model a periodic run claiming state between the completed worker and its caller.
            assertNull(withTimeoutOrNull(1_000) { request.await() })
            val result = UpdateCheckState.Idle(clock.now())
            state.state.value = result
            assertEquals(result, withTimeout(10_000) { request.await() })
        }

    private suspend fun awaitPeriod(
        id: UUID,
        count: Int,
    ) {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(10)
        while (spec(id).periodCount < count ||
            spec(id).scheduleRequestedAt == androidx.work.impl.model.WorkSpec.SCHEDULE_NOT_REQUESTED_YET
        ) {
            check(System.nanoTime() < deadline) { "Update period did not finish: ${spec(id)}" }
            Thread.sleep(10)
        }
    }

    private fun unique(name: String): WorkInfo = workManager.getWorkInfosForUniqueWork(name).get().single()

    private suspend fun spec(id: UUID) =
        withContext(Dispatchers.IO) {
            checkNotNull((workManager as WorkManagerImpl).workDatabase.workSpecDao().getWorkSpec(id.toString()))
        }

    private fun awaitInfo(
        id: UUID,
        predicate: (WorkInfo) -> Boolean,
    ): WorkInfo {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(10)
        while (System.nanoTime() < deadline) {
            val info = checkNotNull(workManager.getWorkInfoById(id).get())
            if (predicate(info)) return info
            Thread.sleep(10)
        }
        error("Update work stuck: ${workManager.getWorkInfoById(id).get()}")
    }
}
