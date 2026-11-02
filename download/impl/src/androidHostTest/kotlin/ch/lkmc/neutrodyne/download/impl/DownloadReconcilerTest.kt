// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.Build
import ch.lkmc.neutrodyne.core.domain.ProcessExit
import ch.lkmc.neutrodyne.core.domain.ProcessExitReason
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.FakeExitReasonProbe
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the real Android probes and Room through reconcile's startup claim barrier. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class DownloadReconcilerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = TestDb.inMemory(context)
    private val dao = db.downloadDao()
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val scheduler =
        context.getSystemService(JobScheduler::class.java).let {
            if (Build.VERSION.SDK_INT >= 34) it.forNamespace(JobSchedulerFacade.NAMESPACE) else it
        }

    // Robolectric stores jobs per wrapper; reuse the downloads wrapper for scheduling and lookup.
    private val jobs = JobSchedulerFacade(context) { scheduler }
    private val probe = AndroidExitReasonProbe(context)
    private val mutex = Mutex()
    private var live = emptySet<String>()
    private val roots =
        object : StorageRoots {
            override suspend fun current(): String = error("Reset does not access storage")

            override suspend fun list(): List<StorageRootInfo> = error("Reset does not access storage")

            override suspend fun resolve(rootId: String): ResolvedRoot? = error("Reset does not access storage")

            override suspend fun ensureSpace(
                rootId: String,
                requiredBytes: Long,
            ): StorageFailure? = error("No IO")

            override suspend fun classify(
                error: Throwable,
                rootId: String,
            ): StorageFailure = error("No IO")
        }
    private val files = OkioDownloadFileSystem(roots)
    private val reset = AndroidTaskManagerReset(dao, probe, jobs)
    private val reconciler =
        DownloadReconciler(
            db,
            files,
            roots,
            PlatformLocalUris(),
            LocalMediaIndexImpl(),
            DeferredDeletes(AndroidDownloadTestStore(), files, roots, dao::allRows),
            mutex,
            { live },
            TestClock(),
            beforeRunnerReset = {
                assertTrue(mutex.isLocked)
                reset.reset(it)
            },
        )

    @After fun close() {
        scheduler.cancelAll()
        db.close()
    }

    @Test fun newerUserExitWithoutJobPausesStoppedManualJobAndItsQueue() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, EXIT_AT)
            val uidt = insert(DownloadState.DOWNLOADING, "uidt:$STARTED_AT:invocation")
            val resolving = insert(DownloadState.RESOLVING, "uidt:$STARTED_AT:invocation")
            val verifying = insert(DownloadState.VERIFYING, "uidt:$STARTED_AT:invocation")
            val queued = insert(DownloadState.QUEUED, null, wait = WaitReason.SYSTEM)
            val worker = insert(DownloadState.DOWNLOADING, "work:manual:$STARTED_AT")
            val autoUidt = insert(DownloadState.DOWNLOADING, "uidt:$STARTED_AT", DownloadLane.AUTO)
            val autoQueued = insert(DownloadState.QUEUED, null, DownloadLane.AUTO)
            val nullOwner = insert(DownloadState.RESOLVING, null)
            live = setOf("uidt:$EXIT_AT:live", "work:live:$EXIT_AT")
            val liveUidt = insert(DownloadState.DOWNLOADING, live.first())
            val liveWorker = insert(DownloadState.RESOLVING, live.last())
            val alreadyPaused = insert(DownloadState.PAUSED, null)
            val completed = insert(DownloadState.COMPLETED, null)

            reconciler.resetOrphanedRunners()

            for (id in listOf(uidt, resolving, verifying, queued)) {
                val row = assertNotNull(dao.byEpisode(id))
                assertEquals(DownloadState.PAUSED, row.state)
                assertEquals(WaitReason.NONE, row.waitReason)
                assertEquals(DownloadStopReasons.USER_TASK_MANAGER, row.lastStopReason)
                assertNull(row.runnerToken)
            }
            for (id in listOf(worker, autoUidt, nullOwner)) assertSystemReset(id)
            assertEquals(DownloadState.QUEUED, dao.byEpisode(autoQueued)?.state)
            assertNull(dao.byEpisode(autoQueued)?.lastStopReason)
            assertEquals(DownloadState.DOWNLOADING, dao.byEpisode(liveUidt)?.state)
            assertEquals(DownloadState.RESOLVING, dao.byEpisode(liveWorker)?.state)
            assertEquals(DownloadState.PAUSED, dao.byEpisode(alreadyPaused)?.state)
            assertNull(dao.byEpisode(alreadyPaused)?.lastStopReason)
            assertEquals(DownloadState.COMPLETED, dao.byEpisode(completed)?.state)
            assertNull(dao.byEpisode(completed)?.lastStopReason)
            val stopped = assertNotNull(dao.byEpisode(uidt))
            assertEquals(42L, stopped.downloadedBytes)
            assertEquals("strong-etag", stopped.etag)
            assertEquals(".partial/owned.part", stopped.tempPath)
            assertEquals(3, stopped.attempt)
            assertEquals(9000L, stopped.nextAttemptAt)
        }

    @Test fun pendingPersistedManualJobKeepsRecentsSwipeOnSystemRecoveryPath() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, EXIT_AT)
            val uidt = insert(DownloadState.DOWNLOADING, "uidt:$STARTED_AT")
            val queued = insert(DownloadState.QUEUED, null)
            assertEquals(
                JobScheduler.RESULT_SUCCESS,
                scheduler.schedule(jobs.info(listOf(assertNotNull(dao.byEpisode(queued))))),
            )
            assertNotNull(shadowOf(scheduler).getPendingJob(JobSchedulerFacade.JOB_ID))

            reconciler.resetOrphanedRunners()

            assertSystemReset(uidt)
            assertEquals(DownloadState.QUEUED, dao.byEpisode(queued)?.state)
            assertNull(dao.byEpisode(queued)?.lastStopReason)
        }

    @Test fun unrelatedPendingJobDoesNotHideRemovalOfManualJob() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, EXIT_AT)
            val uidt = insert(DownloadState.RESOLVING, "uidt:$STARTED_AT")
            val unrelated =
                JobInfo
                    .Builder(
                        JobSchedulerFacade.JOB_ID + 1,
                        ComponentName(context, ManualDownloadJobService::class.java),
                    ).build()
            assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(unrelated))

            reconciler.resetOrphanedRunners()

            assertEquals(DownloadState.PAUSED, dao.byEpisode(uidt)?.state)
        }

    @Test fun olderUserExitDoesNotPauseANewerTransfer() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, STARTED_AT - 1)
            assertOrdinaryRecovery()
        }

    @Test fun equalTimestampDoesNotCountAsAnExitAfterTheTransferStarted() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, STARTED_AT)
            assertOrdinaryRecovery()
        }

    @Test fun processKillDoesNotPauseEvenWithNoPendingJob() =
        runTest {
            exit(ApplicationExitInfo.REASON_SIGNALED, EXIT_AT)
            assertOrdinaryRecovery()
        }

    @Test fun secondaryUserStopIsNotTaskManagerStop() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_STOPPED, EXIT_AT)
            assertOrdinaryRecovery()
        }

    @Test fun packageUpdateDoesNotPause() =
        runTest {
            exit(ApplicationExitInfo.REASON_PACKAGE_UPDATED, EXIT_AT)
            assertOrdinaryRecovery()
        }

    @Test fun absentHistoryDoesNotPause() =
        runTest {
            assertNull(probe.latest())
            assertOrdinaryRecovery()
        }

    @Test fun onlyLatestExitIsUsedEvenWhenEarlierExitWasUserRequested() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, EXIT_AT)
            exit(ApplicationExitInfo.REASON_CRASH, EXIT_AT + 1)
            assertEquals(ProcessExit(ProcessExitReason.OTHER, EXIT_AT + 1), probe.latest())
            assertOrdinaryRecovery()
        }

    @Test fun latestExitRetainsTimestampAndIncludesOtherProcessIds() {
        // pid=0 is deliberate: the previous process need not have this process's PID.
        exit(ApplicationExitInfo.REASON_USER_REQUESTED, EXIT_AT)
        assertEquals(ProcessExit(ProcessExitReason.USER_REQUESTED, EXIT_AT), probe.latest())
    }

    @Test fun noStaleManualUidtOwnerMeansQueuedManualIsNotPaused() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, EXIT_AT)
            live = setOf("uidt:$STARTED_AT")
            val liveId = insert(DownloadState.DOWNLOADING, live.single())
            val worker = insert(DownloadState.DOWNLOADING, "work:manual:$STARTED_AT")
            val auto = insert(DownloadState.DOWNLOADING, "uidt:999", DownloadLane.AUTO)
            val queued = insert(DownloadState.QUEUED, null)

            reconciler.resetOrphanedRunners()

            assertEquals(DownloadState.DOWNLOADING, dao.byEpisode(liveId)?.state)
            assertSystemReset(worker)
            assertSystemReset(auto)
            assertEquals(DownloadState.QUEUED, dao.byEpisode(queued)?.state)
            assertNull(dao.byEpisode(queued)?.lastStopReason)
        }

    @Test fun malformedUidtTimesCannotEstablishUserIntent() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, EXIT_AT)
            val ids =
                listOf("uidt:", "uidt:bad:invocation", "uidt:9223372036854775808", "uidt:-1").map {
                    insert(DownloadState.DOWNLOADING, it)
                }
            val queued = insert(DownloadState.QUEUED, null)

            reconciler.resetOrphanedRunners()

            ids.forEach { assertSystemReset(it) }
            assertEquals(DownloadState.QUEUED, dao.byEpisode(queued)?.state)
        }

    @Test fun resetRunsOnceSoLaterManualRequestsAreNotPausedByOldHistory() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, EXIT_AT)
            val uidt = insert(DownloadState.DOWNLOADING, "uidt:$STARTED_AT")
            reconciler.resetOrphanedRunners()
            val newRequest = insert(DownloadState.QUEUED, null)

            reconciler.resetOrphanedRunners()

            assertEquals(DownloadState.PAUSED, dao.byEpisode(uidt)?.state)
            assertEquals(DownloadState.QUEUED, dao.byEpisode(newRequest)?.state)
        }

    @Test
    @Config(sdk = [33])
    fun api33DoesNotInferUserStopEvenWithUserRequestedHistory() =
        runTest {
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, EXIT_AT)
            assertNull(probe.latest())
            assertOrdinaryRecovery()
            insert(DownloadState.DOWNLOADING, "uidt:$STARTED_AT")
            val before = dao.allRows()
            val fake = FakeExitReasonProbe().apply { exit = ProcessExit(ProcessExitReason.USER_REQUESTED, EXIT_AT) }
            // The policy itself is gated, even if another probe were to return a user exit below 34.
            AndroidTaskManagerReset(
                dao,
                fake,
                JobSchedulerFacade(context) { error("No UIDT below API 34") },
            ).reset(emptySet())
            assertEquals(before, dao.allRows())
        }

    private suspend fun assertOrdinaryRecovery() {
        val uidt = insert(DownloadState.DOWNLOADING, "uidt:$STARTED_AT:invocation")
        val queued = insert(DownloadState.QUEUED, null)
        reconciler.resetOrphanedRunners()
        assertSystemReset(uidt)
        assertEquals(DownloadState.QUEUED, dao.byEpisode(queued)?.state)
        assertNull(dao.byEpisode(queued)?.lastStopReason)
    }

    private suspend fun assertSystemReset(id: Long) {
        val row = assertNotNull(dao.byEpisode(id))
        assertEquals(DownloadState.QUEUED, row.state)
        assertEquals(WaitReason.SYSTEM, row.waitReason)
        assertEquals(DownloadStopReasons.PROCESS_DEATH, row.lastStopReason)
        assertEquals(DownloadError.CANCELLED_BY_SYSTEM, row.lastError)
        assertNull(row.runnerToken)
    }

    private fun exit(
        reason: Int,
        at: Long,
    ) {
        shadowOf(manager).addApplicationExitInfo(
            ApplicationExitInfoBuilder
                .newBuilder()
                .setProcessName(context.packageName)
                .setPid(12345)
                .setReason(reason)
                .setTimestamp(at)
                .build(),
        )
    }

    private suspend fun insert(
        state: DownloadState,
        token: String?,
        lane: DownloadLane = DownloadLane.MANUAL,
        wait: WaitReason = WaitReason.NONE,
    ): Long {
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        val id = db.episodeDao().insertStub(episodeEntity(podcastId = podcast))
        dao.insert(
            downloadEntity(id, state).copy(
                lane = lane,
                runnerToken = token,
                waitReason = wait,
                downloadedBytes = 42,
                etag = "strong-etag",
                tempPath = ".partial/owned.part",
                attempt = 3,
                nextAttemptAt = 9000,
            ),
        )
        return id
    }

    private companion object {
        const val STARTED_AT = 1000L
        const val EXIT_AT = 2000L
    }
}
