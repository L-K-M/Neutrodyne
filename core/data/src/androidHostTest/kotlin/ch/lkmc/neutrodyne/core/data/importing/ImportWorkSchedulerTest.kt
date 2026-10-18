// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkName
import androidx.work.impl.model.WorkSpec
import androidx.work.impl.model.WorkTag
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.data.backup.RestoreWorkData
import ch.lkmc.neutrodyne.core.data.backup.RestoreWorker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * `WorkManagerImportWorkScheduler` over a real `WorkManager` test backend (05 §6/§7 work
 * table, Restore arbitration): the fetch lane's unique names, network constraint, expedited
 * quota fallback and KEEP/APPEND_OR_REPLACE policies, and `scheduleRestore`'s typed
 * acknowledgment — proven against stored `WorkInfo`, not a trusting API answer. The workers
 * never run here; every assertion reads the spec database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportWorkSchedulerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val workManager get() = WorkManager.getInstance(app)
    private val scheduler = WorkManagerImportWorkScheduler(app)

    @Before
    fun setUp() {
        gate = CompletableDeferred()
        // The test backend executes unconstrained specs immediately: every worker maps to a
        // gated CoroutineWorker that parks RUNNING, so a spec's unfinished/finished state is
        // the test's choice — never a reflective-instantiation failure.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setWorkerFactory(GatedWorkerFactory()).build(),
        )
    }

    @After
    fun tearDown() {
        gate.complete(Unit)
        workManager.cancelAllWork()
    }

    @Test
    fun `the fetch lane enqueues unique constrained work per session`() =
        runTest {
            scheduler.schedule(7L, ImportWorkPolicy.KEEP)

            val info = infoOf(ImportWorkData.workName(7L))
            assertThat(info.state.isFinished).isFalse()
            assertThat(info.tags).contains(ImportWorkData.TAG_IMPORT)
            val spec = specOf(info.id)
            assertThat(spec.input.getLong("sessionId", -1L)).isEqualTo(7L)
            assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
            // SDK 34: expedited with the demote-to-ordinary fallback — never a foreground
            // service demand (05 §7's API 31+ note).
            assertThat(spec.expedited).isTrue()
            assertThat(spec.outOfQuotaPolicy)
                .isEqualTo(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

    @Test
    fun `KEEP preserves a pending fetch while APPEND_OR_REPLACE chains behind it`() =
        runTest {
            scheduler.schedule(7L, ImportWorkPolicy.KEEP)
            val first = infoOf(ImportWorkData.workName(7L)).id
            scheduler.schedule(7L, ImportWorkPolicy.KEEP)
            // KEEP ignored the second request: the surviving spec is still the first one.
            assertThat(infoOf(ImportWorkData.workName(7L)).id).isEqualTo(first)
            scheduler.schedule(7L, ImportWorkPolicy.APPEND_OR_REPLACE)
            val ids =
                workManager.getWorkInfosForUniqueWork(ImportWorkData.workName(7L)).get().map { it.id }
            assertThat(ids).hasSize(2)
            assertThat(ids).contains(first)
            assertThat(ids.count { it != first }).isEqualTo(1)
        }

    @Test
    fun `cancel drops the session's unique fetch`() =
        runTest {
            scheduler.schedule(7L, ImportWorkPolicy.KEEP)
            scheduler.cancel(7L)
            // The scheduler's own await landed the cancel; the row may lag a beat.
            assertThat(infoOf(ImportWorkData.workName(7L)).state)
                .isEqualTo(WorkInfo.State.CANCELLED)
        }

    @Test
    fun `hasRestoreWork answers unfinished states only`() =
        runTest {
            assertThat(scheduler.hasRestoreWork()).isFalse()
            assertThat(scheduler.scheduleRestore(1L, auto = false)).isEqualTo(RestoreEnqueue.ENQUEUED)
            assertThat(scheduler.hasRestoreWork()).isTrue()
            workManager.cancelUniqueWork(RestoreWorkData.WORK_RESTORE).result.get()
            assertThat(scheduler.hasRestoreWork()).isFalse()
        }

    @Test
    fun `the same session's replay answers ENQUEUED, a different session ALREADY_RUNNING`() =
        runTest {
            assertThat(scheduler.scheduleRestore(11L, auto = false))
                .isEqualTo(RestoreEnqueue.ENQUEUED)
            val spec = specOf(infoOf(RestoreWorkData.WORK_RESTORE).id)
            assertThat(spec.input.getLong("sessionId", -1L)).isEqualTo(11L)
            assertThat(infoOf(RestoreWorkData.WORK_RESTORE).tags)
                .contains(RestoreWorkData.sessionTag(11L))

            // Replay while the spec still owns the name: the session's own work is the
            // unfinished spec, so the durable intent is already in flight — ENQUEUED.
            assertThat(scheduler.scheduleRestore(11L, auto = false))
                .isEqualTo(RestoreEnqueue.ENQUEUED)

            // A different session cannot displace it: KEEP dropped the request and the
            // surviving spec names session 11 — the definitive decline.
            assertThat(scheduler.scheduleRestore(12L, auto = true))
                .isEqualTo(RestoreEnqueue.ALREADY_RUNNING)
            assertThat(specOf(infoOf(RestoreWorkData.WORK_RESTORE).id).input.getLong("sessionId", -1L))
                .isEqualTo(11L)
        }

    @Test
    fun `a spec with no session tag still declines a different request`() =
        runTest {
            // Any live spec occupying the name that cannot be proven ours is somebody else's
            // by definition — ALREADY_RUNNING, the definitive decline.
            workManager
                .enqueueUniqueWork(
                    RestoreWorkData.WORK_RESTORE,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RestoreWorker>().build(),
                ).result
                .get()
            assertThat(scheduler.scheduleRestore(13L, auto = false))
                .isEqualTo(RestoreEnqueue.ALREADY_RUNNING)
        }

    @Test
    fun `an ambiguous answer fails closed and preserves the durable intent`() =
        runTest {
            // A live spec without our tag holds the name — KEEP will drop the request.
            workManager
                .enqueueUniqueWork(
                    RestoreWorkData.WORK_RESTORE,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RestoreWorker>().build(),
                ).result
                .get()
            // A terminal trace of our session sits beside it — the residue a failed dispatch
            // leaves behind (the enqueue path itself prunes finished specs, so the row goes
            // straight into the test WorkManager's database).
            val doomed =
                OneTimeWorkRequestBuilder<RestoreWorker>()
                    .setInputData(RestoreWorkData.dataOf(13L, auto = false))
                    .addTag(RestoreWorkData.sessionTag(13L))
                    .build()
            val workDb = (workManager as WorkManagerImpl).workDatabase
            workDb.workSpecDao().insertWorkSpec(doomed.workSpec)
            workDb.workSpecDao().setState(WorkInfo.State.FAILED, doomed.id.toString())
            workDb.workNameDao().insert(WorkName(RestoreWorkData.WORK_RESTORE, doomed.id.toString()))
            workDb.workTagDao().insert(WorkTag(RestoreWorkData.sessionTag(13L), doomed.id.toString()))

            // KEEP drops the request behind the foreign spec, and the only provable trace of
            // ours is terminal — admission cannot be answered, so the scheduler throws and
            // the caller keeps the durable intent for start-up reconciliation.
            assertFailsWith<IllegalStateException> {
                scheduler.scheduleRestore(13L, auto = false)
            }
        }

    @Test
    fun `a finished spec frees the name for the next request`() =
        runTest {
            assertThat(scheduler.scheduleRestore(20L, auto = false))
                .isEqualTo(RestoreEnqueue.ENQUEUED)
            workManager.cancelUniqueWork(RestoreWorkData.WORK_RESTORE).result.get()
            // The cancelled row's terminal state must not block a new request.
            assertThat(scheduler.scheduleRestore(21L, auto = false))
                .isEqualTo(RestoreEnqueue.ENQUEUED)
        }

    @Test
    fun `a stale succeeded spec does not acknowledge a new request KEEP dropped`() =
        runTest {
            workManager
                .enqueueUniqueWork(
                    RestoreWorkData.WORK_RESTORE,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RestoreWorker>().build(),
                ).result
                .get()
            val stale =
                OneTimeWorkRequestBuilder<RestoreWorker>()
                    .setInputData(RestoreWorkData.dataOf(13L, auto = false))
                    .addTag(RestoreWorkData.sessionTag(13L))
                    .build()
            val workDb = (workManager as WorkManagerImpl).workDatabase
            workDb.workSpecDao().insertWorkSpec(stale.workSpec)
            workDb.workSpecDao().setState(WorkInfo.State.SUCCEEDED, stale.id.toString())
            workDb.workNameDao().insert(WorkName(RestoreWorkData.WORK_RESTORE, stale.id.toString()))
            workDb.workTagDao().insert(WorkTag(RestoreWorkData.sessionTag(13L), stale.id.toString()))

            assertFailsWith<IllegalStateException> { scheduler.scheduleRestore(13L, auto = false) }
        }

    @Test
    fun `the follow-up enqueue lands behind a live spec instead of vanishing`() =
        runTest {
            assertThat(scheduler.scheduleRestore(31L, auto = false))
                .isEqualTo(RestoreEnqueue.ENQUEUED)
            // RestoreWorker's re-probe path: a second durable intent found mid-run must not be
            // swallowed by KEEP while this spec still owns the name.
            scheduler.enqueueRestoreFollowUp(32L, auto = true)
            val sessionIds =
                workManager
                    .getWorkInfosForUniqueWork(RestoreWorkData.WORK_RESTORE)
                    .get()
                    .map { specOf(it.id).input.getLong("sessionId", -1L) }
            assertThat(sessionIds).containsExactly(31L, 32L)
        }

    /** A worker that parks until [gate] completes — the spec's unfinished state on demand. */
    private class GateWorker(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            gate.await()
            return Result.success()
        }
    }

    private class GatedWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = GateWorker(appContext, workerParameters)
    }

    private fun infoOf(uniqueName: String): WorkInfo {
        val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertThat(infos).isNotEmpty()
        return infos.last()
    }

    private fun specOf(id: UUID): WorkSpec =
        checkNotNull(
            (workManager as WorkManagerImpl).workDatabase.workSpecDao().getWorkSpec(id.toString()),
        )

    private companion object {
        var gate = CompletableDeferred<Unit>()
    }
}
