// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.database.AndroidDatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkSyncWorkerTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var fixture: ArtworkHostFixture
    private lateinit var loader: ImageLoader

    @Before fun setUp() {
        fixture = ArtworkHostFixture(app)
        loader = ImageLoader.Builder(app).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
    }

    @After fun tearDown() {
        WorkManager
            .getInstance(app)
            .cancelAllWork()
            .result
            .get()
        fixture.close()
        loader.shutdown()
    }

    @Test fun workerFactoryDefersRunnerUntilDatabaseIsOpen() =
        runBlocking {
            var resolutions = 0
            val worker =
                worker(
                    Provider {
                        assertIs<DatabaseOpenState.Opened>(fixture.opener.openState.value)
                        resolutions++
                        runner()
                    },
                )
            assertEquals(0, resolutions)
            assertIs<DatabaseOpenState.Pending>(fixture.opener.openState.value)
            assertIs<ListenableWorker.Result.Success>(worker.doWork())
            assertEquals(1, resolutions)
            assertEquals(0, fixture.enqueues)
            assertEquals(8.minutes, ArtworkSyncWorker.DEADLINE)
        }

    @Test fun databaseFailureRetriesWithoutResolvingRunner() =
        runBlocking<Unit> {
            fixture.close()
            fixture =
                ArtworkHostFixture(
                    app,
                    object : DatabaseFactory by AndroidDatabaseFactory(app) {
                        override fun exists(): Boolean = throw IOException("test storage unavailable")
                    },
                )
            val result = worker(Provider { error("must not resolve database users before open") }).doWork()
            assertIs<ListenableWorker.Result.Retry>(result)
        }

    @Test fun uniqueWorkAppendsAndReplacesFailedChainWithRequiredConstraints() =
        runBlocking {
            val scheduler = WorkManagerArtworkScheduler(app)
            val work = WorkManager.getInstance(app)
            scheduler.enqueue()
            scheduler.enqueue()
            val firstChain = work.getWorkInfosForUniqueWork("artwork-sync").get()
            assertEquals(2, firstChain.size)
            assertTrue(firstChain.all { "artwork" in it.tags })
            assertEquals(1, firstChain.count { it.state == WorkInfo.State.BLOCKED })
            // A cancelled predecessor must not poison subsequent requests under APPEND_OR_REPLACE.
            work.cancelUniqueWork("artwork-sync").result.get()
            scheduler.enqueue()
            val fresh = work.getWorkInfosForUniqueWork("artwork-sync").get().filter { !it.state.isFinished }
            assertEquals(1, fresh.size)
            assertEquals(WorkInfo.State.ENQUEUED, fresh.single().state)
            assertTrue(fresh.single().constraints.requiresStorageNotLow())
            assertEquals(androidx.work.NetworkType.CONNECTED, fresh.single().constraints.requiredNetworkType)
            assertFalse(fresh.single().constraints.requiresCharging())
        }

    @Test fun eightMinuteBoundAppendsContinuationAndExternalStopPropagates() =
        runBlocking {
            fixture.open()
            fixture.podcast("u-" + "c".repeat(40), "https://synthetic.test/slow")
            val time = kotlinx.coroutines.test.TestCoroutineScheduler()
            val dispatcher = StandardTestDispatcher(time)
            val reached = CompletableDeferred<Unit>()
            val delayedLoader =
                ImageLoader
                    .Builder(app)
                    .interceptorCoroutineContext(dispatcher)
                    .components {
                        add(
                            object : Interceptor {
                                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                                    reached.complete(Unit)
                                    delay(Long.MAX_VALUE)
                                    error("test response never arrives")
                                }
                            },
                        )
                    }.build()
            try {
                val runner =
                    ArtworkSyncRunner(
                        fixture.db,
                        fixture.store,
                        fixture.codec,
                        fixture.monograms,
                        Provider { delayedLoader },
                        app,
                        fixture.scheduler,
                        fixture.clock,
                        dispatcher,
                    )
                val bounded = worker(Provider { runner }, dispatcher)
                val running = async(Dispatchers.IO) { bounded.doWork() }
                kotlinx.coroutines.withTimeout(10_000) {
                    while (!reached.isCompleted) {
                        time.runCurrent()
                        delay(1)
                    }
                }
                time.advanceTimeBy(8.minutes.inWholeMilliseconds)
                time.runCurrent()
                assertIs<ListenableWorker.Result.Success>(running.await())
                assertEquals(1, fixture.enqueues)
                val stopped = async(Dispatchers.IO) { worker(Provider { runner }, dispatcher).doWork() }
                time.runCurrent()
                stopped.cancel()
                kotlinx.coroutines.withTimeout(10_000) {
                    while (!stopped.isCompleted) {
                        time.runCurrent()
                        delay(1)
                    }
                }
                stopped.join()
                assertTrue(stopped.isCancelled)
                assertEquals(1, fixture.enqueues)
            } finally {
                delayedLoader.shutdown()
            }
        }

    private fun runner() =
        ArtworkSyncRunner(
            fixture.db,
            fixture.store,
            fixture.codec,
            fixture.monograms,
            Provider { loader },
            app,
            fixture.scheduler,
            fixture.clock,
            Dispatchers.IO,
        )

    private fun worker(
        runner: Provider<ArtworkSyncRunner>,
        io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    ): ArtworkSyncWorker =
        TestListenableWorkerBuilder<ArtworkSyncWorker>(app)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        ArtworkSyncWorker(
                            appContext,
                            workerParameters,
                            fixture.opener,
                            runner,
                            Provider {
                                MosaicSync(
                                    fixture.db,
                                    fixture.store,
                                    MosaicSources(fixture.db),
                                    MosaicRenderer(fixture.store, fixture.monograms, fixture.codec),
                                    fixture.scheduler,
                                    fixture.clock,
                                    Dispatchers.Default,
                                )
                            },
                            io,
                        )
                },
            ).build()
}
