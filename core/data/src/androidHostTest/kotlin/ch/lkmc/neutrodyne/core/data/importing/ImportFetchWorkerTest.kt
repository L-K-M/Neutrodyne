// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.adapterParsed
import ch.lkmc.neutrodyne.core.data.fetchMeta
import ch.lkmc.neutrodyne.core.data.importItem
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.newRefresher
import ch.lkmc.neutrodyne.core.data.parsedFeed
import ch.lkmc.neutrodyne.core.data.seedImportSession
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.data.stubAdapter
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeImportReportPoster
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith

/**
 * `ImportFetchWorker` over the real `ImportFetchRunner` (05 §7): the session id decodes from
 * the request's input data, `runAttemptCount` and the 8-minute monotonic deadline reach the
 * lane, `COMPLETED`/`RETRY` map to `Result.success()`/`Result.retry()`, a lane failure
 * reschedules, and a stopped run propagates cancellation — the durable `COMMITTED` session
 * stays for the next enqueue rather than reporting a fake success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportFetchWorkerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val settings = FakeSettingsRepository()
    private val snapshots = FakeSnapshotScheduler()
    private val reports = FakeImportReportPoster()
    private var db: NeutrodyneDatabase? = null

    @After
    fun closeDb() {
        db?.close()
    }

    private fun openDb(): NeutrodyneDatabase = newDb(app, clock).also { db = it }

    @Test
    fun `a committed session runs to DONE and posts its report`() =
        runBlocking<Unit> {
            val db = openDb()
            val podcastId = seedPodcast(db, FEED_URL, nextRefreshAt = 0L)
            val adapter =
                stubAdapter(
                    mutableMapOf(FEED_URL to adapterParsed(parsedFeed(), meta = fetchMeta(FEED_URL))),
                )
            val sessionId =
                db.seedImportSession(
                    state = ImportState.COMMITTED,
                    items = listOf(importItem(0, FEED_URL, status = ImportItemStatus.QUEUED, podcastId = podcastId)),
                )

            val result = worker(db, sessionId, adapter = adapter).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(db.importDao().sessionById(sessionId)?.state).isEqualTo(ImportState.DONE)
            assertThat(reports.posts.map { it.sessionId }).containsExactly(sessionId)
            assertThat(adapter.calls.map { it.first }).containsExactly(podcastId)
        }

    @Test
    fun `a session without pending items completes and posts an empty report`() =
        runBlocking<Unit> {
            val db = openDb()
            val sessionId = db.seedImportSession(state = ImportState.COMMITTED)

            val result = worker(db, sessionId).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            // DONE is DONE — the report lands even when nothing was fetched.
            assertThat(reports.posts.map { it.sessionId }).containsExactly(sessionId)
        }

    @Test
    fun `a request without a session id finishes without a run`() =
        runBlocking<Unit> {
            val db = openDb()
            val result = worker(db, sessionId = null).doWork()
            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        }

    @Test
    fun `a deadline-bound run reschedules with the item still queued`() =
        runBlocking<Unit> {
            val db = openDb()
            val podcastId = seedPodcast(db, FEED_URL, nextRefreshAt = 0L)
            val sessionId =
                db.seedImportSession(
                    state = ImportState.COMMITTED,
                    items = listOf(importItem(0, FEED_URL, status = ImportItemStatus.QUEUED, podcastId = podcastId)),
                )
            // The worker's deadline lands at +8 min; the lane's own elapsed checks then
            // report it already past — the same state a real overrun reaches.
            val result = worker(db, sessionId, workerClock = DeadlineClock()).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            // Retry left the durable work alone: the item stays QUEUED for the redelivery.
            assertThat(
                db.importDao().itemsByStatus(sessionId, listOf(ImportItemStatus.QUEUED)),
            ).hasSize(1)
        }

    @Test
    fun `a thrown lane error reschedules`() =
        runBlocking<Unit> {
            val db = openDb()
            val sessionId = db.seedImportSession(state = ImportState.COMMITTED)
            db.close()
            this@ImportFetchWorkerTest.db = null

            val result = worker(db, sessionId).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        }

    @Test
    fun `a stopped run propagates cancellation`() =
        runBlocking<Unit> {
            val db = openDb()
            val sessionId = db.seedImportSession(state = ImportState.COMMITTED)
            val stopped = Job().apply { cancel() }

            assertFailsWith<CancellationException> {
                withContext(stopped) { worker(db, sessionId).doWork() }
            }
            // The durable session is untouched — startup reconciliation re-enqueues it.
            assertThat(db.importDao().sessionById(sessionId)?.state).isEqualTo(ImportState.COMMITTED)
        }

    private fun worker(
        db: NeutrodyneDatabase,
        sessionId: Long?,
        runAttemptCount: Int = 0,
        adapter: ch.lkmc.neutrodyne.core.data.StubSourceAdapter = stubAdapter(),
        workerClock: Clock = clock,
    ): ImportFetchWorker {
        val runner =
            ImportFetchRunner(
                db = db,
                refresher = newRefresher(app, db, mapOf(SourceType.RSS to adapter), workerClock, settings),
                snapshots = snapshots,
                reports = reports,
                clock = workerClock,
            )
        val factory =
            object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ImportFetchWorker(appContext, workerParameters, runner, workerClock)
            }
        val builder =
            TestListenableWorkerBuilder
                .from(app, ImportFetchWorker::class.java)
                .setRunAttemptCount(runAttemptCount)
                .setWorkerFactory(factory)
        if (sessionId != null) {
            builder.setInputData(ImportWorkData.dataOf(sessionId))
        }
        return builder.build()
    }

    /**
     * `elapsedRealtime` returns 0 once — the worker's deadline computation lands at +8 min —
     * then reports the deadline already past, so the lane retries instead of fetching.
     */
    private class DeadlineClock : Clock {
        private var elapsedCalls = 0

        override fun now(): Long = TestClock.DEFAULT_NOW

        override fun elapsedRealtime(): Long = if (elapsedCalls++ == 0) 0L else PAST_DEADLINE_MS

        private companion object {
            const val PAST_DEADLINE_MS = 9 * 60_000L
        }
    }

    private companion object {
        const val FEED_URL = "https://import.test/feed.xml"
    }
}
