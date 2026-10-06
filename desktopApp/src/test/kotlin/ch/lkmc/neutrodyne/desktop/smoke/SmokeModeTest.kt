// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * [SmokeMode]'s skeleton (11 Smoke mode): one `SMOKE {json}` line with the versions, the runtime
 * facts, the step timings and the pending steps, and exit code 0 — through the testable entry
 * point, against the real graph under a temporary `AppDirs`.
 */
class SmokeModeTest {
    @Test
    fun `prints one SMOKE json line and exits 0`() {
        val lines = mutableListOf<String>()
        val exitCode = SmokeMode(output = lines::add).run()

        assertThat(exitCode).isEqualTo(0)
        assertThat(lines).hasSize(1)

        val json = Json.parseToJsonElement(lines.single().removePrefix("SMOKE ")).jsonObject
        assertThat(json["versionName"]!!.jsonPrimitive.content).isEqualTo(BuildInfoLoader.load().versionName)
        assertThat(json["versionCode"]!!.jsonPrimitive.content).isEqualTo("${BuildInfoLoader.load().versionCode}")
        assertThat(json["installKind"]!!.jsonPrimitive.content).isEqualTo("dev")
        assertThat(json["javaVendor"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(json["javaVendorVersion"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(json["javaRuntimeVersion"]!!.jsonPrimitive.content).isNotEmpty()

        val steps = json["steps"]!!.jsonObject.keys
        assertThat(steps).containsAtLeast("appDirs", "buildInfo", "graph", "initializers")

        val pending = json["pending"]!!.jsonPrimitive.content
        assertThat(pending).contains("window")
        assertThat(pending).contains("engine")
        assertThat(json.keys).doesNotContain("failed")
    }

    @Test
    fun `isEnabled matches only the exact value true`() {
        assertThat(SmokeMode.isEnabled("true")).isTrue()
        assertThat(SmokeMode.isEnabled("TRUE")).isFalse()
        assertThat(SmokeMode.isEnabled("false")).isFalse()
        assertThat(SmokeMode.isEnabled(null)).isFalse()
    }
}
