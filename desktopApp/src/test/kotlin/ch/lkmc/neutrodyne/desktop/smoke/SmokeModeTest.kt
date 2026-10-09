// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * [SmokeMode]'s skeleton (11 Smoke mode): one `SMOKE {json}` line with the versions, the
 * runtime facts, the step timings — including the window step's `firstFrameMs` — and the
 * pending steps, and exit code 0 — through the testable entry point, against the real graph
 * under a temporary `AppDirs` (the window step is faked; a real window needs a display, which
 * only CI's `desktop-smoke` job has).
 */
class SmokeModeTest {
    @Test
    fun `prints one SMOKE json line and exits 0`() {
        val lines = mutableListOf<String>()
        val exitCode =
            SmokeMode(
                output = lines::add,
                windowStep = { FIRST_FRAME_MS },
            ).run()

        assertThat(exitCode).isEqualTo(0)
        assertThat(lines).hasSize(1)

        val json = Json.parseToJsonElement(lines.single().removePrefix("SMOKE ")).jsonObject
        assertThat(json["versionName"]!!.jsonPrimitive.content).isEqualTo(BuildInfoLoader.load().versionName)
        assertThat(json["versionCode"]!!.jsonPrimitive.content).isEqualTo("${BuildInfoLoader.load().versionCode}")
        assertThat(json["installKind"]!!.jsonPrimitive.content)
            .isEqualTo(
                BuildInfoLoader
                    .load()
                    .desktop!!
                    .installKind.wire,
            )
        assertThat(json["javaVendor"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(json["javaVendorVersion"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(json["javaRuntimeVersion"]!!.jsonPrimitive.content).isNotEmpty()

        val steps = json["steps"]!!.jsonObject
        assertThat(steps.keys).containsAtLeast("appDirs", "buildInfo", "graph", "initializers", "window")
        assertThat(steps["firstFrameMs"]!!.jsonPrimitive.content).isEqualTo("$FIRST_FRAME_MS")
        assertThat(json["window"]!!.jsonPrimitive.content).isEqualTo(SmokeMode.WINDOW_OPENED)

        val pending = json["pending"]!!.jsonPrimitive.content
        assertThat(pending).contains("engine")
        assertThat(pending).doesNotContain("window")
        assertThat(json.keys).doesNotContain("failed")
    }

    @Test
    fun `a headless window step says so in the json`() {
        val lines = mutableListOf<String>()
        val exitCode =
            SmokeMode(
                output = lines::add,
                windowStep = { null },
            ).run()

        assertThat(exitCode).isEqualTo(0)

        val json = Json.parseToJsonElement(lines.single().removePrefix("SMOKE ")).jsonObject
        assertThat(json["window"]!!.jsonPrimitive.content).isEqualTo(SmokeMode.WINDOW_SKIPPED_HEADLESS)
        assertThat(json["steps"]!!.jsonObject.keys).doesNotContain(SmokeMode.FIRST_FRAME_STEP)
        assertThat(json.keys).doesNotContain("failed")
    }

    /**
     * A window step that throws — headed, but no frame (a close before the first frame throws in
     * [SmokeMode]'s production branch; a real window needs a display, which only CI's
     * `desktop-smoke` job has) — must report the failure: exit 1 with `failed`, never a silent
     * exit and never `skipped-headless`.
     */
    @Test
    fun `a failed window step names the failure and exits 1`() {
        val lines = mutableListOf<String>()
        val exitCode =
            SmokeMode(
                output = lines::add,
                windowStep = { error("window closed before its first frame") },
            ).run()

        assertThat(exitCode).isEqualTo(1)
        val json = Json.parseToJsonElement(lines.single().removePrefix("SMOKE ")).jsonObject
        assertThat(json["failed"]!!.jsonPrimitive.content).contains("window")
        assertThat(json["steps"]!!.jsonObject.keys).contains("window")
        assertThat(json["steps"]!!.jsonObject.keys).doesNotContain(SmokeMode.FIRST_FRAME_STEP)
        assertThat(json.keys).doesNotContain("window")
    }

    @Test
    fun `isEnabled matches only the exact value true`() {
        assertThat(SmokeMode.isEnabled("true")).isTrue()
        assertThat(SmokeMode.isEnabled("TRUE")).isFalse()
        assertThat(SmokeMode.isEnabled("false")).isFalse()
        assertThat(SmokeMode.isEnabled(null)).isFalse()
    }

    private companion object {
        const val FIRST_FRAME_MS = 42L
    }
}
