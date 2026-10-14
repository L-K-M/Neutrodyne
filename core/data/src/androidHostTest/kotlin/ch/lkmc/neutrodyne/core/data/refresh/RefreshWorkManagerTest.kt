// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.TestDriver
import androidx.work.testing.TestScheduler
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.data.adapterParsed
import ch.lkmc.neutrodyne.core.data.dueFeedOf
import ch.lkmc.neutrodyne.core.data.fetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.newIngestor
import ch.lkmc.neutrodyne.core.data.newRefresher
import ch.lkmc.neutrodyne.core.data.parsedEpisode
import ch.lkmc.neutrodyne.core.data.parsedFeed
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.data.stubAdapter
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WorkManager-driven refresh journey of M1 AC4 (03 Work requests / Engine run, audit G3):
 * real `RefreshWorker` executions through `WorkManagerTestInitHelper`'s test backend and
 * `TestDriver` — constraint gating, due-only selection, the 8-minute deadline handing off a
 * single `refresh-continuation`, the ten-attempt cap — plus cancellation atomicity: a worker
 * cancelled inside a real Room ingest transaction leaves the feed's rows untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshWorkManagerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val settings = FakeSettingsRepository()
    private lateinit var db: NeutrodyneDatabase
    private lateinit var scheduler: WorkManagerRefreshScheduler

    private val workManager get() = WorkManager.getInstance(app)
    private val testDriver: TestDriver
        get() = checkNotNull(WorkManagerTestInitHelper.getTestDriver(app))

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `the driven run fetches only due feeds and the deadline continuation stops at ten`() =
        runTest {
            db = newDb(app, clock)
            val dueIds =
                List(DUE_FEED_COUNT) {
                    seedPodcast(db, "https://d$it.test/feed.xml", nextRefreshAt = 0L)
                }
            val futureId =
                seedPodcast(db, "https://future.test/feed.xml", nextRefreshAt = FAR_FUTURE)

            val adapter = stubAdapter(dueResults(dueIds))
            installWorkerFactory(refresherWith(adaptersHookingClock(adapter)))
            scheduler = scheduler()
            scheduler.enqueueNow(
                RefreshScope.All,
                force = false,
                pagesOnly = false,
                origin = RefreshOrigin.FOREGROUND,
            )
            val initial = awaitWork(WorkManagerRefreshScheduler.WORK_AUTO)

            // The auto request's connected-network/battery constraints gate the launch: nothing
            // runs until TestDriver satisfies them (03 Work requests).
            assertThat(workManager.getWorkInfoById(initial.id).get()?.state)
                .isEqualTo(WorkInfo.State.ENQUEUED)
            assertThat(adapter.calls).isEmpty()

            testDriver.setAllConstraintsMet(initial.id)
            awaitInfo(initial.id) { it.state == WorkInfo.State.SUCCEEDED }

            // One feed launched before the tripped deadline stopped the loop.
            assertThat(adapter.calls).containsExactly(dueIds[0] to FetchMode.REFRESH)

            // Exactly one `refresh-continuation`, KEEP-chained, carrying the continuation origin.
            val continuations =
                workManager
                    .getWorkInfosForUniqueWork(WorkManagerRefreshScheduler.WORK_CONTINUATION)
                    .get()
            assertThat(continuations).hasSize(1)
            val continuation = continuations.single()
            assertThat(
                RefreshWorkData
                    .request(specOf(continuation.id.toString()).input, 0, 0, 0)
                    .origin,
            ).isEqualTo(RefreshOrigin.CONTINUATION)

            // The continuation carries a 60 s initial delay — a real gate for its first
            // execution (`isFirstPeriodicRun`). Each retry is backed off: the platform would
            // re-deliver it once the delay elapses; in the test backend `schedule(spec)` is
            // that signal (the running spec was un-registered at launch), followed by the
            // constraints gate.
            testDriver.setInitialDelayMet(continuation.id)
            repeat(ATTEMPT_CAP) { attempt ->
                if (attempt > 0) {
                    (testDriver as TestScheduler).schedule(specOf(continuation.id.toString()))
                }
                testDriver.setAllConstraintsMet(continuation.id)
                if (attempt < ATTEMPT_CAP - 1) {
                    awaitInfo(continuation.id) {
                        it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == attempt + 1
                    }
                } else {
                    awaitInfo(continuation.id) { it.state == WorkInfo.State.SUCCEEDED }
                }
            }

            // One feed per execution: the first run plus ten continuation executions consumed
            // eleven due feeds in id order; the last due feed and the future feed were never
            // fetched.
            val expected = dueIds.dropLast(1).map { it to FetchMode.REFRESH }
            assertThat(adapter.calls).containsExactlyElementsIn(expected).inOrder()
            assertThat(db.podcastDao().byId(dueIds.last())?.lastAttemptAt).isNull()
            assertThat(db.podcastDao().byId(futureId)?.lastAttemptAt).isNull()
        }

    @Test
    fun `cancelling inside an ingest transaction leaves the feed untouched`() =
        runTest {
            val barrier = StatementBarrier()
            db = newDb(app, clock, driver = BarrierDriver(AndroidSQLiteDriver(), barrier))
            val podcastId = seedPodcast(db, FEED_URL, nextRefreshAt = 0L)

            // v1 through the real ingest path: the feed carries episodes, a description, a
            // user-state row and fetch metadata before the cancelled run touches them.
            newIngestor(db, clock, settings)
                .ingest(
                    dueFeedOf(db, podcastId),
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(0, guid = "e1", descriptionHtml = "<p>v1</p>"),
                                parsedEpisode(1, guid = "e2"),
                            ),
                    ),
                    IngestContext(
                        mode = IngestMode.REFRESH,
                        partial = false,
                        fetch = fetchMeta(FEED_URL, etag = "v1", lastModified = "m-v1"),
                    ),
                )
            val e1 = checkNotNull(db.episodeDao().byIdentityKey(podcastId, "g:e1"))
            db.episodeStateDao().upsert(episodeStateEntity(e1.id, playedAt = clock.nowMs - 1_000L))
            db.podcastDao().forceDue(scopeAll = true)
            val before = feedSnapshot(podcastId)

            // v2: a changed description (write into `episode_description`) plus a new episode
            // (insert) — whichever write lands first inside the transaction trips the barrier.
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        FEED_URL to
                            adapterParsed(
                                parsedFeed(
                                    items =
                                        listOf(
                                            parsedEpisode(
                                                0,
                                                guid = "e1",
                                                descriptionHtml = "<p>v2</p>",
                                            ),
                                            parsedEpisode(1, guid = "e2"),
                                            parsedEpisode(2, guid = "e3"),
                                        ),
                                ),
                                meta = fetchMeta(FEED_URL, etag = "v2"),
                            ),
                    ),
                )
            installWorkerFactory(
                refresherWith(mapOf(SourceType.RSS to adapter)),
            )
            scheduler = scheduler()
            scheduler.enqueueNow(
                RefreshScope.All,
                force = false,
                pagesOnly = false,
                origin = RefreshOrigin.FOREGROUND,
            )
            val initial = awaitWork(WorkManagerRefreshScheduler.WORK_AUTO)

            barrier.arm()
            testDriver.setAllConstraintsMet(initial.id)
            try {
                assertThat(barrier.reached.await(AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue()
                assertThat(barrier.firedOn).isNotNull()

                workManager.cancelWorkById(initial.id).result.get()
            } finally {
                // A dead barrier would park the worker coroutine (and its write transaction)
                // on a pooled thread past test teardown.
                barrier.release()
            }

            assertThat(barrier.rolledBack.await(AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue()
            awaitInfo(initial.id) { it.state == WorkInfo.State.CANCELLED }

            // No partially ingested feed: every write inside the open transaction rolled back.
            assertThat(feedSnapshot(podcastId)).isEqualTo(before)
        }

    // --- plumbing ---------------------------------------------------------------------------------

    private fun TestScope.scheduler() =
        WorkManagerRefreshScheduler(
            application = app,
            db = db,
            settings = settings,
            rebaser = NextRefreshRebaser(db, settings),
            appScope = this,
        )

    private fun refresherWith(adapters: Map<SourceType, SourceAdapter>) =
        newRefresher(context = app, db = db, adapters = adapters, clock = clock, settings = settings)

    /**
     * Mirrors the production contract — `MetroWorkerFactory` dispatches by worker class name
     * into the bound creator, which calls `RefreshWorker.Factory.create` — built on the test's
     * real engine, scheduler, settings and clock.
     */
    private fun installWorkerFactory(refresher: FeedRefresher) {
        val factory =
            object : RefreshWorker.Factory {
                override fun create(
                    context: Context,
                    params: WorkerParameters,
                ): RefreshWorker = RefreshWorker(context, params, refresher, scheduler, settings, clock)
            }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? =
                            if (workerClassName == RefreshWorker::class.qualifiedName) {
                                factory.create(appContext, workerParameters)
                            } else {
                                null
                            }
                    },
                ).build(),
        )
    }

    private fun dueResults(dueIds: List<Long>): MutableMap<String, AdapterResult> =
        dueIds.indices.associateTo(mutableMapOf()) { index ->
            val url = "https://d$index.test/feed.xml"
            url to
                adapterParsed(
                    parsedFeed(items = listOf(parsedEpisode(0, guid = "ep-$index"))),
                    meta = fetchMeta(url, etag = "e-$index"),
                )
        }

    /**
     * The deterministic deadline trip of M1 AC4: `doWork` recomputes the deadline from the
     * injected clock's `elapsedRealtime` on every execution, and the launch loop checks it at
     * the top of each iteration, right before `adapters.getValue(feed.sourceType)`. Advancing
     * `elapsedMs` on that lookup makes the next iteration's check trip — the same state a real
     * overrun reaches — so exactly one feed launches per worker execution.
     */
    private fun adaptersHookingClock(adapter: SourceAdapter): Map<SourceType, SourceAdapter> {
        val base = mapOf(SourceType.RSS to adapter)
        return object : Map<SourceType, SourceAdapter> by base {
            override fun get(key: SourceType): SourceAdapter? =
                base.get(key).also { clock.elapsedMs += DEADLINE_TRIP_MS }
        }
    }

    private fun TestScope.awaitWork(name: String): WorkInfo {
        val deadlineMs = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadlineMs) {
            advanceUntilIdle()
            workManager
                .getWorkInfosForUniqueWork(name)
                .get()
                .singleOrNull()
                ?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("no work enqueued for $name within ${AWAIT_MS}ms")
    }

    private fun awaitInfo(
        id: UUID,
        predicate: (WorkInfo) -> Boolean,
    ): WorkInfo {
        val deadlineMs = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadlineMs) {
            workManager.getWorkInfoById(id).get()?.let { if (predicate(it)) return it }
            Thread.sleep(POLL_MS)
        }
        error("work $id stuck at ${workManager.getWorkInfoById(id).get()} (awaited ${AWAIT_MS}ms)")
    }

    /** The persisted spec: `WorkInfo` hides the input data. */
    private suspend fun specOf(id: String): WorkSpec =
        withContext(Dispatchers.IO) {
            checkNotNull((workManager as WorkManagerImpl).workDatabase.workSpecDao().getWorkSpec(id))
        }

    // --- cancellation barrier ----------------------------------------------------------------------

    /**
     * The episode/description/state/fetch-metadata view of one feed. `episode_description`
     * holds `ByteArray`s — the map values are copied to `List<Byte>` so `isEqualTo` compares
     * content, not identity.
     */
    private data class FeedSnapshot(
        val podcast: PodcastEntity?,
        val episodes: List<EpisodeEntity>,
        val descriptions: Map<Long, List<Byte>?>,
        val states: Map<Long, EpisodeStateEntity?>,
    )

    private suspend fun feedSnapshot(podcastId: Long): FeedSnapshot {
        val episodes =
            db
                .ingestDao()
                .existing(podcastId)
                .mapNotNull { db.episodeDao().byId(it.id) }
                .sortedBy { it.id }
        return FeedSnapshot(
            podcast = db.podcastDao().byId(podcastId),
            episodes = episodes,
            descriptions =
                episodes.associate {
                    it.id to
                        db
                            .episodeDao()
                            .observeDescription(it.id)
                            .first()
                            ?.toList()
                },
            states = episodes.associate { it.id to db.episodeStateDao().byEpisode(it.id) },
        )
    }

    /**
     * Parks the first mutating statement issued inside a transaction, on the worker's own
     * thread, until [release] is called — the test cancels the running worker against that
     * parked write. Room3 issues `BEGIN`/`END`/`ROLLBACK` as plain statements through
     * `SQLiteConnection.prepare`/`step`, so wrapping the driver sees the whole boundary.
     */
    private class StatementBarrier {
        private val releaseLatch = CountDownLatch(1)
        private val consumed = AtomicBoolean(false)
        val reached = CountDownLatch(1)
        val rolledBack = CountDownLatch(1)

        @Volatile var armed = false
            private set

        /** The mutating statement the barrier parked on — null until [reached] fires. */
        @Volatile var firedOn: String? = null
            private set

        fun arm() {
            armed = true
        }

        fun release() {
            releaseLatch.countDown()
        }

        fun onRollback() {
            rolledBack.countDown()
        }

        /** Blocks the caller when this statement is the first armed in-transaction write. */
        fun blockIfArmedWrite(
            sql: String,
            inTransaction: Boolean,
        ) {
            if (!armed || !inTransaction || !isWrite(sql) || !consumed.compareAndSet(false, true)) {
                return
            }
            firedOn = sql
            reached.countDown()
            releaseLatch.await()
        }

        private fun isWrite(sql: String): Boolean {
            val head = sql.trimStart().uppercase()
            return head.startsWith("INSERT") ||
                head.startsWith("UPDATE") ||
                head.startsWith("DELETE") ||
                head.startsWith("REPLACE")
        }
    }

    private class BarrierDriver(
        private val delegate: SQLiteDriver,
        private val barrier: StatementBarrier,
    ) : SQLiteDriver by delegate {
        override fun open(fileName: String): SQLiteConnection = BarrierConnection(delegate.open(fileName), barrier)
    }

    private class BarrierConnection(
        private val delegate: SQLiteConnection,
        private val barrier: StatementBarrier,
    ) : SQLiteConnection by delegate {
        @Volatile private var inTransaction = false

        override fun prepare(sql: String): SQLiteStatement {
            val stmt = delegate.prepare(sql)
            return object : SQLiteStatement by stmt {
                override fun step(): Boolean {
                    val stepped = stmt.step()
                    track(sql)
                    return stepped
                }
            }
        }

        private fun track(sql: String) {
            val head = sql.trimStart().take(HEAD_CHARS).uppercase()
            when {
                head.startsWith("BEGIN") -> {
                    inTransaction = true
                }

                head.startsWith("COMMIT") || head.startsWith("END") -> {
                    inTransaction = false
                }

                // `ROLLBACK TRANSACTION TO SAVEPOINT` keeps the outer transaction open.
                head.startsWith("ROLLBACK") && !head.contains("SAVEPOINT") -> {
                    inTransaction = false
                    barrier.onRollback()
                }
                // SAVEPOINT/RELEASE nest inside the open transaction; the flag stays.
            }
            if (inTransaction) {
                barrier.blockIfArmedWrite(sql, inTransaction = true)
            }
        }
    }

    private companion object {
        const val FEED_URL = "https://c.test/feed.xml"

        /** The worker's ten-execution budget (`RefreshWorker.MAX_CONTINUATION_ATTEMPTS`). */
        const val ATTEMPT_CAP = 10

        /** Due feeds outnumber the ten-execution budget so leftovers outlive the cap. */
        const val DUE_FEED_COUNT = 12

        /** Past the 8-min deadline plus its 30 s margin from any start point. */
        const val DEADLINE_TRIP_MS = 9 * 60_000L
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 10L
        const val HEAD_CHARS = 48
        const val FAR_FUTURE = TestClock.DEFAULT_NOW + 365L * 24 * 60 * 60 * 1_000L
    }
}
