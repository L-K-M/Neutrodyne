// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.download.impl.CleanupWorker
import ch.lkmc.neutrodyne.download.impl.DownloadLaneWorker
import ch.lkmc.neutrodyne.download.impl.DownloadMoveWorker
import ch.lkmc.neutrodyne.download.impl.DownloadReconcileWorker
import ch.lkmc.neutrodyne.download.impl.DownloadWakeWorker
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Production worker map, initialization bands and common planner/cleanup/mover bindings. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AndroidDownloadGraphTest {
    private val application = RuntimeEnvironment.getApplication()
    private val graph = createGraphFactory<AndroidAppGraph.Factory>().create(application)

    @Test fun everyDownloadWorkerCanBeCreatedBeforeDatabaseOpen() {
        val factory = graph.workerFactory
        val workers =
            listOf(
                TestListenableWorkerBuilder<DownloadLaneWorker>(application).setWorkerFactory(factory).build(),
                TestListenableWorkerBuilder<DownloadWakeWorker>(application).setWorkerFactory(factory).build(),
                TestListenableWorkerBuilder<CleanupWorker>(application).setWorkerFactory(factory).build(),
                TestListenableWorkerBuilder<DownloadReconcileWorker>(application).setWorkerFactory(factory).build(),
                TestListenableWorkerBuilder<DownloadMoveWorker>(application).setWorkerFactory(factory).build(),
            )
        assertThat(workers.map { it::class }).containsExactly(
            DownloadLaneWorker::class,
            DownloadWakeWorker::class,
            CleanupWorker::class,
            DownloadReconcileWorker::class,
            DownloadMoveWorker::class,
        )
        assertThat(graph.databaseOpener.openState.value).isEqualTo(DatabaseOpenState.Pending)
    }

    @Test fun downloadHooksUseDocumentedStartupBands() {
        val hooks = graph.initializers.associate { it::class.simpleName to it.order }
        assertThat(hooks["DownloadChannelsInitializer"]).isEqualTo(10)
        assertThat(hooks["DownloadIndexInitializer"]).isEqualTo(130)
        assertThat(hooks["DownloadCleanupInitializer"]).isEqualTo(200)
        assertThat(hooks["DownloadReconcileInitializer"]).isEqualTo(210)
        assertThat(hooks["DownloadObserversInitializer"]).isEqualTo(310)
        assertThat(hooks["DeferredDeletesInitializer"]).isEqualTo(310)
        assertThat(graph.databaseOpener.openState.value).isEqualTo(DatabaseOpenState.Pending)
    }

    @Test fun startupAndDailyWorkersResolveAndRunRealCommonEngines() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            try {
                WorkManagerTestInitHelper.initializeTestWorkManager(
                    application,
                    Configuration
                        .Builder()
                        .setExecutor { it.run() }
                        .setTaskExecutor { it.run() }
                        .setWorkerFactory(graph.workerFactory)
                        .build(),
                )
                val startup =
                    TestListenableWorkerBuilder<DownloadReconcileWorker>(application)
                        .setWorkerFactory(graph.workerFactory)
                        .build()
                val daily =
                    TestListenableWorkerBuilder<CleanupWorker>(application)
                        .setWorkerFactory(graph.workerFactory)
                        .build()
                assertThat(startup.doWork()).isEqualTo(ListenableWorker.Result.success())
                assertThat(daily.doWork()).isEqualTo(ListenableWorker.Result.success())
                assertThat(graph.localMediaIndex.localUriOrNull(1)).isNull()
            } finally {
                graph.databaseOpener.requireDatabase().close()
            }
        }
}
