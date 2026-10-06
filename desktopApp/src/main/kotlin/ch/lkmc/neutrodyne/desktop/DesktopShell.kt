// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.log.ConsoleSink
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.log.RollingFileSink
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.HandoffOutcome
import ch.lkmc.neutrodyne.desktop.shell.HandoffRequest
import ch.lkmc.neutrodyne.desktop.shell.InstanceHandshake
import ch.lkmc.neutrodyne.desktop.shell.SessionFile
import ch.lkmc.neutrodyne.desktop.shell.SessionState
import ch.lkmc.neutrodyne.desktop.shell.SingleInstanceLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * The shell start-up in 11's order, up to the point where the window would open. The M0b process
 * owns the lock, serves hand-offs and stays alive until it receives SIGTERM (a JVM shutdown hook
 * performs the clean shutdown); the window, the AWT "not responding"/"has to close" dialogs,
 * `ShutdownCoordinator`, the queued-input routing through `IntentRouter`, the Windows
 * AppUserModelID call and the `desktop.language` locale all arrive with the window milestone —
 * recorded in 11's implementation notes (2026-10-06).
 */
internal object DesktopShell {
    private const val TAG = "Main"

    /** 11's exit codes: 0 normal, 2 the owner does not answer, 3 a data directory failure. */
    private const val EXIT_OK = 0
    private const val EXIT_OWNER_UNREACHABLE = 2
    private const val EXIT_DATA_DIR_FAILURE = 3

    private const val BACKGROUND_FLAG = "--background"
    private const val FLAG_PREFIX = "--"
    private const val HS_ERR_GLOB = "hs_err_pid*.log"
    private const val SHUTDOWN_THREAD = "nd-shutdown"

    fun start(rawArgs: Array<String>): Int {
        // Step 1: split `--background` (start at login) from inputs; unknown flags ignored.
        var background = false
        val inputs = mutableListOf<String>()
        for (arg in rawArgs) {
            when {
                arg == BACKGROUND_FLAG -> background = true
                arg.startsWith(FLAG_PREFIX) -> Log.w(TAG) { "ignoring unknown flag $arg" }
                else -> inputs += arg
            }
        }

        val buildInfo = BuildInfoLoader.load()
        val clock = DesktopClock
        val dirs = AppDirs.current()

        // Only `state` exists before the lock (11 start-up step 2).
        try {
            Files.createDirectories(dirs.state)
        } catch (e: IOException) {
            return dataDirFailure(dirs.state, e)
        }

        // Step 3: lock, or hand off to the owner and exit before AWT is initialised.
        val lock = SingleInstanceLock(
            dirs = dirs,
            pid = ProcessHandle.current().pid(),
            startedAtMs = clock.now(),
            versionName = buildInfo.versionName,
        )
        return when (lock.tryAcquire()) {
            SingleInstanceLock.Acquire.HeldByOther -> handOffToOwner(dirs, inputs, buildInfo)
            SingleInstanceLock.Acquire.Acquired -> runOwner(dirs, inputs, background, buildInfo, lock)
        }
    }

    /** The second launch: deliver its inputs, then exit 0 — or 2 when the owner never answers. */
    private fun handOffToOwner(dirs: AppDirs, inputs: List<String>, buildInfo: BuildInfo): Int {
        val handshake = InstanceHandshake(dirs, SecureRandom(), buildInfo.versionName)
        val token = InstanceHandshake.readToken(dirs) ?: ""
        val request = HandoffRequest.fromLaunchArgs(
            token = token,
            args = inputs,
            cwd = System.getProperty("user.dir") ?: "",
        )
        return if (handshake.send(request) == HandoffOutcome.Delivered) {
            EXIT_OK
        } else {
            // 11's AWT dialog arrives with the window; stderr stands in until then.
            System.err.println(
                "Neutrodyne is already running but is not responding. Wait a moment and try again, " +
                    "or end it in Task Manager / Activity Monitor / your system monitor.",
            )
            EXIT_OWNER_UNREACHABLE
        }
    }

