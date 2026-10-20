// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.CrashContext
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.NewEpisodePoster
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.NewEpisodeNotice
import ch.lkmc.neutrodyne.core.network.DesktopNetworkMonitor
import ch.lkmc.neutrodyne.core.network.DesktopNetworkMonitorBindings
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import ch.lkmc.neutrodyne.desktop.youtube.DesktopYouTubeBindingsModule
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.download.api.RequestResult
import com.sun.net.httpserver.HttpServer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real graph, persistent Room, real RSS client and files across two fresh process graphs. */
class DesktopDownloadRestartTest {
    @Test fun gracefulQuitAtHalfResumesWithoutTornTailTrimming() = restart(Restart.GRACEFUL)

    @Test fun staleDesktopTokenAtHalfResetsAndTrimsTheTornTail() = restart(Restart.STALE_TOKEN)

    private fun restart(mode: Restart) =
        runBlocking {
            val dirs = tempAppDirs("nd-download-restart").also { it.ensureCreated() }
            val bytes = ByteArray(TOTAL_BYTES) { (it % 251).toByte() }.also { "ID3".toByteArray().copyInto(it) }
            val requests = CopyOnWriteArrayList<String>()
            val release = CountDownLatch(1)
            val executor = Executors.newCachedThreadPool()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = executor
            server.createContext("/episode.mp3") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                requests += range ?: "full"
                val offset = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                exchange.responseHeaders.add("Content-Type", "audio/mpeg")
                exchange.responseHeaders.add("ETag", "\"stable\"")
                if (range != null) {
                    exchange.responseHeaders.add(
                        "Content-Range",
                        "bytes $offset-${bytes.lastIndex}/${bytes.size}",
                    )
                }
                exchange.sendResponseHeaders(if (range == null) 200 else 206, (bytes.size - offset).toLong())
                try {
                    exchange.responseBody.use { body ->
                        if (range == null) {
                            body.write(bytes, 0, HALF_BYTES)
                            body.flush()
                            release.await(SERVER_HOLD_SECONDS, TimeUnit.SECONDS)
                        } else {
                            body.write(bytes, offset, bytes.size - offset)
                        }
                    }
                } catch (_: java.io.IOException) {
                    // The first socket is closed by the application's graceful quit.
                } finally {
                    exchange.close()
                }
            }
            server.start()
            var graph: DownloadJourneyGraph? = null
            try {
                val first = open(dirs)
                graph = first
                val db = first.db
                val podcast = db.podcastDao().insertPodcast(podcastEntity { copy(nextRefreshAt = Long.MAX_VALUE) })
                val url = "http://127.0.0.1:${server.address.port}/episode.mp3"
                val id =
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = podcast) {
                            copy(
                                enclosureUrl = url,
                                enclosureType = "audio/mpeg",
                                enclosureLength = TOTAL_BYTES.toLong(),
                            )
                        },
                    )
                first.runner.start()
                assertIs<RequestResult.Queued>(first.controller.request(listOf(id), DownloadLane.MANUAL, null))
                val part = dirs.downloadsDefault.resolve(".partial/$id.part")
                withTimeout(TRANSFER_TIMEOUT_MS) {
                    while (!Files.exists(part) || Files.size(part) != HALF_BYTES.toLong()) delay(POLL_MS)
                }
                first.runner.stop(RUNNER_GRACE)
                val stopped = checkNotNull(db.downloadDao().byEpisode(id))
                assertEquals(DownloadState.QUEUED, stopped.state)
                assertEquals(STOP_APP_QUIT, stopped.lastStopReason)
                assertEquals(HALF_BYTES.toLong(), Files.size(part))
                assertNull(stopped.runnerToken)
                if (mode == Restart.STALE_TOKEN) {
                    db.downloadDao().claim(id, "desk:dead-process")
                    db.downloadDao().transition(
                        stopped.copy(state = DownloadState.DOWNLOADING, lastStopReason = null),
                        DownloadState.RESOLVING,
                        "desk:dead-process",
                    )
                }
                close(first)
                graph = null
                assertEquals(listOf("full"), requests.toList())
                val second = open(dirs)
                graph = second
                // Merely opening a fresh graph does no transfer work until the runner starts.
                assertEquals(listOf("full"), requests.toList())
                second.runner.start()
                second.runner.poke("downloads-manual")
                val completed =
                    withTimeout(TRANSFER_TIMEOUT_MS) {
                        var row: DownloadEntity?
                        do {
                            delay(POLL_MS)
                            row = second.db.downloadDao().byEpisode(id)
                        }
                        while (
                            row?.state != DownloadState.COMPLETED ||
                            second.index.localUriOrNull(id) != row.finalUri
                        )
                        checkNotNull(row)
                    }
                val expected = HALF_BYTES - if (mode == Restart.STALE_TOKEN) TORN_TAIL_BYTES else 0
                assertEquals(listOf("full", "bytes=$expected-"), requests.toList())
                assertEquals(TOTAL_BYTES.toLong(), completed.totalBytes)
                assertEquals(0, completed.attempt)
                assertEquals(completed.finalUri, second.index.localUriOrNull(id))
                val finalFile = dirs.downloadsDefault.resolve(checkNotNull(completed.relativePath))
                assertTrue(bytes.contentEquals(Files.readAllBytes(finalFile)))
                second.runner.stop(RUNNER_GRACE)
            } finally {
                graph?.let {
                    it.runner.stop(RUNNER_GRACE)
                    close(it)
                }
                release.countDown()
                server.stop(0)
                executor.shutdownNow()
                dirs.data.parent
                    .toFile()
                    .deleteRecursively()
            }
        }

    private suspend fun open(dirs: AppDirs): DownloadJourneyGraph {
        val info = BuildInfoLoader.load()
        val reporter = DesktopCrashReporter(dirs, info, DesktopClock, RecentLogBuffer())
        val graph = createGraphFactory<DownloadJourneyGraph.Factory>().create(dirs, info, reporter)
        runInitializers(graph.initializers.filter { it.order <= 130 }.toSet())
        return graph
    }

    private suspend fun close(graph: DownloadJourneyGraph) {
        graph.scope.cancel()
        graph.scope.coroutineContext[Job]?.join()
        graph.db.close()
    }

    private enum class Restart { GRACEFUL, STALE_TOKEN }

    private companion object {
        const val TOTAL_BYTES = 512 * 1024
        const val HALF_BYTES = TOTAL_BYTES / 2
        const val TORN_TAIL_BYTES = 64 * 1024
        const val STOP_APP_QUIT = -6
        const val TRANSFER_TIMEOUT_MS = 30_000L
        const val SERVER_HOLD_SECONDS = 30L
        const val POLL_MS = 10L
        val RUNNER_GRACE = 3.seconds
    }
}

