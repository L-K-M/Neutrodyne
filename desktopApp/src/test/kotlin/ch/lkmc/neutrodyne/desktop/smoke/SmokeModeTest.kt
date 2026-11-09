// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    fun `stops graph jobs and watchdog before removing smoke files`() {
        lateinit var startedGraph: ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
        val exit =
            SmokeMode(
                output = {},
                windowStep = { graph ->
                    startedGraph = graph
                    FIRST_FRAME_MS
                },
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

    /**
     * MD3 AC2's report shape (11 Smoke mode step 6): a run the replay property armed carries
     * the proof's evidence object in `engine` and drops `engine` from `pending`. The proof
     * itself is the `engineStep` seam here — the packaged run's real one is
     * `PackagedEngineProof`, which needs the image's own resources.
     */
    @Test
    fun `an armed engine run drops engine from pending and reports its evidence`() =
        withEngineReplay {
            val evidence = buildJsonObject { put("resolve", "ok") }
            val lines = mutableListOf<String>()
            val exit =
                SmokeMode(
                    output = lines::add,
                    windowStep = { FIRST_FRAME_MS },
                    engineStep = { _, _ -> evidence },
                ).run()

            assertThat(exit).isEqualTo(0)
            val json = Json.parseToJsonElement(lines.single().removePrefix("SMOKE ")).jsonObject
            assertThat(json["engine"]).isEqualTo(evidence)
            assertThat(json["pending"]!!.jsonPrimitive.content).doesNotContain("engine")
            assertThat(json["steps"]!!.jsonObject.keys).contains("engine")
            assertThat(json.keys).doesNotContain("failed")
        }

    /**
     * The armed run's honest failure (MD3 AC2): the replay property reaches the engine step,
     * and a step that cannot prove — here the real `runEngineProof` against a test JVM with
     * no packaged engine resources — must fail the run with `failed` and keep `engine`
     * pending. A plain unarmed success (exit 0, engine pending) is the wrong answer: the
     * pre-proof contract ignored the property and reported exactly that.
     */
    @Test
    fun `an armed engine run cannot report a plain pending success`() =
        withEngineReplay {
            val lines = mutableListOf<String>()
            val exit =
                SmokeMode(
                    output = lines::add,
                    windowStep = { FIRST_FRAME_MS },
                ).run()

            assertThat(exit).isEqualTo(1)
            val json = Json.parseToJsonElement(lines.single().removePrefix("SMOKE ")).jsonObject
            assertThat(json.keys).contains("failed")
            assertThat(json.keys).doesNotContain("engine")
            assertThat(json["pending"]!!.jsonPrimitive.content).contains("engine")
        }

    /**
     * A proof that runs and throws — missing resources, a dev interpreter, a failed resolve —
     * is a failed step like any other: exit 1, `failed` names it, `engine` stays pending.
     */
    @Test
    fun `a thrown engine proof is a failed step`() =
        withEngineReplay {
            val lines = mutableListOf<String>()
            val exit =
                SmokeMode(
                    output = lines::add,
                    windowStep = { FIRST_FRAME_MS },
                    engineStep = { _, _ -> error("packaged engine resources are missing") },
                ).run()

            assertThat(exit).isEqualTo(1)
            val json = Json.parseToJsonElement(lines.single().removePrefix("SMOKE ")).jsonObject
            assertThat(json["failed"]!!.jsonPrimitive.content).contains("packaged engine resources")
            assertThat(json["steps"]!!.jsonObject.keys).contains("engine")
            assertThat(json["pending"]!!.jsonPrimitive.content).contains("engine")
        }

    /** The arming property's gate: only `neutrodyne.smoke=true` plus a nonblank dir arms it. */
    @Test
    fun `engineReplayDir arms only inside a smoke run`() {
        assertThat(SmokeMode.engineReplayDir("true", "/recorded/resolve"))
            .isEqualTo(java.io.File("/recorded/resolve"))
        assertThat(SmokeMode.engineReplayDir("false", "/recorded/resolve")).isNull()
        assertThat(SmokeMode.engineReplayDir(null, "/recorded/resolve")).isNull()
        assertThat(SmokeMode.engineReplayDir("true", null)).isNull()
        assertThat(SmokeMode.engineReplayDir("true", "")).isNull()
        assertThat(SmokeMode.engineReplayDir("true", "   ")).isNull()
    }

    private fun <T> withEngineReplay(block: () -> T): T {
        val smokeBefore = System.getProperty(SmokeMode.SYSTEM_PROPERTY)
        val replayBefore = System.getProperty(SmokeMode.ENGINE_REPLAY_PROPERTY)
        System.setProperty(SmokeMode.SYSTEM_PROPERTY, "true")
        System.setProperty(SmokeMode.ENGINE_REPLAY_PROPERTY, "/recorded/resolve")
        return try {
            block()
        } finally {
            if (smokeBefore ==
                null
            ) {
                System.clearProperty(SmokeMode.SYSTEM_PROPERTY)
            } else {
                System.setProperty(SmokeMode.SYSTEM_PROPERTY, smokeBefore)
            }
            if (replayBefore ==
                null
            ) {
                System.clearProperty(SmokeMode.ENGINE_REPLAY_PROPERTY)
            } else {
                System.setProperty(SmokeMode.ENGINE_REPLAY_PROPERTY, replayBefore)
            }
        }
    }

    @Test
    fun `isEnabled matches only the exact value true`() {
        assertThat(SmokeMode.isEnabled("true")).isTrue()
        assertThat(SmokeMode.isEnabled("TRUE")).isFalse()
        assertThat(SmokeMode.isEnabled("false")).isFalse()
        assertThat(SmokeMode.isEnabled(null)).isFalse()
    }

    /**
     * The state machine behind the `aotCache` field. The real JDK 25 behavior that makes
     * these honest is pinned by the build-logic `DesktopAotTrainingRealTest`: an auto launch
     * falls back silently, so an existing archive is only `unverified`; a strict launch
     * (`-XX:AOTMode=on`) fails before main, so reaching the report under it is proof.
     */
    @Test
    fun `aotCacheState reports unverified on auto and in-use only under strict mode`() {
        assertThat(SmokeMode.aotCacheStateOf(emptyList()) { true })
            .isEqualTo(SmokeMode.AOT_CACHE_NOT_CONFIGURED)
        assertThat(SmokeMode.aotCacheStateOf(listOf("-XX:AOTCache=/x")) { false })
            .isEqualTo(SmokeMode.AOT_CACHE_MISSING)
        assertThat(SmokeMode.aotCacheStateOf(listOf("-XX:AOTCache=/x")) { true })
            .isEqualTo(SmokeMode.AOT_CACHE_UNVERIFIED)
        assertThat(
            SmokeMode.aotCacheStateOf(listOf("-XX:AOTCache=/x", "-XX:AOTMode=on")) { true },
        ).isEqualTo(SmokeMode.AOT_CACHE_IN_USE)
        // A strict launch with a missing archive never reaches this report — the JVM
        // fails before main — but the state function still reports the missing file.
        assertThat(
            SmokeMode.aotCacheStateOf(listOf("-XX:AOTCache=/x", "-XX:AOTMode=on")) { false },
        ).isEqualTo(SmokeMode.AOT_CACHE_MISSING)
    }

    private companion object {
        const val FIRST_FRAME_MS = 42L
    }
}
