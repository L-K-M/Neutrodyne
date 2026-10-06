// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.appDirsUnder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Smoke mode (11 Smoke mode): `-Dneutrodyne.smoke=true` is the only test entry point in a
 * packaged image. It never touches the user's directories, the network or an OS registration: a
 * fresh temporary `AppDirs` root, the graph, the initializers, one `SMOKE {json}` line, exit 0 —
 * or exit 1 naming the failed step; a watchdog exits 1 after 60 s.
 *
 * M0b skeleton: the database open, the window and the five destinations through `AppNavigator`,
 * FFmpeg, `ndmedia`, the engine child, the D-Bus step, the AOT-cache flag and the RSS line need
 * later milestones — the JSON lists them as pending (11 Delivery by milestone).
 */
internal class SmokeMode(
    private val output: (String) -> Unit = ::println,
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
            step("initializers") { runBlocking { runInitializers(graph.initializers) } }

            output("SMOKE ${smokeJson(buildInfo, timings, failure = null)}")
            cleanUp(tempRoot)
            EXIT_OK
        } catch (t: Throwable) {
            Log.e(TAG, t) { "smoke run failed" }
            val buildInfo = runCatching { BuildInfoLoader.load() }.getOrNull()
            output("SMOKE ${smokeJson(buildInfo, timings, failure = t.message ?: t.javaClass.simpleName)}")
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

        /** 11's only test entry point switch. */
        const val SYSTEM_PROPERTY = "neutrodyne.smoke"

        /** Steps 11 defines that this milestone cannot run yet (11 Smoke mode; PB24's window). */
        internal val PENDING_STEPS =
            listOf(
                "database",
                "window",
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

        /** The one-line report (11 step 8), in the test's reach for shape checks. */
        internal fun smokeJson(
            buildInfo: BuildInfo?,
            timings: Map<String, Long>,
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
                    put("pending", JsonPrimitive(PENDING_STEPS.joinToString(",")))
                    if (failure != null) put("failed", JsonPrimitive(failure))
                }
            return JsonObject(json).toString()
        }
    }
}
