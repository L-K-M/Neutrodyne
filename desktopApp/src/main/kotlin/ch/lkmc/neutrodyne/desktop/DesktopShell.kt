// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import androidx.compose.ui.window.application
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopOs
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
import ch.lkmc.neutrodyne.desktop.shell.ShellDialogs
import ch.lkmc.neutrodyne.desktop.shell.ShutdownCoordinator
import ch.lkmc.neutrodyne.desktop.shell.SingleInstanceLock
import ch.lkmc.neutrodyne.desktop.window.DesktopMenuActions
import ch.lkmc.neutrodyne.desktop.window.NeutrodyneWindow
import ch.lkmc.neutrodyne.desktop.window.WindowActivator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The shell start-up in 11's order: `AppDirs` → `SingleInstanceLock` → directories, log, crash
 * files, session → the graph and initializer bands → the window (the AWT event thread) → the
 * clean shutdown when it closes. The owner serves hand-offs for the whole process life; SIGTERM
 * runs the same [ShutdownCoordinator] through its shutdown hook. Input routing through
 * `DesktopOpenHandler`/`IntentRouter`, the Windows AppUserModelID call and the `desktop.language`
 * locale of step 4 arrive with their milestones (11's implementation notes, 2026-10-06).
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

    /** The two [ShutdownCoordinator] callers; both mean a normal exit. */
    private const val REASON_SIGNAL = "signal"
    private const val REASON_WINDOW_CLOSED = "window closed"

    fun start(rawArgs: Array<String>): Int {
        // Step 1: split `--background` (start at login) from inputs. No sinks are installed yet,
        // so unknown flags are collected and logged once the owner side opens the log.
        var background = false
        val inputs = mutableListOf<String>()
        val unknownFlags = mutableListOf<String>()
        for (arg in rawArgs) {
            when {
                arg == BACKGROUND_FLAG -> background = true
                arg.startsWith(FLAG_PREFIX) -> unknownFlags += arg
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
        val lock =
            SingleInstanceLock(
                dirs = dirs,
                pid = ProcessHandle.current().pid(),
                startedAtMs = clock.now(),
                versionName = buildInfo.versionName,
            )
        return when (lock.tryAcquire()) {
            SingleInstanceLock.Acquire.HeldByOther -> handOffToOwner(dirs, inputs, buildInfo)
            SingleInstanceLock.Acquire.Acquired -> runOwner(dirs, inputs, unknownFlags, background, buildInfo, lock)
        }
    }

    /** The second launch: deliver its inputs, then exit 0 — or 2 when the owner never answers. */
    private fun handOffToOwner(
        dirs: AppDirs,
        inputs: List<String>,
        buildInfo: BuildInfo,
    ): Int {
        val handshake = InstanceHandshake(dirs, SecureRandom(), buildInfo.versionName)
        val token = InstanceHandshake.readToken(dirs) ?: ""
        val request =
            HandoffRequest.fromLaunchArgs(
                token = token,
                args = inputs,
                cwd = System.getProperty("user.dir") ?: "",
            )
        return if (handshake.send(request) == HandoffOutcome.Delivered) {
            EXIT_OK
        } else {
            ShellDialogs.showError(ShellDialogs.OWNER_UNREACHABLE)
            EXIT_OWNER_UNREACHABLE
        }
    }

    private fun runOwner(
        dirs: AppDirs,
        inputs: List<String>,
        unknownFlags: List<String>,
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

        val recentLogs = RecentLogBuffer()
        val fileSink = RollingFileSink(dirs.logs, if (buildInfo.debug) LogLevel.DEBUG else LogLevel.INFO)
        val sinks = if (buildInfo.debug) arrayOf(recentLogs, fileSink, ConsoleSink) else arrayOf(recentLogs, fileSink)
        Log.install(*sinks)
        for (flag in unknownFlags) Log.w(TAG) { "ignoring unknown flag $flag" }
        if (background) Log.i(TAG) { "--background start; the window starts iconified" }
        Log.i(
            TAG,
        ) { "Neutrodyne ${buildInfo.versionName} starting (install kind ${buildInfo.desktop?.installKind?.wire})" }

        val crashReporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, recentLogs)
        crashReporter.installAsDefaultExceptionHandler()
        reviewPreviousSession(dirs, crashReporter)
        // The session's start instant, also carried by the clean-exit rewrite at shutdown.
        val sessionStartedAtMs = DesktopClock.now()
        SessionFile.write(
            dirs.state,
            SessionState(
                pid = ProcessHandle.current().pid(),
                startedAtMs = sessionStartedAtMs,
                versionName = buildInfo.versionName,
                cleanExit = false,
            ),
        )

        // Step 4's macOS AWT facts: the menu bar lives in the screen, the app menu carries our
        // name and tray images may be template images. Before any AWT class initialises.
        installMacosAwtProperties(buildInfo)

        // Step 5: the graph, the initializer bands and the hand-off server.
        val graph = createDesktopGraph(dirs, buildInfo, crashReporter)
        graph.appScope.launch { runInitializers(graph.initializers) }

        val handshakeDispatcher =
            Executors
                .newThreadPerTaskExecutor(Thread.ofVirtual().name("nd-handshake").factory())
                .asCoroutineDispatcher()
        val handshakeScope = CoroutineScope(SupervisorJob() + handshakeDispatcher)
        val handshake = InstanceHandshake(dirs, SecureRandom(), buildInfo.versionName)
        val activator = WindowActivator()
        val serveJob =
            handshakeScope.launch {
                handshake.serve { request -> onHandoff(request, activator) }
            }
        if (inputs.isNotEmpty()) {
            Log.i(TAG) { "${inputs.size} first-launch input(s) queued for the window" }
        }

        // One clean-shutdown path for the window close and SIGTERM (11 Shutdown).
        val coordinator =
            ShutdownCoordinator(
                dirs = dirs,
                versionName = buildInfo.versionName,
                lock = lock,
                fileSink = fileSink,
                stopServices = {
                    runBlocking { serveJob.cancelAndJoin() } // serve's finally removes port/token files
                    handshakeScope.cancel()
                    handshakeDispatcher.close()
                    graph.appScope.cancel()
                },
                startedAtMs = sessionStartedAtMs,
            )
        Runtime.getRuntime().addShutdownHook(
            Thread(
                { coordinator.shutdown(REASON_SIGNAL) },
                SHUTDOWN_THREAD,
            ),
        )

        // Step 6: the window on the AWT event thread. `application` returns when the window
        // closes — the M0b close rule: nothing is busy, so a close request quits — and the
        // clean shutdown runs before main returns.
        application {
            NeutrodyneWindow(
                installers = graph.entryInstallers,
                menuActions = DesktopMenuActions(),
                activator = activator,
                background = background,
                onQuitRequest = ::exitApplication,
            )
        }
        coordinator.shutdown(REASON_WINDOW_CLOSED)
        return EXIT_OK
    }

    /** macOS-only AWT properties, all of which must be set before AWT initialises. */
    private fun installMacosAwtProperties(buildInfo: BuildInfo) {
        if (buildInfo.desktop?.os != DesktopOs.MACOS) return
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", ShellDialogs.APP_TITLE)
        System.setProperty("apple.awt.enableTemplateImages", "true")
    }

    /**
     * The owner's hand-off application (11 Hand-off row): the inputs go to `DesktopOpenHandler`
     * and `IntentRouter` with their milestone — M0b records them in the log; `activate` shows
     * the window (de-iconified, `toFront()`).
     */
    private fun onHandoff(
        request: HandoffRequest,
        activator: WindowActivator,
    ) {
        Log.i(TAG) { "hand-off: ${request.args.size} input(s) from ${request.cwd}, queued for the window" }
        if (request.activate) activator.bringToFront()
    }

    /** 11 Crash files table: an unclean previous session with an `hs_err` file is a JVM crash. */
    private fun reviewPreviousSession(
        dirs: AppDirs,
        crashReporter: DesktopCrashReporter,
    ) {
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
                stream.maxByOrNull { file ->
                    runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
                }
            }
        } catch (_: IOException) {
            null
        }

    /** 11 Shell failure modes: name the directory and the error; nothing is written elsewhere. */
    private fun dataDirFailure(
        dir: Path,
        e: IOException,
    ): Int {
        ShellDialogs.showError("${ShellDialogs.DATA_DIR_FAILURE_PREFIX} $dir: ${e.message}")
        return EXIT_DATA_DIR_FAILURE
    }
}