@DependencyGraph(
    AppScope::class,
    bindingContainers = [DesktopYouTubeBindingsModule::class],
    excludes = [DesktopNetworkMonitorBindings::class],
)
internal interface DownloadJourneyGraph {
    val controller: DownloadController
    val index: LocalMediaIndex
    val runner: DesktopJobRunner
    val db: NeutrodyneDatabase
    val initializers: Set<AppInitializer>

    @ApplicationScope val scope: CoroutineScope

    @Binds val DesktopCrashReporter.asCrashReporter: CrashReporter

    @Binds val DesktopCrashReporter.asCrashContext: CrashContext

    @Provides fun database(opener: DatabaseOpener): NeutrodyneDatabase = opener.requireDatabase()

    @Provides fun network(): NetworkMonitor = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)

    @Provides fun monitor(
        @ApplicationScope scope: CoroutineScope,
        power: PowerMonitor,
    ): DesktopNetworkMonitor = DesktopNetworkMonitorBindings.desktopNetworkMonitor(scope, power)

    @Provides fun notifier(): DesktopNotifier =
        object : DesktopNotifier {
            override suspend fun post(n: DesktopNotification) = Unit

            override fun cancel(id: String) = Unit
        }

    @Provides fun poster(): NewEpisodePoster =
        object : NewEpisodePoster {
            override suspend fun post(notices: List<NewEpisodeNotice>) = Unit

            override suspend fun removePodcasts(podcastIds: List<Long>) = Unit
        }

    @DependencyGraph.Factory fun interface Factory {
        fun create(
            @Provides dirs: AppDirs,
            @Provides info: BuildInfo,
            @Provides reporter: DesktopCrashReporter,
        ): DownloadJourneyGraph
    }
}
