// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `WorkManagerRefreshScheduler` against the WorkManager test backend (03 Work requests /
 * Periodic tick): unique-work names and policies, scope encoding, the 60-min floor, manual-only
 * cancellation, Wi-Fi-only constraints, the >500-id fallback and expedited gating by API level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshSchedulerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private lateinit var db: NeutrodyneDatabase
    private lateinit var settings: FakeSettingsRepository

    private val workManager get() = WorkManager.getInstance(app)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        db = newDb(app, clock)
        settings = FakeSettingsRepository()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the periodic tick enqueues refresh-periodic with the interval`() =
        runTest {
            val scheduler = scheduler()
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 240)

            scheduler.reschedulePeriodic()

            val info = uniqueWork(WorkManagerRefreshScheduler.WORK_PERIODIC)
            assertThat(info.periodicityInfo?.repeatIntervalMillis).isEqualTo(240 * MINUTE_MS)
            assertThat(info.periodicityInfo?.flexIntervalMillis).isEqualTo(80 * MINUTE_MS)
            assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
            assertThat(info.constraints.requiresBatteryNotLow()).isTrue()
            assertThat(info.tags).contains(WorkManagerRefreshScheduler.TAG_REFRESH)
            assertThat(settings.get(FeedsSettingKeys.SCHEDULED_TICK_MINUTES)).isEqualTo(240L)
        }

    @Test
    fun `the tick clamps to the sixty minute floor`() =
        runTest {
            val scheduler = scheduler()
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 30)

            scheduler.reschedulePeriodic()

            val info = uniqueWork(WorkManagerRefreshScheduler.WORK_PERIODIC)
            assertThat(info.periodicityInfo?.repeatIntervalMillis).isEqualTo(60 * MINUTE_MS)
            assertThat(settings.get(FeedsSettingKeys.SCHEDULED_TICK_MINUTES)).isEqualTo(60L)
        }

    @Test
    fun `manual only cancels the periodic work`() =
        runTest {
            val scheduler = scheduler()
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 240)
            scheduler.reschedulePeriodic()

            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 0)
            scheduler.reschedulePeriodic()

            val infos = workManager.getWorkInfosForUniqueWork(WorkManagerRefreshScheduler.WORK_PERIODIC).get()
            assertThat(infos.map { it.state }).containsNoneOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)
            assertThat(settings.get(FeedsSettingKeys.SCHEDULED_TICK_MINUTES)).isEqualTo(-1L)
        }

    @Test
    fun `an unchanged spec does not re-enqueue`() =
        runTest {
            val scheduler = scheduler()
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 240)
            scheduler.reschedulePeriodic()
            val first = uniqueWork(WorkManagerRefreshScheduler.WORK_PERIODIC)

            scheduler.reschedulePeriodic()

            val second = uniqueWork(WorkManagerRefreshScheduler.WORK_PERIODIC)
            assertThat(second.id).isEqualTo(first.id)
        }

    @Test
    fun `wifi only makes the tick unmetered`() =
        runTest {
            val scheduler = scheduler()
            settings.set(FeedsSettingKeys.REFRESH_WIFI_ONLY, true)

            scheduler.reschedulePeriodic()

            val info = uniqueWork(WorkManagerRefreshScheduler.WORK_PERIODIC)
            assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        }

    @Test
    fun `enqueueNow encodes the scope and flags`() =
        runTest {
            val scheduler = scheduler()

            scheduler.enqueueNow(
                RefreshScope.Podcasts(listOf(3L, 7L)),
                force = true,
                pagesOnly = false,
                origin = RefreshOrigin.MANUAL,
            )

            val info = uniqueWork(WorkManagerRefreshScheduler.WORK_NOW)
            val spec = specOf(info.id.toString())
            val request = RefreshWorkData.request(spec.input, 0, 0, 0)
            assertThat(request.scope).isEqualTo(RefreshScope.Podcasts(listOf(3L, 7L)))
            assertThat(request.force).isTrue()
            assertThat(request.origin).isEqualTo(RefreshOrigin.MANUAL)
            // Immediate work is network-only; the periodic tick carries the battery constraint.
            assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
            assertThat(spec.constraints.requiresBatteryNotLow()).isFalse()
            assertThat(spec.workerClassName).isEqualTo(RefreshWorker::class.java.name)
        }

    @Test
    fun `enqueueNow groups encode their group id`() =
        runTest {
            val scheduler = scheduler()

            scheduler.enqueueNow(RefreshScope.Group(9), force = false, pagesOnly = false, origin = RefreshOrigin.MANUAL)

            val request = RefreshWorkData.request(specOf(uniqueWork(WorkManagerRefreshScheduler.WORK_NOW).id.toString()).input, 0, 0, 0)
            assertThat(request.scope).isEqualTo(RefreshScope.Group(9))
        }

    @Test
    fun `a scope above the id cap persists due marks and enqueues an all run`() =
        runTest {
            val scheduler = scheduler()
            val p1 = seedPodcast(db, "https://a.test/feed.xml", nextRefreshAt = FAR_FUTURE)
            val p2 = seedPodcast(db, "https://b.test/feed.xml", nextRefreshAt = FAR_FUTURE)
            val ids = listOf(p1, p2) + (1_000L until 1_000L + RefreshWorkData.MAX_SCOPE_IDS)

            scheduler.enqueueNow(RefreshScope.Podcasts(ids), force = true, pagesOnly = false, origin = RefreshOrigin.MANUAL)
            advanceUntilIdle()

            // `Data` caps at 10 KB: the run degrades to All and the rows carry the due marks.
            // The enqueue rides the app scope after the DAO write, which Room dispatches off the
            // test scheduler — wait for it in real time.
            val request = RefreshWorkData.request(specOf(awaitWork(WorkManagerRefreshScheduler.WORK_NOW).id.toString()).input, 0, 0, 0)
            assertThat(request.scope).isEqualTo(RefreshScope.All)
            assertThat(request.origin).isEqualTo(RefreshOrigin.MANUAL)
            assertThat(db.podcastDao().byId(p1)?.nextRefreshAt).isEqualTo(0L)
            assertThat(db.podcastDao().byId(p2)?.nextRefreshAt).isEqualTo(0L)
        }

    @Test
    fun `enqueueNow chains the newest request behind the pending one`() =
        runTest {
            val scheduler = scheduler()

            scheduler.enqueueNow(RefreshScope.Podcasts(listOf(1L)), force = false, pagesOnly = false, origin = RefreshOrigin.MANUAL)
            scheduler.enqueueNow(RefreshScope.Podcasts(listOf(2L)), force = true, pagesOnly = false, origin = RefreshOrigin.MANUAL)

            // APPEND_OR_REPLACE treats an unstarted predecessor as finished-enough to keep: the
            // earlier user request stays ENQUEUED and the newest waits BLOCKED behind it.
            val infos = workManager.getWorkInfosForUniqueWork(WorkManagerRefreshScheduler.WORK_NOW).get()
            val enqueued = infos.single { it.state == WorkInfo.State.ENQUEUED }
            val blocked = infos.single { it.state == WorkInfo.State.BLOCKED }
            assertThat(RefreshWorkData.request(specOf(enqueued.id.toString()).input, 0, 0, 0).scope)
                .isEqualTo(RefreshScope.Podcasts(listOf(1L)))
            assertThat(RefreshWorkData.request(specOf(blocked.id.toString()).input, 0, 0, 0).scope)
                .isEqualTo(RefreshScope.Podcasts(listOf(2L)))
        }

    @Test
    fun `enqueueContinuation keeps a single work with the linear backoff`() =
        runTest {
            val scheduler = scheduler()

            scheduler.enqueueContinuation()
            scheduler.enqueueContinuation()
            advanceUntilIdle()

            val infos = workManager.getWorkInfosForUniqueWork(WorkManagerRefreshScheduler.WORK_CONTINUATION).get()
            assertThat(infos).hasSize(1)
            val info = infos.single()
            assertThat(info.initialDelayMillis).isEqualTo(60_000L)
            val spec = specOf(info.id.toString())
            assertThat(spec.backoffPolicy).isEqualTo(BackoffPolicy.LINEAR)
            assertThat(spec.backoffDelayDuration).isEqualTo(60_000L)
            val request = RefreshWorkData.request(spec.input, 0, 0, 0)
            assertThat(request.origin).isEqualTo(RefreshOrigin.CONTINUATION)
            assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
            assertThat(spec.constraints.requiresBatteryNotLow()).isTrue()
        }

    @Test
    fun `requestFirstFetch enqueues import-sync with the sync origin`() =
        runTest {
            val scheduler = scheduler()

            scheduler.requestFirstFetch()

            val spec = specOf(uniqueWork(WorkManagerRefreshScheduler.WORK_IMPORT_SYNC).id.toString())
            val request = RefreshWorkData.request(spec.input, 0, 0, 0)
            assertThat(request.origin).isEqualTo(RefreshOrigin.SYNC)
            assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        }

    @Test
    fun `reschedulePeriodic rebases next refresh at`() =
        runTest {
            val scheduler = scheduler()
            val success = TestClock.DEFAULT_NOW - 24 * HOUR_MS
            val id =
                seedPodcast(
                    db,
                    "https://a.test/feed.xml",
                    subscribedAt = success - 30 * 24 * HOUR_MS,
                    lastSuccessAt = success,
                    nextRefreshAt = FAR_FUTURE,
                )
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 60)

            scheduler.reschedulePeriodic()
            advanceUntilIdle()

            assertThat(db.podcastDao().byId(id)?.nextRefreshAt).isEqualTo(success + 60 * MINUTE_MS)
        }

    @Test
    @Config(sdk = [31])
    fun `refresh-now is expedited on api 31`() =
        runTest {
            val scheduler = scheduler()

            scheduler.enqueueNow(RefreshScope.All, force = false, pagesOnly = false, origin = RefreshOrigin.MANUAL)

            val spec = specOf(uniqueWork(WorkManagerRefreshScheduler.WORK_NOW).id.toString())
            assertThat(spec.expedited).isTrue()
            assertThat(spec.outOfQuotaPolicy).isEqualTo(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

    @Test
    @Config(sdk = [30])
    fun `refresh-now is not expedited on api 30`() =
        runTest {
            val scheduler = scheduler()

            scheduler.enqueueNow(RefreshScope.All, force = false, pagesOnly = false, origin = RefreshOrigin.MANUAL)

            val spec = specOf(uniqueWork(WorkManagerRefreshScheduler.WORK_NOW).id.toString())
            assertThat(spec.expedited).isFalse()
        }

    // --- plumbing ---------------------------------------------------------------------------------

    private fun TestScope.scheduler() =
        WorkManagerRefreshScheduler(
            application = app,
            db = db,
            settings = settings,
            rebaser = NextRefreshRebaser(db, settings, clock, this),
            appScope = this,
        )

    private fun uniqueWork(name: String): WorkInfo =
        workManager.getWorkInfosForUniqueWork(name).get().single()

    /**
     * Waits for a work enqueue that rides the app scope behind a Room write: the DAO's dispatcher
     * hop posts the continuation back to the test scheduler, so drain it between real-time polls.
     */
    private suspend fun TestScope.awaitWork(name: String): WorkInfo {
        val deadlineMs = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadlineMs) {
            advanceUntilIdle()
            workManager.getWorkInfosForUniqueWork(name).get().singleOrNull()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("no work enqueued for $name within ${AWAIT_MS}ms")
    }

    /** The persisted spec: `WorkInfo` hides the input data, backoff and expedited flag. */
    private suspend fun specOf(id: String): WorkSpec =
        withContext(Dispatchers.IO) {
            checkNotNull((workManager as WorkManagerImpl).workDatabase.workSpecDao().getWorkSpec(id))
        }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val AWAIT_MS = 5_000L
        const val POLL_MS = 10L
        const val FAR_FUTURE = TestClock.DEFAULT_NOW + 365L * 24 * 60 * 60 * 1_000L
    }
}
