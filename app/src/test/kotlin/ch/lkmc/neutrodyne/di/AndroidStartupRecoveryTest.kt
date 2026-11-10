// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import android.os.Looper
import android.os.StrictMode
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.database.DatabaseOpenException
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Executes the production shell graph; the test task supplies the bundled driver's host JNI. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AndroidStartupRecoveryTest {
    @Test
    fun healthyStartupInstallsRefreshOnce() = checkStartupAndPolicyRestoration(Startup.HEALTHY)

    @Test
    fun failedOpenThenGateRetryInstallsRefreshOnce() = checkStartupAndPolicyRestoration(Startup.RETRY)

    private fun checkStartupAndPolicyRestoration(startup: Startup) {
        val vmPolicy = StrictMode.getVmPolicy()
        val threadMask = ReflectionHelpers.getField<Int>(StrictMode.getThreadPolicy(), "mask")

        checkStartup(startup)

        assertSame(vmPolicy, StrictMode.getVmPolicy(), "startup must restore the process-wide VM policy")
        assertEquals(threadMask, ReflectionHelpers.getField<Int>(StrictMode.getThreadPolicy(), "mask"))
    }

    private fun checkStartup(startup: Startup) {
        val vmPolicy = StrictMode.getVmPolicy()
        val threadPolicy = StrictMode.getThreadPolicy()
        val app: Application = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val graph = createGraphFactory<AndroidAppGraph.Factory>().create(app)
        val file = app.getDatabasePath(NeutrodyneDatabase.FILE_NAME)
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        val observersBefore = lifecycle.observerCount
        val postOpenRuns = CopyOnWriteArrayList<Pair<String, DatabaseOpenState>>()
        try {
            assertTrue(!file.exists(), "a fresh database path is required")
            if (startup == Startup.RETRY) assertTrue(file.mkdirs())
            // Constructing the whole initializer set on main exercises the lazy dependency rule.
            // Pre-sort by the documented (order, qualifiedName) comparator: the wrappers below
            // are anonymous and lose qualifiedName, so the runner's tie-break would otherwise
            // fall to identity-hash toString order — which shifts with allocation order.
            val set =
                graph.initializers
                    .sortedWith(compareBy({ it.order }, { it::class.qualifiedName ?: it::class.toString() }))
                    .map { initializer ->
                        object : AppInitializer {
                            override val order = initializer.order

                            override suspend fun run() {
                                val name = checkNotNull(initializer::class.simpleName)
                                if (name in POST_OPEN_INITIALIZERS) {
                                    postOpenRuns += name to graph.databaseOpener.openState.value
                                }
                                initializer.run()
                            }
                        }
                    }.toSet()
            val initializers = graph.appScope.launch { runInitializers(set) }
            if (startup == Startup.RETRY) {
                pumpUntil { graph.databaseOpener.openState.value is DatabaseOpenState.Failed }
                // Drain main-thread registration while the original failed startup unwinds.
                pumpFor(100)
                assertEquals(0, periodicWork().size)
                assertEquals(observersBefore, lifecycle.observerCount)
                runBlocking {
                    assertFailsWith<DatabaseOpenException> { graph.databaseOpener.awaitOpen() }
                }
                assertTrue(file.delete())
                runBlocking { graph.databaseOpener.awaitOpen() }
            }
            pumpUntil { initializers.isCompleted }
            assertIs<DatabaseOpenState.Opened>(graph.databaseOpener.openState.value)
            // Refresh, the lazy player connection, the sync foreground trigger and manual
            // downloads each install one process observer.
            assertEquals(1 to PROCESS_OBSERVERS, periodicWork().size to (lifecycle.observerCount - observersBefore))
            val work = periodicWork().single()

            // The gate and concurrent open callers only reopen the gate, never rerun bands.
            runBlocking {
                val result = graph.databaseOpener.awaitOpen()
                assertEquals(result, graph.databaseOpener.awaitOpen())
            }
            pumpFor(100)
            assertEquals(work.id, periodicWork().single().id)
            assertEquals(observersBefore + PROCESS_OBSERVERS, lifecycle.observerCount)
            assertEquals(
                POST_OPEN_INITIALIZERS.map { it to graph.databaseOpener.openState.value },
                postOpenRuns,
            )
        } finally {
            try {
                graph.appScope.cancel()
                // Best-effort teardown: a cleanup timeout must not replace the test's own failure.
                runCatching { pumpUntil { graph.appScope.coroutineContext[Job]?.isCompleted != false } }
                if (graph.databaseOpener.openState.value is DatabaseOpenState.Opened) {
                    graph.databaseOpener.requireDatabase().close()
                }
                WorkManagerTestInitHelper.closeWorkDatabase()
            } finally {
                // The real debug initializer installs a fatal process-wide policy. Keep it
                // within this fixture after its background work has stopped.
                StrictMode.setVmPolicy(vmPolicy)
                StrictMode.setThreadPolicy(threadPolicy)
            }
        }
    }

    private fun periodicWork() =
        WorkManager
            .getInstance(RuntimeEnvironment.getApplication())
            .getWorkInfosForUniqueWork("refresh-periodic")
            .get(5, TimeUnit.SECONDS)

    private fun pumpUntil(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_BOUND_SECONDS)
        while (!ready()) {
            check(System.nanoTime() < deadline) { "startup did not finish" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pumpFor(millis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        pumpUntil { System.nanoTime() >= deadline }
    }

    private enum class Startup { HEALTHY, RETRY }

    private companion object {
        /**
         * A hang detector, not a performance budget: each case took up to 12 s idle and up to
         * 28 s under CPU contention, where a 10 s bound on the Robolectric start-up failed.
         */
        const val STARTUP_BOUND_SECONDS = 60L
        const val PROCESS_OBSERVERS = 4

        /**
         * Bands that must never run while the database is still closed — the M1b credential load
         * (120) waits on the Keystore store's `credential` emission and the refresh installs
         * (200/220) on the opened database. Recorded in run order; on the retry path every one
         * must see `Opened`, proving a failed open keeps them parked behind band 100's retry wait.
         */
        val POST_OPEN_INITIALIZERS =
            listOf(
                // 05 First-launch restore sits at 110: its Provider deps dereference the
                // open result only inside `run`, which must wait on Opened.
                "FirstLaunchRestoreInitializer",
                "CredentialLoadInitializer",
                "DownloadIndexInitializer",
                // 10 Scheduling on Android arms `sync-periodic` and `SyncScheduler` at 200,
                // behind the opened database like the other scheduling-band initializers.
                // Both sit at 200: the (order, qualifiedName) tie-break puts Periodic first.
                "PeriodicRefreshInitializer",
                "SyncSchedulingInitializer",
                "RefreshForegroundObserverInitializer",
                // 10 Scheduling on Android's held-batch notice: the collector resolves
                // `SyncController` at 230, which reaches the opened database.
                "SyncHeldNotifier",
            )
    }
}
