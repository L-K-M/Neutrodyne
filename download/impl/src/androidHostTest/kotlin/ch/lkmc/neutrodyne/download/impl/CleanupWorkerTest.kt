// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33, 34, 36])
class CleanupWorkerTest {
    private val context = RuntimeEnvironment.getApplication()
    private var daily: suspend () -> Unit = {}
    private var startup: suspend () -> Unit = {}
    private val runtime =
        AndroidDownloadRuntime(
            drain = { error("Maintenance cannot drain a lane") },
            reconcile = { error("Use the complete maintenance pass") },
            stop = { _, _ -> },
            wake = {},
            controller = { FakeDownloadController() },
            daily = { daily() },
            startup = { startup() },
        )

    @Test fun dailyWorkerRunsExactlyOneFullPass() =
        runTest {
            var calls = 0
            daily = { calls++ }
            assertEquals(ListenableWorker.Result.success(), cleanupWorker().doWork())
            assertEquals(1, calls)
        }

    @Test fun dailyFailureRetriesAndCancellationPropagates() =
        runTest {
            daily = { error("disk is unavailable") }
            assertEquals(ListenableWorker.Result.retry(), cleanupWorker().doWork())
            daily = { throw CancellationException("quota stop") }
            assertFailsWith<CancellationException> { cleanupWorker().doWork() }
        }

    @Test fun reconcileWorkerRunsStartupPassAndPreservesFailures() =
        runTest {
            var calls = 0
            startup = { calls++ }
            assertEquals(ListenableWorker.Result.success(), reconcileWorker().doWork())
            assertEquals(1, calls)
            startup = { error("planner failed") }
            assertEquals(ListenableWorker.Result.retry(), reconcileWorker().doWork())
            startup = { throw CancellationException("stopped") }
            assertFailsWith<CancellationException> { reconcileWorker().doWork() }
        }

    private val factory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? =
                when (workerClassName) {
                    CleanupWorker::class.java.name -> {
                        CleanupWorker(appContext, workerParameters, lazy { runtime })
                    }

                    DownloadReconcileWorker::class.java.name -> {
                        DownloadReconcileWorker(
                            appContext,
                            workerParameters,
                            lazy { runtime },
                            lazy { error("Startup owns scheduling") },
                        )
                    }

                    else -> {
                        null
                    }
                }
        }

    private fun cleanupWorker() = TestListenableWorkerBuilder<CleanupWorker>(context).setWorkerFactory(factory).build()

    private fun reconcileWorker() =
        TestListenableWorkerBuilder<DownloadReconcileWorker>(context).setWorkerFactory(factory).build()
}
