// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.window_title
import ch.lkmc.neutrodyne.desktop.window.DesktopMenuActions
import ch.lkmc.neutrodyne.desktop.window.NeutrodyneWindowContent
import ch.lkmc.neutrodyne.desktop.window.WindowIcons
import ch.lkmc.neutrodyne.desktop.window.rememberNeutrodyneWindowState
import ch.lkmc.neutrodyne.desktop.window.toGateState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource

/**
 * Smoke mode (11 Smoke mode): `-Dneutrodyne.smoke=true` is the only test entry point in a
 * packaged image. It never touches the user's directories, the network or an OS registration: a
 * fresh temporary `AppDirs` root, the graph, the initializers except the host's OS
 * registrations (`ShellJourney` skips those), the window and its first frame,
 * one `SMOKE {json}` line, exit 0 — or exit 1 naming the failed step; a watchdog exits 1 after
 * 60 s. Under `java.awt.headless=true` the window step is skipped and the JSON says so.
 *
 * Still pending from 11's step list (the milestone rows): the five destinations through
 * `AppNavigator`, FFmpeg, `ndmedia`, the engine child, the D-Bus step and the RSS line —
 * the JSON lists them as pending.
 */
internal class SmokeMode(
    private val output: (String) -> Unit = ::println,
    /** Shows the real window, returns the first-frame delay in ms (null = skipped headless). */
    private val windowStep: (DesktopAppGraph) -> Long? = Companion::openWindowAndAwaitFirstFrame,
) {
    fun run(): Int {
        val journey =
            try {
                ShellJourney(SMOKE_TEMP_PREFIX)
            } catch (t: Throwable) {
                Log.e(TAG, t) { "smoke run failed" }
                output(
                    "SMOKE ${smokeJson(
                        null,
                        emptyMap(),
                        windowOpened = null,
                        failure = t.message ?: t.javaClass.simpleName,
                    )}",
                )
                return EXIT_FAILED_STEP
            }
        var buildInfo: BuildInfo? = null
        return try {
            buildInfo = journey.runPrologue()

            // 11 step 2's window half: show it, wait for the first frame, close it again.
            val firstFrameMs = journey.step("window") { windowStep(journey.graph!!) }
            val windowOpened = firstFrameMs != null
            if (windowOpened) journey.timings[FIRST_FRAME_STEP] = firstFrameMs

            journey.cleanUp()
            output("SMOKE ${smokeJson(buildInfo, journey.timings, windowOpened, failure = null)}")
            EXIT_OK
        } catch (t: Throwable) {
            Log.e(TAG, t) { "smoke run failed" }
            val info = buildInfo ?: runCatching { BuildInfoLoader.load() }.getOrNull()
            output(
                "SMOKE ${smokeJson(
                    info,
                    journey.timings,
                    windowOpened = null,
                    failure =
                        t.message ?: t.javaClass.simpleName,
                )}",
            )
            runCatching { journey.cleanUp() }
                .onFailure { Log.e(TAG, it) { "smoke shutdown failed" } }
            EXIT_FAILED_STEP
        } finally {
            journey.disarmWatchdog()
        }
    }

    companion object {
        private const val TAG = "Smoke"
        private const val SMOKE_TEMP_PREFIX = "neutrodyne-smoke"
        private const val EXIT_OK = 0
        private const val EXIT_FAILED_STEP = 1

        /** [openWindowAndAwaitFirstFrame]'s "no frame yet" marker. */
        private const val FRAME_NOT_RENDERED = -1L

        /** 11's only test entry point switch. */
        const val SYSTEM_PROPERTY = "neutrodyne.smoke"

        /** The first-frame figure's step name (11 step 8). */
        internal const val FIRST_FRAME_STEP = "firstFrameMs"

        /** The window outcome's JSON field values. */
        internal const val WINDOW_OPENED = "opened"
        internal const val WINDOW_SKIPPED_HEADLESS = "skipped-headless"

        /** The AOT cache's option prefix and the strict mode that proves its use. */
        internal const val AOT_CACHE_OPTION_PREFIX = "-XX:AOTCache="
        internal const val AOT_MODE_STRICT_OPTION = "-XX:AOTMode=on"

        /** The `aotCache` JSON field's values. */
        internal const val AOT_CACHE_IN_USE = "in-use"
        internal const val AOT_CACHE_MISSING = "missing"
        internal const val AOT_CACHE_NOT_CONFIGURED = "not-configured"
        internal const val AOT_CACHE_UNVERIFIED = "unverified"

        /** Steps 11 defines that this milestone cannot run yet (11 Smoke mode). */
        internal val PENDING_STEPS =
            listOf(
                "destinations",
                "ffmpeg",
                "ndmedia",
                "engine",
                "dbus",
                "rss",
            )

        /** `-Dneutrodyne.smoke` is read exactly like this (11 Start-up sequence step 1). */
        fun isEnabled(property: String?): Boolean = property == "true"

        /** The smoke journey: open the window, take the first-frame figure, close it again. */
        private fun openWindowAndAwaitFirstFrame(graph: DesktopAppGraph): Long? = openWindow(graph) { }

        /**
         * Opens the real window, waits for the first frame, runs [journey] inside the window's
         * composition — the AOT training run walks the five destinations there — then closes
         * it and returns the ms from the window step's start to that first frame (11 step 8's
         * first-frame figure). A headless AWT skips the window and returns `null` — CI's
         * `desktop-smoke` job runs the same code under Xvfb, where the real branch executes.
         *
         * `application` must return here, so `exitProcessOnExit = false`: its default
         * `exitProcess(0)` fires the moment the smoke window closes and kills the JVM before
         * [run] can print the `SMOKE` line — the failure the real-window CI run hit. A window
         * that closes without a first frame is a failed window step, not `skipped-headless`.
         */
        internal fun openWindow(
            graph: DesktopAppGraph,
            journey: suspend (DesktopMenuActions) -> Unit = {},
        ): Long? {
            if (GraphicsEnvironment.isHeadless()) {
                Log.i(TAG) { "java.awt.headless: skipping the window step" }
                return null
            }

            val firstFrameMs = AtomicLong(FRAME_NOT_RENDERED)
            var journeyFailure: Throwable? = null
            application(exitProcessOnExit = false) {
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
                        viewModelFactory = graph.metroViewModelFactory,
                        playbackController = graph.playbackController,
                        playbackState = graph.playbackStateSource,
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
                        try {
                            journey(menuActions)
                        } catch (t: Throwable) {
                            journeyFailure = t
                        }
                        exitApplication()
                    }
                }
            }
            val elapsed = firstFrameMs.get()
            if (elapsed == FRAME_NOT_RENDERED) error("window closed before its first frame")
            journeyFailure?.let { throw it }
            return elapsed
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
                    put("aotCache", JsonPrimitive(aotCacheState()))
                    put("pending", JsonPrimitive(PENDING_STEPS.joinToString(",")))
                    if (failure != null) put("failed", JsonPrimitive(failure))
                }
            return JsonObject(json).toString()
        }

        /**
         * The PO-42 AOT cache's runtime state (11 step 8's "whether the AOT cache was used"):
         * `not-configured` without a `-XX:AOTCache` flag, `missing` when the flag names a
         * file that is not there. When the file exists the state depends on the JVM's mode.
         * A strict launch (`-XX:AOTMode=on`) fails before main when the archive cannot be
         * mapped (JDK 25), so reaching this report under strict mode proves the cache was
         * used — `in-use`. An auto launch silently falls back to the non-AOT path (JDK 25),
         * so the file's existence alone proves nothing: `unverified`. The packaged acceptance
         * launches the image under strict mode; a normal product start stays on auto and is
         * reported honestly rather than claimed as proven.
         */
        internal fun aotCacheState(): String =
            aotCacheStateOf(ManagementFactory.getRuntimeMXBean().inputArguments) { path ->
                Files.isRegularFile(Path.of(path))
            }

        internal fun aotCacheStateOf(
            inputArguments: List<String>,
            exists: (String) -> Boolean,
        ): String {
            val path =
                inputArguments
                    .firstOrNull { it.startsWith(AOT_CACHE_OPTION_PREFIX) }
                    ?.substringAfter('=')
                    ?: return AOT_CACHE_NOT_CONFIGURED
            if (!exists(path)) return AOT_CACHE_MISSING
            return if (inputArguments.any { it == AOT_MODE_STRICT_OPTION }) {
                AOT_CACHE_IN_USE
            } else {
                AOT_CACHE_UNVERIFIED
            }
        }
    }
}
