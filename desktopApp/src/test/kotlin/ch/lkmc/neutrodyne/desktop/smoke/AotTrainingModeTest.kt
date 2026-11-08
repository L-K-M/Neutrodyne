// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * [AotTrainingMode]'s report contract (11 AOT cache): one `AOT-TRAINING {json}` line
 * with the step timings and the window and null-backend outcomes, exit 0 only when
 * the whole journey ran — a headless-skip or a missing ndmedia is a failed training,
 * never a completed window/native proof. The window and PCM steps are seams (a real
 * window needs a display, the real PCM step needs the ndmedia build).
 */
class AotTrainingModeTest {
    @Test
    fun `prints one AOT-TRAINING json line and exits 0`() {
        val lines = mutableListOf<String>()
        val exit =
            AotTrainingMode(
                output = lines::add,
                windowJourney = { FIRST_FRAME_MS },
                pcmStep = { AotTrainingMode.NULL_BACKEND_PLAYED },
            ).run()

        assertThat(exit).isEqualTo(0)
        assertThat(lines).hasSize(1)

        val json = Json.parseToJsonElement(lines.single().removePrefix("AOT-TRAINING ")).jsonObject
        val steps = json["steps"]!!.jsonObject
        assertThat(steps.keys)
            .containsAtLeast("appDirs", "buildInfo", "graph", "initializers", "window", "nullBackend")
        assertThat(steps[AotTrainingMode.FIRST_FRAME_STEP]!!.jsonPrimitive.content)
            .isEqualTo("$FIRST_FRAME_MS")
        assertThat(json["window"]!!.jsonPrimitive.content).isEqualTo(AotTrainingMode.WINDOW_OPENED)
        assertThat(json["nullBackend"]!!.jsonPrimitive.content)
            .isEqualTo(AotTrainingMode.NULL_BACKEND_PLAYED)
        assertThat(json.keys).doesNotContain("failed")
    }

    @Test
    fun `a skipped window fails the training instead of masquerading`() {
        val lines = mutableListOf<String>()
        val exit =
            AotTrainingMode(
                output = lines::add,
                windowJourney = { null },
                pcmStep = { AotTrainingMode.NULL_BACKEND_PLAYED },
            ).run()

        assertThat(exit).isEqualTo(1)
        val json = Json.parseToJsonElement(lines.single().removePrefix("AOT-TRAINING ")).jsonObject
        assertThat(json["window"]!!.jsonPrimitive.content)
            .isEqualTo(AotTrainingMode.WINDOW_SKIPPED_HEADLESS)
        assertThat(json["failed"]!!.jsonPrimitive.content).contains("window")
    }

    @Test
    fun `a failed window step names the failure and exits 1`() {
        val lines = mutableListOf<String>()
        val exit =
            AotTrainingMode(
                output = lines::add,
                windowJourney = { error("window closed before its first frame") },
                pcmStep = { AotTrainingMode.NULL_BACKEND_PLAYED },
            ).run()

        assertThat(exit).isEqualTo(1)
        val json = Json.parseToJsonElement(lines.single().removePrefix("AOT-TRAINING ")).jsonObject
        assertThat(json["failed"]!!.jsonPrimitive.content).contains("window")
        assertThat(json["steps"]!!.jsonObject.keys).contains("window")
    }

    @Test
    fun `a missing ndmedia fails the training`() {
        val lines = mutableListOf<String>()
        val exit =
            AotTrainingMode(
                output = lines::add,
                windowJourney = { FIRST_FRAME_MS },
                pcmStep = { AotTrainingMode.NULL_BACKEND_SKIPPED },
            ).run()

        assertThat(exit).isEqualTo(1)
        val json = Json.parseToJsonElement(lines.single().removePrefix("AOT-TRAINING ")).jsonObject
        assertThat(json["window"]!!.jsonPrimitive.content).isEqualTo(AotTrainingMode.WINDOW_OPENED)
        assertThat(json["nullBackend"]!!.jsonPrimitive.content)
            .isEqualTo(AotTrainingMode.NULL_BACKEND_SKIPPED)
        assertThat(json["failed"]!!.jsonPrimitive.content).contains("null backend")
    }

    @Test
    fun `a failed pcm step names the failure and exits 1`() {
        val lines = mutableListOf<String>()
        val exit =
            AotTrainingMode(
                output = lines::add,
                windowJourney = { FIRST_FRAME_MS },
                pcmStep = { error("nd_out_open failed with -3") },
            ).run()

        assertThat(exit).isEqualTo(1)
        val json = Json.parseToJsonElement(lines.single().removePrefix("AOT-TRAINING ")).jsonObject
        assertThat(json["failed"]!!.jsonPrimitive.content).contains("nd_out_open")
    }

    @Test
    fun `stops graph jobs and watchdog before removing training files`() {
        lateinit var startedGraph: ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
        val exit =
            AotTrainingMode(
                output = {},
                windowJourney = { graph ->
                    startedGraph = graph
                    FIRST_FRAME_MS
                },
                pcmStep = { AotTrainingMode.NULL_BACKEND_PLAYED },
            ).run()

        assertThat(exit).isEqualTo(0)
        assertThat(startedGraph.appScope.coroutineContext[kotlinx.coroutines.Job]?.isCompleted).isTrue()
        assertThat(
            java.nio.file.Files
                .exists(startedGraph.dirs.data),
        ).isFalse()
        assertThat(Thread.getAllStackTraces().keys.any { it.name == "smoke-watchdog" && it.isAlive }).isFalse()
    }

    @Test
    fun `isEnabled matches only the exact value true`() {
        assertThat(AotTrainingMode.isEnabled("true")).isTrue()
        assertThat(AotTrainingMode.isEnabled("TRUE")).isFalse()
        assertThat(AotTrainingMode.isEnabled("false")).isFalse()
        assertThat(AotTrainingMode.isEnabled(null)).isFalse()
    }

    private companion object {
        const val FIRST_FRAME_MS = 42L
    }
}
