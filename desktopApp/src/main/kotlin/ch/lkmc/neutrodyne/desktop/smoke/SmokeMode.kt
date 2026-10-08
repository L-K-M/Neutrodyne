// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.window_title
import ch.lkmc.neutrodyne.desktop.shell.appDirsUnder
import ch.lkmc.neutrodyne.desktop.window.DesktopMenuActions
import ch.lkmc.neutrodyne.desktop.window.NeutrodyneWindowContent
import ch.lkmc.neutrodyne.desktop.window.WindowIcons
import ch.lkmc.neutrodyne.desktop.window.rememberNeutrodyneWindowState
import ch.lkmc.neutrodyne.desktop.window.toGateState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Smoke mode (11 Smoke mode): `-Dneutrodyne.smoke=true` is the only test entry point in a
 * packaged image. It never touches the user's directories, the network or an OS registration: a
 * fresh temporary `AppDirs` root, the graph, the initializers, the window and its first frame,
 * one `SMOKE {json}` line, exit 0 — or exit 1 naming the failed step; a watchdog exits 1 after
 * 60 s. Under `java.awt.headless=true` the window step is skipped and the JSON says so.
 *
 * Still pending from 11's step list (the milestone rows): the five destinations through
 * `AppNavigator`, FFmpeg, `ndmedia`, the engine child, the D-Bus step, the AOT-cache flag and
 * the RSS line — the JSON lists them as pending.
 */
