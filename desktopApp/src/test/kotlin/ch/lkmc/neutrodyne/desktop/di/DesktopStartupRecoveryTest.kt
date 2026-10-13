// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.database.DatabaseOpenException
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Runs the real shell graph and refresh lane, including the gate's exact retry operation. */
class DesktopStartupRecoveryTest {
    @Test
    fun healthyStartupStartsRefreshOnce() = checkStartup(Startup.HEALTHY)

    @Test
    fun failedOpenThenGateRetryStartsRefreshOnce() = checkStartup(Startup.RETRY)

    private fun checkStartup(startup: Startup) =
        runBlocking {
            val dirs = tempAppDirs("nd-startup-recovery").also { it.ensureCreated() }
            val buildInfo = BuildInfoLoader.load()
            val reporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer())
            val graph = createDesktopGraph(dirs, buildInfo, reporter)
            val runnerStarts = CopyOnWriteArrayList<DatabaseOpenState>()
            val requests = LinkedBlockingQueue<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/feed.xml") { exchange ->
                requests.add(exchange.requestURI.path)
                val body = "<rss version=\"2.0\"><channel><title>Recovery feed</title></channel></rss>".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val file = dirs.data.resolve(NeutrodyneDatabase.FILE_NAME)
                if (startup == Startup.RETRY) Files.createDirectory(file)
                val set =
                    graph.initializers
                        .map { initializer ->
                            object : AppInitializer {
                                override val order = initializer.order

                                override suspend fun run() {
                                    if (initializer::class.simpleName == "DesktopRunnerInitializer") {
                                        runnerStarts += graph.databaseOpener.openState.value
                                    }
                                    initializer.run()
                                }
                            }
                        }.toSet()
                val initializers = graph.appScope.launch { runInitializers(set) }
                if (startup == Startup.RETRY) {
                    withTimeout(10.seconds) { graph.databaseOpener.openState.first { it is DatabaseOpenState.Failed } }
                    // Leave the obstruction in place while the original startup loop unwinds.
                    delay(100)
                    assertFailsWith<DatabaseOpenException> { graph.databaseOpener.awaitOpen() }
                    Files.delete(file)
                    graph.databaseOpener.awaitOpen()
                }
                withTimeout(10.seconds) { initializers.join() }
                assertIs<DatabaseOpenState.Opened>(graph.databaseOpener.openState.value)

                // "Try again" and other awaitOpen callers must not reinstall a started runner.
                val result = graph.databaseOpener.awaitOpen()
                assertEquals(result, graph.databaseOpener.awaitOpen())
                assertTrue(graph.networkMonitor.status.value.isConnected, "loopback feed needs the online lane")
                val db = graph.databaseOpener.requireDatabase()
                val url = "http://127.0.0.1:${server.address.port}/feed.xml"
                val id =
                    db.podcastDao().insertPodcast(
                        podcastEntity(feedUrl = url) {
                            copy(initialFetch = false, nextRefreshAt = 0L, pagingComplete = true)
                        },
                    )
                graph.jobRunner.poke("refresh")
                assertNotNull(requests.poll(10, TimeUnit.SECONDS), "a due feed must be attempted after a poke")
                withTimeout(10.seconds) {
                    graph.jobRunner.status.first { it.getValue("refresh").runs > 0 }
                }
                assertNotNull(db.podcastDao().byId(id)?.lastAttemptAt)
                assertEquals(
                    0,
                    graph.jobRunner.status.value
                        .getValue("refresh")
                        .failures,
                )
                assertEquals(listOf(graph.databaseOpener.openState.value), runnerStarts)
            } finally {
                if (graph.databaseOpener.openState.value is DatabaseOpenState.Opened) {
                    graph.jobRunner.stop(3.seconds)
                }
                graph.appScope.coroutineContext[Job]?.cancel()
                // Bounded and best-effort: a stuck child must neither hang the suite nor mask the
                // test's own failure.
                withTimeoutOrNull(CLEANUP_TIMEOUT) { graph.appScope.coroutineContext[Job]?.join() }
                if (graph.databaseOpener.openState.value is DatabaseOpenState.Opened) {
                    graph.databaseOpener.requireDatabase().close()
                }
                server.stop(0)
                dirs.data.parent
                    .toFile()
                    .deleteRecursively()
            }
        }

    private enum class Startup { HEALTHY, RETRY }

    private companion object {
        val CLEANUP_TIMEOUT = 10.seconds
    }
}
