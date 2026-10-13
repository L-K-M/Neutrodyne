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
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
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
            val settings = FakeSettingsRepository()
            val deps = deps(db = db, settings = settings)

            worker(deps).doWork()

            assertThat(settings.calls).contains("set(${FeedsSettingKeys.LAST_RUN_STOP_REASON.name})")
        }

    @Test
    fun `a thrown engine error retries within the continuation budget then succeeds`() =
        runTest { assertThrownReadIsBounded(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES) }

    @Test
    fun `a thrown worker settings read retries within the continuation budget then succeeds`() =
        runTest { assertThrownReadIsBounded(FeedsSettingKeys.SCHEDULED_TICK_MINUTES) }

    private suspend fun assertThrownReadIsBounded(failingKey: SettingKey<*>) {
        val settings = FakeSettingsRepository()
        val deps =
            deps(
                db = openDb(),
                settings = throwingRead(settings, failingKey, IllegalStateException("disk read failed")),
            )
        for (attempt in 0..9) {
            val result = worker(deps, runAttemptCount = attempt).doWork()
            val expected =
                if (attempt <
                    9
                ) {
                    ListenableWorker.Result.Retry::class.java
                } else {
                    ListenableWorker.Result.Success::class.java
                }
            assertThat(result).isInstanceOf(expected)
            assertThat(settings.calls.count { it == "set(${FeedsSettingKeys.LAST_RUN_STOP_REASON.name})" })
                .isEqualTo(attempt + 1)
        }
        assertThat(deps.scheduler.continuationCount).isEqualTo(0)
    }

    @Test
    fun `cancellation from worker settings or the engine propagates and writes the stop reason`() =
        runTest {
            val db = openDb()
            for (key in listOf(FeedsSettingKeys.SCHEDULED_TICK_MINUTES, FeedsSettingKeys.REFRESH_INTERVAL_MINUTES)) {
                val settings = FakeSettingsRepository()
                val cancellation = CancellationException("worker stopped")
                val deps = deps(db = db, settings = throwingRead(settings, key, cancellation))
                var caught: CancellationException? = null
                try {
                    worker(deps).doWork()
                } catch (exception: CancellationException) {
                    caught = exception
                }
                assertThat(caught).isSameInstanceAs(cancellation)
                assertThat(settings.calls).contains("set(${FeedsSettingKeys.LAST_RUN_STOP_REASON.name})")
            }
        }

    private fun throwingRead(
        settings: SettingsRepository,
        failingKey: SettingKey<*>,
        failure: Exception,
    ): SettingsRepository =
        object : SettingsRepository by settings {
            override suspend fun <T : Any> get(key: SettingKey<T>): T {
                if (key == failingKey) throw failure
                return settings.get(key)
            }
        }

    @Test
    fun `a scoped retry that times out on the mutex retries instead of continuing`() =
        runTest { assertMutexTimeoutRetries(RefreshOrigin.RETRY) }

    @Test
    fun `a non-manual mutex timeout retries the persisted request`() =
        runTest { assertMutexTimeoutRetries(RefreshOrigin.SUBSCRIBE) }

    private suspend fun TestScope.assertMutexTimeoutRetries(origin: RefreshOrigin) {
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
        // Another run owns the mutex; this worker's clock jumps past its wait deadline.
        val holder =
            backgroundScope.async {
                deps.refresher.run(
                    RefreshRequest(RefreshScope.All, force = false, pagesOnly = false, origin = RefreshOrigin.PERIODIC),
                )
            }
        testScheduler.runCurrent()
        try {
            val worker =
                worker(deps, RefreshScope.Podcasts(listOf(id)), force = true, pagesOnly = true, origin = origin)
            val result = worker.doWork()

            // Retrying retains WorkManager's input, with no app-scope enqueue gap.
            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
            assertThat(deps.scheduler.continuationCount).isEqualTo(0)
            assertThat(deps.scheduler.nowRequests).isEmpty()
            val retained = RefreshWorkData.request(worker.inputData, 0, 0, 0)
            assertThat(retained.scope).isEqualTo(RefreshScope.Podcasts(listOf(id)))
            assertThat(retained.force).isTrue()
            assertThat(retained.pagesOnly).isTrue()
            assertThat(retained.origin).isEqualTo(origin)
        } finally {
            gate.complete(Unit)
            holder.join()
        }
    }

    // --- plumbing ---------------------------------------------------------------------------------

    private suspend fun openDb(): NeutrodyneDatabase = newDb(app).also { db = it }

    private class Deps(
        val adapter: StubSourceAdapter,
        val scheduler: FakeRefreshScheduler,
        val settings: SettingsRepository,
        val clock: Clock,
        val refresher: FeedRefresher,
    )

    private fun deps(
        db: NeutrodyneDatabase,
        adapter: StubSourceAdapter = stubAdapter(),
        clock: Clock = TestClock(),
        settings: SettingsRepository = FakeSettingsRepository(),
    ): Deps {
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
