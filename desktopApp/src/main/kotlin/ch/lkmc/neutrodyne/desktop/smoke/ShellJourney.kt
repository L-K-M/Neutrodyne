// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.core.common.AppDirs
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
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

    /** Stops jobs and OS surfaces, closes the database, then removes the throwaway root. */
    fun cleanUp() {
        val startedGraph = graph
        if (startedGraph != null) {
            runBlocking {
                // Collectors and lanes must finish before Room closes or its files disappear.
                val opened = startedGraph.databaseOpener.openState.value is DatabaseOpenState.Opened
                if (opened) startedGraph.jobRunner.stop(RUNNER_GRACE)
                runCatching { startedGraph.osSurfaces.close() }
                startedGraph.appScope.coroutineContext[Job]?.cancelAndJoin()
                if (opened) startedGraph.databaseOpener.requireDatabase().close()
            }
        }
        // Compiled engine libraries protect their files and directories. Restore write
        // access inside this owned tree before deleting children; never follow a link.
        Files.walk(root).use { paths -> paths.forEach(::restoreWritable) }
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

        /**
         * `dos:readonly` is the platform's read-only marker only on a DOS file system. A
         * Unix store can advertise both POSIX and DOS views. Keep its existing POSIX
         * permission semantics and consult the DOS marker on Windows alone.
         */
        private val DOS_HOST = AppDirs.DesktopOs.current() == AppDirs.DesktopOs.WINDOWS

        /**
         * Restores write access on [path] inside the owned journey tree so the bottom-up
         * delete can remove it; a symbolic link is never followed, its target lives
         * outside this tree.
         *
         * `File.setWritable` is deliberately not the guard: on Windows it reports failure
         * even for directories that are perfectly writable — the hosted windows-x64 AOT
         * training run failed this way on a freshly created temporary root. Actual
         * writability can short-circuit POSIX restoration. On Windows a directory can
         * report writable while its DOS marker is still read-only, so always clear and
         * read back that marker: the owner-write bit on a POSIX store, `dos:readonly` on
         * a DOS host. A marker that still reads read-only fails the cleanup instead of
         * letting the delete pass hit it unprepared.
         */
        internal fun restoreWritable(path: Path) {
            if (Files.isSymbolicLink(path)) return
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (!DOS_HOST && Files.isWritable(path)) return
            val restored =
                when {
                    Files.getFileStore(path).supportsFileAttributeView("posix") -> restorePosixWritable(path)
                    DOS_HOST -> restoreDosWritable(path)
                    else -> restoreFallbackWritable(path)
                }
            if (!restored) {
                throw IOException("Cannot make temporary journey path writable: $path")
            }
        }

        /** The owner-write bit is the read-only marker the engine store sets on POSIX. */
        private fun restorePosixWritable(path: Path): Boolean {
            val view =
                Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    ?: return restoreFallbackWritable(path)
            val permissions = view.readAttributes().permissions()
            if (PosixFilePermission.OWNER_WRITE in permissions) return true
            view.setPermissions(permissions + PosixFilePermission.OWNER_WRITE)
            return PosixFilePermission.OWNER_WRITE in view.readAttributes().permissions()
        }

        /** `dos:readonly` is the same marker through the DOS attribute view. */
        private fun restoreDosWritable(path: Path): Boolean {
            val view =
                Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    ?: return restoreFallbackWritable(path)
            if (!view.readAttributes().isReadOnly) return true
            view.setReadOnly(false)
            return !view.readAttributes().isReadOnly
        }

        /**
         * A store that is neither POSIX nor DOS gets `File.setWritable` — with its
         * return value verified against actual writability, since the boolean does not
         * reliably mean success or failure.
         */
        private fun restoreFallbackWritable(path: Path): Boolean =
            path.toFile().setWritable(true, true) || Files.isWritable(path)
    }
}
