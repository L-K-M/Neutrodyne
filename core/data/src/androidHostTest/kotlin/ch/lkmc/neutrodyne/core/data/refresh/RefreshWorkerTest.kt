// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.StubSourceAdapter
import ch.lkmc.neutrodyne.core.data.adapterFailed
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.newRefresher
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.data.stubAdapter
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `RefreshWorker` under the WorkManager test builder (03 Work requests, M1 acceptance 4): the
 * engine runs under the 8-min soft deadline; leftovers go to `refresh-continuation` once — a
 * deadline-bound continuation retries below 10 attempts and succeeds at the cap. Per-feed
 * failures never escalate to WorkManager retries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshWorkerTest {
    private val app = RuntimeEnvironment.getApplication()
    private var db: NeutrodyneDatabase? = null

    @After
    fun closeDb() {
        db?.close()
    }

    @Test
    fun `an empty due set succeeds without enqueuing a continuation`() =
        runTest {
            val db = openDb()
            seedPodcast(db, "https://a.test/feed.xml", nextRefreshAt = TestClock.DEFAULT_NOW + HOUR_MS)
            val deps = deps(db = db)

            val result = worker(deps).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(deps.adapter.calls).isEmpty()
            assertThat(deps.scheduler.continuationCount).isEqualTo(0)
        }

    @Test
    fun `a per-feed failure stays on the row and still succeeds`() =
        runTest {
            val db = openDb()
            val id = seedPodcast(db, "https://a.test/feed.xml")
            val deps =
                deps(
                    db = db,
                    adapter = stubAdapter(mutableMapOf("https://a.test/feed.xml" to adapterFailed())),
                )

            val result = worker(deps).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(deps.adapter.calls).containsExactly(id to FetchMode.REFRESH)
            assertThat(deps.scheduler.continuationCount).isEqualTo(0)
        }

    @Test
    fun `the deadline enqueues one continuation for remaining work`() =
        runTest {
            val db = openDb()
            seedPodcast(db, "https://a.test/feed.xml")
            seedPodcast(db, "https://b.test/feed.xml")
            val deps = deps(db = db, clock = DeadlineClock())

            val result = worker(deps, origin = RefreshOrigin.PERIODIC).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(deps.scheduler.continuationCount).isEqualTo(1)
        }

    @Test
    fun `a deadline-bound continuation retries below the cap`() =
        runTest {
            val db = openDb()
            seedPodcast(db, "https://a.test/feed.xml")
            seedPodcast(db, "https://b.test/feed.xml")
            val deps = deps(db = db, clock = DeadlineClock())

            val result =
                worker(deps, origin = RefreshOrigin.CONTINUATION, runAttemptCount = 3).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            assertThat(deps.scheduler.continuationCount).isEqualTo(0)
        }

    @Test
    fun `a deadline-bound continuation retries up to the ninth retry`() =
        runTest {
            val db = openDb()
            seedPodcast(db, "https://a.test/feed.xml")
            seedPodcast(db, "https://b.test/feed.xml")
            val deps = deps(db = db, clock = DeadlineClock())

            // Zero-based attempts 0–8 are the nine retries before the tenth execution.
            val result =
                worker(deps, origin = RefreshOrigin.CONTINUATION, runAttemptCount = 8).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        }

    @Test
    fun `a deadline-bound continuation succeeds at the attempt cap`() =
        runTest {
            val db = openDb()
            seedPodcast(db, "https://a.test/feed.xml")
            seedPodcast(db, "https://b.test/feed.xml")
            val deps = deps(db = db, clock = DeadlineClock())

            // Count 9 is the tenth execution — AC4's cap: leftovers are dropped, not retried.
            val result =
                worker(deps, origin = RefreshOrigin.CONTINUATION, runAttemptCount = 9).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(deps.scheduler.continuationCount).isEqualTo(0)
        }

    @Test
    fun `a failed continuation enqueue retries instead of reporting success`() =
        runTest {
            val db = openDb()
            seedPodcast(db, "https://a.test/feed.xml")
            seedPodcast(db, "https://b.test/feed.xml")
            val deps = deps(db = db, clock = DeadlineClock())
            deps.scheduler.continuationResult = false

            val result = worker(deps, origin = RefreshOrigin.PERIODIC).doWork()

            // The enqueue is awaited: an unconfirmed enqueue must not strand the leftovers (R6).
            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            assertThat(deps.scheduler.continuationCount).isEqualTo(1)
        }

    @Test
    fun `the scheduled tick floor narrows the due slack`() =
        runTest {
            val db = openDb()
            // Due in 20 min: outside the 15-min slack of the 60-min floor, so untouched.
            seedPodcast(db, "https://a.test/feed.xml", nextRefreshAt = TestClock.DEFAULT_NOW + 20 * MINUTE_MS)
            val deps = deps(db = db)

            val result = worker(deps).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(deps.adapter.calls).isEmpty()
        }

    @Test
    fun `a wider scheduled tick widens the due slack`() =
        runTest {
            val db = openDb()
            val id = seedPodcast(db, "https://a.test/feed.xml", nextRefreshAt = TestClock.DEFAULT_NOW + 20 * MINUTE_MS)
            val deps = deps(db = db)
            // A 90-min tick gives 22.5 min of slack, which covers the feed due in 20 min.
            deps.settings.set(FeedsSettingKeys.SCHEDULED_TICK_MINUTES, 90L)

            val result = worker(deps).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(deps.adapter.calls).containsExactly(id to FetchMode.REFRESH)
        }

    @Test
    fun `the work manager stop reason is persisted`() =
        runTest {
            val db = openDb()
            val deps = deps(db = db)

            worker(deps).doWork()

            assertThat(deps.settings.calls).contains("set(${FeedsSettingKeys.LAST_RUN_STOP_REASON.name})")
        }

    @Test
    fun `a scoped retry that times out on the mutex re-enqueues instead of continuing`() =
        runTest {
            val db = openDb()
            val id = seedPodcast(db, "https://a.test/feed.xml")
            val gate = CompletableDeferred<Unit>()
            val deps =
                deps(
                    db = db,
                    adapter =
                        stubAdapter(onFetch = { _, _ ->
                            gate.await()
                            adapterFailed()
                        }),
                    clock = DeadlineClock(),
                )
            // An unrelated run owns the engine mutex behind its parked fetch; the retry work's
            // deadline lands inside the mutex margin (the clock jumps past it), so the wait
            // degrades to a tryLock that fails.
            val holder =
                backgroundScope.async {
                    deps.refresher.run(
                        RefreshRequest(
                            scope = RefreshScope.All,
                            force = false,
                            pagesOnly = false,
                            origin = RefreshOrigin.PERIODIC,
                        ),
                    )
                }
            testScheduler.runCurrent()

            val result =
                worker(
                    deps,
                    scope = RefreshScope.Podcasts(listOf(id)),
                    force = true,
                    origin = RefreshOrigin.RETRY,
                ).doWork()

            // r4 F3: the original request is re-enqueued — scope, force and RETRY origin
            // intact in the work data — rather than degraded to a non-forced continuation.
            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(deps.scheduler.continuationCount).isEqualTo(0)
            assertThat(deps.scheduler.nowRequests)
                .containsExactly(
                    FakeRefreshScheduler.NowRequest(
                        RefreshScope.Podcasts(listOf(id)),
                        force = true,
                        pagesOnly = false,
                        origin = RefreshOrigin.RETRY,
                    ),
                )

            gate.complete(Unit)
            holder.await()
        }

    // --- plumbing ---------------------------------------------------------------------------------

    private suspend fun openDb(): NeutrodyneDatabase = newDb(app).also { db = it }

    private class Deps(
        val adapter: StubSourceAdapter,
        val scheduler: FakeRefreshScheduler,
        val settings: FakeSettingsRepository,
        val clock: Clock,
        val refresher: FeedRefresher,
    )

    private fun deps(
        db: NeutrodyneDatabase,
        adapter: StubSourceAdapter = stubAdapter(),
        clock: Clock = TestClock(),
    ): Deps {
        val settings = FakeSettingsRepository()
        val scheduler = FakeRefreshScheduler()
        val refresher =
            newRefresher(
                context = app,
                db = db,
                adapters = mapOf(SourceType.RSS to adapter),
                clock = clock,
                settings = settings,
            )
        return Deps(adapter, scheduler, settings, clock, refresher)
    }

    private fun worker(
        deps: Deps,
        scope: RefreshScope = RefreshScope.All,
        force: Boolean = false,
        pagesOnly: Boolean = false,
        origin: RefreshOrigin = RefreshOrigin.PERIODIC,
        runAttemptCount: Int = 0,
    ): RefreshWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    RefreshWorker(
                        appContext,
                        workerParameters,
                        deps.refresher,
                        deps.scheduler,
                        deps.settings,
                        deps.clock,
                    )
            }
        return TestListenableWorkerBuilder
            .from(app, RefreshWorker::class.java)
            .setInputData(RefreshWorkData.of(scope, force, pagesOnly, origin))
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(factory)
            .build()
    }

    /**
     * `elapsedRealtime` returns 0 once (the worker's deadline computation lands at +8 min), then
     * reports the deadline already past — the engine stops launching feeds and reports leftovers.
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
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
    }
}
