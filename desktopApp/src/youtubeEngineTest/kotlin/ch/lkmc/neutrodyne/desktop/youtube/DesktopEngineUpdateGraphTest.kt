// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import ch.lkmc.neutrodyne.desktop.system.LinuxMprisSession
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DesktopEngineStorePaths
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

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

    private var closed = false

    @After
    fun tearDown() = runBlocking { closeGraph() }

    private suspend fun closeGraph() {
        if (closed) return
        closed = true
        try {
            graph.osSurfaces.close()
        } finally {
            graph.appScope.coroutineContext[Job]?.cancelAndJoin()
            if (graph.databaseOpener.openState.value is ch.lkmc.neutrodyne.core.database.DatabaseOpenState.Opened) {
                graph.databaseOpener.requireDatabase().close()
            }
        }
    }

    @Test
    fun engineGraphTeardownReleasesTheMprisBusName() =
        runBlocking {
            val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
            assumeTrue(
                "private Linux session bus required",
                buildInfo.desktop?.os == DesktopOs.LINUX && address?.startsWith("unix:") == true,
            )
            graph.databaseOpener.awaitOpen()
            assertThat(graph.initializers).isNotEmpty()
            assertThat(graph.systemMediaSession).isInstanceOf(LinuxMprisSession::class.java)
            closeGraph()
            LinuxMprisSession.open(address).use { }
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

    @Test
    fun theTestTaskDeclaresItsEngineResourceInput() {
        val configured = System.getProperty("neutrodyne.engineResources")
        assertThat(configured).isNotNull()
        val resources = File(checkNotNull(configured))
        assertThat(resources.isDirectory).isTrue()
        assertThat(DesktopYouTubeBindingsModule.engineKeys(resourcePaths(resources))).isNotNull()
    }

    @Test
    fun missingTrustedKeyFilesDoNotFallBackToOtherResources() {
        val empty =
            dirs.cache
                .resolve("empty-keys")
                .toFile()
                .apply { mkdirs() }
        try {
            assertThrows(IOException::class.java) {
                DesktopYouTubeBindingsModule.engineKeys(resourcePaths(empty))
            }
        } finally {
            empty.deleteRecursively()
        }
    }

    @Test
    fun malformedSigningKeysStillFailClosed() {
        val resources =
            dirs.cache
                .resolve("malformed-keys")
                .toFile()
                .apply { mkdirs() }
        try {
            val keys = resources.resolve("keys").apply { mkdirs() }
            keys.resolve("engine-manifest-ed25519.pub").writeText("invalid-ed25519-key")
            val root = File(checkNotNull(System.getProperty("neutrodyne.rootDir")))
            root
                .resolve("youtube/ytdlp/keys/yt-dlp-release-key.asc")
                .copyTo(keys.resolve("yt-dlp-release-key.asc"))
            assertThrows(IllegalArgumentException::class.java) {
                DesktopYouTubeBindingsModule.engineKeys(resourcePaths(resources))
            }
        } finally {
            resources.deleteRecursively()
        }
    }

    private fun resourcePaths(resources: File): DesktopEngineStorePaths =
        DesktopEngineStorePaths(dirs, { resources }, { null })
}