internal class SmokeMode(
    private val output: (String) -> Unit = ::println,
    /** Shows the real window, returns the first-frame delay in ms (null = skipped headless). */
    private val windowStep: (DesktopAppGraph) -> Long? = Companion::openWindowAndAwaitFirstFrame,
) {
    private val timings = LinkedHashMap<String, Long>()

    fun run(): Int {
        armWatchdog()
        var root: Path? = null

        return try {
            val tempRoot = Files.createTempDirectory("neutrodyne-smoke")
            root = tempRoot
            val dirs = step("appDirs") { appDirsUnder(tempRoot).also { it.ensureCreated() } }
            val buildInfo = step("buildInfo") { BuildInfoLoader.load() }
            val graph =
                step("graph") {
                    val crashReporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer())
                    createDesktopGraph(dirs, buildInfo, crashReporter)
                }
            // The database open gets its own step ahead of the bands, so the timing is real;
            // band 100's `DatabaseOpenInitializer` then returns at once (11 Smoke mode, M1a).
            step("database") { runBlocking { graph.databaseOpener.awaitOpen() } }
            step("initializers") { runBlocking { runInitializers(graph.initializers) } }

            // 11 step 2's window half: show it, wait for the first frame, close it again.
            val firstFrameMs = step("window") { windowStep(graph) }
            val windowOpened = firstFrameMs != null
            if (windowOpened) timings[FIRST_FRAME_STEP] = firstFrameMs

            output("SMOKE ${smokeJson(buildInfo, timings, windowOpened, failure = null)}")
            cleanUp(tempRoot)
            EXIT_OK
        } catch (t: Throwable) {
            Log.e(TAG, t) { "smoke run failed" }
            val buildInfo = runCatching { BuildInfoLoader.load() }.getOrNull()
            output(
                "SMOKE ${smokeJson(
                    buildInfo,
                    timings,
                    windowOpened = null,
                    failure =
                        t.message ?: t.javaClass.simpleName,
                )}",
            )
            root?.let(::cleanUp)
            EXIT_FAILED_STEP
        }
    }

    /** A daemon thread that ends a hanging run (11: the watchdog exits 1 after 60 s). */
    private fun armWatchdog() {
        val watchdog =
            Thread({
                sleepQuietly(WATCHDOG_TIMEOUT)
                System.err.println("smoke run exceeded $WATCHDOG_TIMEOUT; exiting")
                Runtime.getRuntime().halt(EXIT_FAILED_STEP)
            }, THREAD_NAME)
        watchdog.isDaemon = true
        watchdog.start()
    }

    /** Runs [block], recording its wall time under [name] — also when it fails. */
    private fun <T> step(
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

    private fun cleanUp(root: Path) {
        runCatching {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun sleepQuietly(duration: Duration) {
        try {
            Thread.sleep(duration.inWholeMilliseconds)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "Smoke"
        private const val THREAD_NAME = "smoke-watchdog"
        private const val EXIT_OK = 0
        private const val EXIT_FAILED_STEP = 1
        private val WATCHDOG_TIMEOUT = 60.seconds

        /** [openWindowAndAwaitFirstFrame]'s "no frame yet" marker. */
        private const val FRAME_NOT_RENDERED = -1L

        /** 11's only test entry point switch. */
        const val SYSTEM_PROPERTY = "neutrodyne.smoke"

        /** The first-frame figure's step name (11 step 8). */
        internal const val FIRST_FRAME_STEP = "firstFrameMs"

        /** The window outcome's JSON field values. */
        internal const val WINDOW_OPENED = "opened"
        internal const val WINDOW_SKIPPED_HEADLESS = "skipped-headless"

        /** Steps 11 defines that this milestone cannot run yet (11 Smoke mode). */
        internal val PENDING_STEPS =
            listOf(
                "destinations",
                "ffmpeg",
                "ndmedia",
                "engine",
                "dbus",
                "aotCache",
                "rss",
            )

        /** `-Dneutrodyne.smoke` is read exactly like this (11 Start-up sequence step 1). */
        fun isEnabled(property: String?): Boolean = property == "true"

        /**
         * Opens the real window and returns the ms from the window step's start to the first
         * rendered frame (11 step 8's first-frame figure), then closes it. A headless AWT skips
         * the window and reports `null` — CI's `desktop-smoke` job runs the same code under
         * Xvfb, where the real branch executes.
         */
        private fun openWindowAndAwaitFirstFrame(graph: DesktopAppGraph): Long? {
            if (GraphicsEnvironment.isHeadless()) {
                Log.i(TAG) { "java.awt.headless: skipping the window step" }
                return null
            }

            val firstFrameMs = AtomicLong(FRAME_NOT_RENDERED)
            application {
                val menuActions = remember { DesktopMenuActions() }
                Window(
                    onCloseRequest = { exitApplication() },
                    state = rememberNeutrodyneWindowState(),
                    title = stringResource(Res.string.window_title),
                    icon = remember { WindowIcons.windowIconPainter() },
                ) {
                    NeutrodyneWindowContent(
                        installers = graph.entryInstallers,
                        menuActions = menuActions,
                        startup =
                            graph.databaseOpener.openState.value
                                .toGateState(),
                        onRetryStartup = {
                            graph.appScope.launch { suspendRunCatching { graph.databaseOpener.awaitOpen() } }
                        },
                        dataDir = graph.dirs.data,
                    )

                    val openedAt = remember { TimeSource.Monotonic.markNow() }
                    LaunchedEffect(Unit) {
                        withFrameNanos { } // returns after the first frame was presented
                        firstFrameMs.set(openedAt.elapsedNow().inWholeMilliseconds)
                        exitApplication()
                    }
                }
            }
            return firstFrameMs.get().takeIf { it != FRAME_NOT_RENDERED }
        }

        /**
         * The one-line report (11 step 8), in the test's reach for shape checks. [windowOpened]
         * is null when the run failed before the window step; otherwise the `window` field says
         * whether it opened or was skipped headless.
         */
        internal fun smokeJson(
            buildInfo: BuildInfo?,
            timings: Map<String, Long>,
            windowOpened: Boolean?,
            failure: String?,
        ): String {
            val desktop = buildInfo?.desktop
            val json =
                buildMap {
                    put("versionName", JsonPrimitive(buildInfo?.versionName ?: "unknown"))
                    put("versionCode", JsonPrimitive(buildInfo?.versionCode))
                    put("installKind", JsonPrimitive(desktop?.installKind?.wire ?: "unknown"))
                    put("javaVendor", JsonPrimitive(System.getProperty("java.vendor")))
                    put("javaVendorVersion", JsonPrimitive(System.getProperty("java.vendor.version")))
                    put("javaRuntimeVersion", JsonPrimitive(System.getProperty("java.runtime.version")))
                    put("steps", JsonObject(timings.mapValues { JsonPrimitive(it.value) }))
                    if (windowOpened != null) {
                        put(
                            "window",
                            JsonPrimitive(if (windowOpened) WINDOW_OPENED else WINDOW_SKIPPED_HEADLESS),
                        )
                    }
                    put("pending", JsonPrimitive(PENDING_STEPS.joinToString(",")))
                    if (failure != null) put("failed", JsonPrimitive(failure))
                }
            return JsonObject(json).toString()
        }
    }
}
