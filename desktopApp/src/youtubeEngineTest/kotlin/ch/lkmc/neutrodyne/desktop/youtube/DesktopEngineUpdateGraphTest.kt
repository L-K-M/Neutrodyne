// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * MD3 AC4 graph proof: the engine-backed desktop graph binds the real update lane and
 * scheduler chain — `engine-update` in the runner's lane set and the update initializer in
 * the app initializer set. On the pre-slice binding (`NoLaneEngineUpdateHooks`) both are
 * absent; this is the RED→GREEN seam the desktop-only no-lane state could not satisfy.
 */
class DesktopEngineUpdateGraphTest {
    private val dirs = tempAppDirs().also { it.ensureCreated() }
    private val buildInfo = BuildInfoLoader.load()
    private val reporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer())
    private val graph = createDesktopGraph(dirs, buildInfo, reporter)

    @After
    fun tearDown() =
        runBlocking {
            graph.appScope.coroutineContext[Job]?.cancelAndJoin()
            if (graph.databaseOpener.openState.value is ch.lkmc.neutrodyne.core.database.DatabaseOpenState.Opened) {
                graph.databaseOpener.requireDatabase().close()
            }
        }

    /**
     * `jobRunner` resolves the lane set through `provideDatabase`, which blocks until the
     * band-100 open finishes — the same gate `DesktopAppGraphTest`'s lane assertions take.
     * The pool stays open until [tearDown] joins the collectors the resolution launched
     * (`SyncControllerImpl.init`'s `refreshStatus`, `DefaultYouTubeHealth`, the rollback
     * monitor): closing it earlier races those appScope reads — a "Connection pool is
     * closed" fault reaches the debug fatal handler and kills the test worker.
     */
    @Test
    fun theEngineUpdateLaneJoinsTheRunnerSet() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            assertThat(graph.jobRunner.status.value.keys).contains("engine-update")
            assertThat(
                graph.initializers
                    .count { it::class.simpleName == "DesktopEngineUpdateInitializer" },
            ).isEqualTo(1)
        }

    @Test
    fun theYoutubeEngineResolvesThroughTheRealUpdaterChain() {
        // Resolving pulls BundledEngineStore, StdioYtxTransport, DesktopEngineCompiler,
        // EngineRollbackMonitor and EngineUpdater through the module's lazy seams.
        assertThat(graph.youtubeEngine::class.simpleName).isEqualTo("YtDlpEngine")
    }
}