    private fun runOwner(
        dirs: AppDirs,
        inputs: List<String>,
        background: Boolean,
        buildInfo: BuildInfo,
        lock: SingleInstanceLock,
    ): Int {
        // Step 4: the other directories (0700 on POSIX), the rolling log, the crash handler,
        // the previous session review and the new session file.
        try {
            dirs.ensureCreated()
        } catch (e: IOException) {
            return dataDirFailure(dirs.data, e)
        }
        if (background) Log.i(TAG) { "--background start; the window milestone starts iconified" }

        val recentLogs = RecentLogBuffer()
        val fileSink = RollingFileSink(dirs.logs, if (buildInfo.debug) LogLevel.DEBUG else LogLevel.INFO)
        val sinks = if (buildInfo.debug) arrayOf(recentLogs, fileSink, ConsoleSink) else arrayOf(recentLogs, fileSink)
        Log.install(*sinks)
        Log.i(TAG) { "Neutrodyne ${buildInfo.versionName} starting (install kind ${buildInfo.desktop?.installKind?.wire})" }

        val crashReporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, recentLogs)
        crashReporter.installAsDefaultExceptionHandler()
        reviewPreviousSession(dirs, crashReporter)
        SessionFile.write(
            dirs.state,
            SessionState(
                pid = ProcessHandle.current().pid(),
                startedAtMs = DesktopClock.now(),
                versionName = buildInfo.versionName,
                cleanExit = false,
            ),
        )

        // Steps 5–6 (M0b shape): the graph, the initializer bands, the hand-off server. The
        // database open on IO and the window join at their milestones.
        val graph = createDesktopGraph(dirs, buildInfo, crashReporter)
        graph.appScope.launch { runInitializers(graph.initializers) }

        val handshakeDispatcher = Executors
            .newThreadPerTaskExecutor(Thread.ofVirtual().name("nd-handshake").factory())
            .asCoroutineDispatcher()
        val handshakeScope = CoroutineScope(SupervisorJob() + handshakeDispatcher)
        val handshake = InstanceHandshake(dirs, SecureRandom(), buildInfo.versionName)
        val serveJob = handshakeScope.launch {
            handshake.serve { request -> onHandoff(request) }
        }
        if (inputs.isNotEmpty()) {
            Log.i(TAG) { "${inputs.size} first-launch input(s) queued for the window" }
        }

        // No window yet (11 step 6 is pending): stay alive serving hand-offs until SIGTERM. The
        // shutdown hook runs the clean shutdown; main parks for the rest of the process's life.
        Runtime.getRuntime().addShutdownHook(Thread(
            {
                Log.i(TAG) { "shutdown signal received" }
                try {
                    runBlocking { serveJob.cancelAndJoin() } // serve's finally removes port/token files
                } catch (_: CancellationException) {
                    // The hook's own cancellation: the files were removed by serve's finally either way.
                }
                handshakeScope.cancel()
                handshakeDispatcher.close()
                graph.appScope.cancel()
                SessionFile.write(
                    dirs.state,
                    SessionState(
                        pid = ProcessHandle.current().pid(),
                        startedAtMs = DesktopClock.now(),
                        versionName = buildInfo.versionName,
                        cleanExit = true,
                    ),
                )
                lock.close()
                fileSink.close()
            },
            SHUTDOWN_THREAD,
        ))
        CountDownLatch(1).await()
        return EXIT_OK
    }

    /**
     * The owner's hand-off application: relative paths resolve against `cwd`; the inputs go to
     * `DesktopOpenHandler` and `IntentRouter` once the window exists (11 Hand-off row) — M0b
     * records them in the log.
     */
    private fun onHandoff(request: HandoffRequest) {
        Log.i(TAG) { "hand-off: ${request.args.size} input(s) from ${request.cwd}, queued for the window" }
    }

    /** 11 Crash files table: an unclean previous session with an `hs_err` file is a JVM crash. */
    private fun reviewPreviousSession(dirs: AppDirs, crashReporter: DesktopCrashReporter) {
        val previous = SessionFile.read(dirs.state) ?: return
        if (previous.cleanExit) return
        val hsErr = newestHsErrFile(dirs.state)
        if (hsErr != null) {
            Log.w(TAG) { "previous session ended in a JVM crash; writing the summary report" }
            crashReporter.recordJvmCrash(hsErr)
        } else {
            Log.w(TAG) { "previous session ended without shutdown" }
        }
    }

    private fun newestHsErrFile(stateDir: Path): Path? =
        try {
            Files.newDirectoryStream(stateDir, HS_ERR_GLOB).use { stream ->
                stream.maxByOrNull { file -> runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L) }
            }
        } catch (_: IOException) {
            null
        }

    /** 11 Shell failure modes: name the directory and the error; nothing is written elsewhere. */
    private fun dataDirFailure(dir: Path, e: IOException): Int {
        System.err.println("Neutrodyne cannot create or write its directory $dir: ${e.message}")
        return EXIT_DATA_DIR_FAILURE
    }
}
