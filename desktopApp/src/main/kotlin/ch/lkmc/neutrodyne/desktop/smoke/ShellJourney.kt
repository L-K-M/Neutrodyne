// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.identity.WindowsAppIdentity
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.os.OsRegistrationInitializer
import ch.lkmc.neutrodyne.desktop.os.withoutOsRegistrations
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.playback.DesktopEnginePaths
import ch.lkmc.neutrodyne.desktop.shell.appDirsUnder
import coil3.SingletonImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The machinery smoke mode and the AOT training run share (11 Smoke mode): a throwaway
 * `AppDirs` root, the timed prologue steps (directories, build info, process identity,
 * graph, database, initializers, image loader), the 60 s watchdog and the close-down that
 * stops graph jobs before their files disappear. The prologue mirrors a real start-up
 * closely enough that the AOT archive records the classes the first launch loads — except
 * the host's OS registrations, which a controlled run never performs (11 Smoke mode
 * step 1): the graph's [OsRegistrationInitializer] members are skipped, every other
 * initializer runs.
 */
internal class ShellJourney(
    tempPrefix: String,
    private val watchdogTimeout: Duration = DEFAULT_WATCHDOG_TIMEOUT,
    createRoot: (String) -> Path = { Files.createTempDirectory(it) },
) {
    val timings = LinkedHashMap<String, Long>()
    private val root: Path = createRoot(tempPrefix)
    var graph: DesktopAppGraph? = null
        private set
    private val watchdog: Thread = armWatchdog()

    /** The steps every packaged-launch check runs before its own journey, in order. */
    fun runPrologue(): BuildInfo {
        val dirs = step("appDirs") { appDirsUnder(root).also { it.ensureCreated() } }
        val buildInfo = step("buildInfo") { BuildInfoLoader.load() }
        // 11 Start-up step 4's process AppUserModelID — the same pre-graph call
        // `DesktopShell.runOwner` makes. It is process-local, never an OS registration,
        // so the controlled journey keeps it: the media-session worker re-asserts the
        // configured value when a session opens (session_win.cpp).
        step("processIdentity") {
            WindowsAppIdentity.applyProcessIdentity(buildInfo, DesktopEnginePaths.current().ndmedia)
        }
        val startedGraph =
            step("graph") {
                val crashReporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer())
                createDesktopGraph(dirs, buildInfo, crashReporter)
            }
        graph = startedGraph
        // The database open gets its own step ahead of the bands, so the timing is real;
        // band 100's `DatabaseOpenInitializer` then returns at once (11 Smoke mode, M1a).
        step("database") { runBlocking { startedGraph.databaseOpener.awaitOpen() } }
        step("controlledSettings") {
            runBlocking { configureControlledJourneySettings(startedGraph.settingsRepository) }
        }
        step("initializers") {
            runBlocking {
                // Metro materialises a new set of new contributions on every `initializers`
                // access, so capture one set before diffing — a second call would make
                // `skipped` report every element.
                val all = startedGraph.initializers
                val journey = all.withoutOsRegistrations()
                val skipped = all - journey
                if (skipped.isNotEmpty()) {
                    Log.i(TAG) {
                        "controlled journey: skipped host OS registrations: " +
                            skipped.joinToString { it::class.simpleName ?: it::class.toString() }
                    }
                }
                runInitializers(journey)
            }
        }
        // The real host installs the artwork-aware Coil singleton at start-up (08); the smoke
        // and training windows compose the same window, so they provision the same loader
        // before the first frame.
        step("imageLoader") { SingletonImageLoader.setSafe(startedGraph.imageLoaderFactory) }
        return buildInfo
    }

    /** Runs [block], recording its wall time under [name] — also when it fails. */
    fun <T> step(
        name: String,
        block: () -> T,
    ): T {
        val mark = TimeSource.Monotonic.markNow()
        return try {
            block()
        } finally {
            timings[name] = mark.elapsedNow().inWholeMilliseconds
        }
    }

    /** Stops graph jobs, closes the database, then removes the throwaway root. */
    fun cleanUp() {
        val startedGraph = graph
        if (startedGraph != null) {
            runBlocking {
                // Collectors and lanes must finish before Room closes or its files disappear.
                val opened = startedGraph.databaseOpener.openState.value is DatabaseOpenState.Opened
                if (opened) startedGraph.jobRunner.stop(RUNNER_GRACE)
                startedGraph.appScope.coroutineContext[Job]?.cancelAndJoin()
                if (opened) startedGraph.databaseOpener.requireDatabase().close()
            }
        }
        // Compiled engine libraries protect their files and directories. Restore write
        // access inside this owned tree before deleting children; never follow a link.
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                if (!Files.isSymbolicLink(path)) {
                    check(
                        path.toFile().setWritable(true, true),
                    ) { "Cannot make temporary journey path writable: $path" }
                }
            }
        }
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    /** A daemon thread that ends a hanging run (11: the watchdog exits 1 after the budget). */
    private fun armWatchdog(): Thread {
        val watchdog =
            Thread({
                try {
                    Thread.sleep(watchdogTimeout.inWholeMilliseconds)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                System.err.println("journey exceeded $watchdogTimeout; exiting")
                Runtime.getRuntime().halt(EXIT_HALT)
            }, THREAD_NAME)
        watchdog.isDaemon = true
        watchdog.start()
        return watchdog
    }

    fun disarmWatchdog() {
        watchdog.interrupt()
        watchdog.join()
    }

    companion object {
        private const val TAG = "ShellJourney"
        private const val THREAD_NAME = "smoke-watchdog"
        private const val EXIT_HALT = 1

        /** 11's 60 s budget; the engine-armed smoke run passes a larger bound. */
        val DEFAULT_WATCHDOG_TIMEOUT = 60.seconds
        private val RUNNER_GRACE = 3.seconds
    }
}
