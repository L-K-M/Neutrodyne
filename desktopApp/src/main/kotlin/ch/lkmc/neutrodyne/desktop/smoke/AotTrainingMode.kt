// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import androidx.compose.runtime.withFrameNanos
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
import ch.lkmc.neutrodyne.desktop.playback.DesktopEnginePaths
import ch.lkmc.neutrodyne.desktop.window.DesktopMenuActions
import ch.lkmc.neutrodyne.playback.native.exerciseNullBackend
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The AOT training run (PO-42, 11 AOT cache): `trainAotCache` launches the packaged app with
 * `-Dcompose.aot.training-run=true` so the JVM's assembly-phase archive
 * (`-XX:AOTCacheOutput=$APPDIR/neutrodyne.aot`) records the classes a real start loads. The
 * journey mirrors the first launch: the shared prologue, the window and the five destinations
 * through `AppNavigator`, then one ndmedia "null"-backend PCM write. Like smoke mode it never
 * touches the user's directories or an OS registration (`ShellJourney` skips the host's
 * registration initializers): a fresh temporary `AppDirs` root, one `AOT-TRAINING {json}`
 * line, exit 0 — or exit 1 naming the failed step. A headless window skip or a missing
 * ndmedia is a failed training, never a completed window/native proof: an archive trained
 * without the windowed journey must not be packaged.
 */
internal class AotTrainingMode(
    private val output: (String) -> Unit = ::println,
    /** Opens the window and walks the destinations; faked by the test. */
    private val windowJourney: (DesktopAppGraph) -> Long? = Companion::windowJourney,
    /** The ndmedia "null"-backend PCM step; faked by the test. */
    private val pcmStep: () -> String = Companion::playNullBackend,
) {
    fun run(): Int {
        val journey =
            try {
                ShellJourney(AOT_TEMP_PREFIX)
            } catch (t: Throwable) {
                output("AOT-TRAINING ${trainingJson(emptyMap(), null, null, t.report())}")
                return EXIT_FAILED_STEP
            }
        var windowOpened: Boolean? = null
        var backend: String? = null
        return try {
            journey.runPrologue()
            val firstFrameMs = journey.step("window") { windowJourney(journey.graph!!) }
            windowOpened = firstFrameMs != null
            if (firstFrameMs != null) journey.timings[FIRST_FRAME_STEP] = firstFrameMs
            backend = journey.step("nullBackend") { pcmStep() }

            journey.cleanUp()
            val failure =
                when {
                    windowOpened != true -> {
                        "window $WINDOW_SKIPPED_HEADLESS: the AOT training run needs a display"
                    }

                    backend != NULL_BACKEND_PLAYED -> {
                        "null backend: $backend"
                    }

                    else -> {
                        null
                    }
                }
            output("AOT-TRAINING ${trainingJson(journey.timings, windowOpened, backend, failure)}")
            if (failure == null) EXIT_OK else EXIT_FAILED_STEP
        } catch (t: Throwable) {
            output("AOT-TRAINING ${trainingJson(journey.timings, windowOpened, backend, t.report())}")
            runCatching { journey.cleanUp() }
                .onFailure { Log.e(TAG, it) { "aot training shutdown failed" } }
            EXIT_FAILED_STEP
        } finally {
            journey.disarmWatchdog()
        }
    }

    private fun Throwable.report(): String = message ?: javaClass.simpleName

    companion object {
        private const val TAG = "AotTraining"
        private const val AOT_TEMP_PREFIX = "neutrodyne-aot-train"
        private const val EXIT_OK = 0
        private const val EXIT_FAILED_STEP = 1

        /** `-Dcompose.aot.training-run` is read exactly like this (PO-42). */
        const val SYSTEM_PROPERTY = "compose.aot.training-run"

        /** The first-frame figure's step name (11 step 8). */
        internal const val FIRST_FRAME_STEP = "firstFrameMs"

        /** The report's outcome values. */
        internal const val WINDOW_OPENED = "opened"
        internal const val WINDOW_SKIPPED_HEADLESS = "skipped-headless"
        internal const val NULL_BACKEND_PLAYED = "played"
        internal const val NULL_BACKEND_SKIPPED = "skipped-no-ndmedia"

        /** The five destinations the journey walks (11 AOT cache's training journey). */
        internal val JOURNEY_TABS = listOf(FeedsKey, LibraryKey, UpNextKey, DownloadsKey, DiscoverKey)

        private const val ATTACH_TIMEOUT_S = 10L
        private const val ATTACH_POLL_MS = 50L

        fun isEnabled(property: String?): Boolean = property == "true"

        /**
         * Opens the real window, waits for the first frame, then selects each of [JOURNEY_TABS]
         * through the shell's `AppNavigator` bridge — awaiting one frame per tab so the
         * destination's composition actually loads its classes. Headless (xvfb-less runners)
         * the window step is skipped and [run] fails the training; the PCM step still records
         * the native path.
         */
        private fun windowJourney(graph: DesktopAppGraph): Long? =
            SmokeMode.openWindow(graph) { menuActions ->
                for (tab in JOURNEY_TABS) {
                    selectTabWhenAttached(menuActions, tab)
                    // One frame for the navigation dispatch, one for the destination's first
                    // composition — the classes the training run exists to record.
                    withFrameNanos { }
                    withFrameNanos { }
                }
            }

        /** Polls until the root has composed and attached its navigator, then selects [tab]. */
        private suspend fun selectTabWhenAttached(
            menuActions: DesktopMenuActions,
            tab: TopLevelKey,
        ) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ATTACH_TIMEOUT_S)
            while (!menuActions.selectTab(tab)) {
                check(System.nanoTime() < deadline) { "navigator never attached for $tab" }
                delay(ATTACH_POLL_MS)
            }
        }

        /**
         * A short PCM burst through ndmedia's "null" backend: the FFM bindings, the
         * device-open path and the ring write all record into the archive without touching
         * real audio hardware (the FFM part lives in `:playback:native`'s
         * [exerciseNullBackend] — the banned-API rule confines it there). An image without
         * the library (a dev training run) is named explicitly rather than silently
         * green — [run] fails it.
         */
        private fun playNullBackend(): String {
            val directory = DesktopEnginePaths.current().ndmedia
            if (!Files.isDirectory(directory)) return NULL_BACKEND_SKIPPED
            exerciseNullBackend(directory)
            return NULL_BACKEND_PLAYED
        }

        internal fun trainingJson(
            timings: Map<String, Long>,
            windowOpened: Boolean?,
            nullBackend: String?,
            failure: String?,
        ): String =
            JsonObject(
                buildMap {
                    put("steps", JsonObject(timings.mapValues { JsonPrimitive(it.value) }))
                    if (windowOpened != null) {
                        put(
                            "window",
                            JsonPrimitive(if (windowOpened) WINDOW_OPENED else WINDOW_SKIPPED_HEADLESS),
                        )
                    }
                    if (nullBackend != null) put("nullBackend", JsonPrimitive(nullBackend))
                    if (failure != null) put("failed", JsonPrimitive(failure))
                },
            ).toString()
    }
}
