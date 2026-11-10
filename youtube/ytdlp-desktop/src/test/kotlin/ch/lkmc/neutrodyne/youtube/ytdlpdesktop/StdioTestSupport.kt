// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EngineActive
import ch.lkmc.neutrodyne.youtube.engine.EngineCompileMarkers
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import org.junit.Assume.assumeNotNull
import java.io.File
import java.nio.file.Files

/** Monotonic real clock for child-process tests; virtual schedulers cannot pace a subprocess. */
internal object RealClock : Clock {
    override fun now(): Long = System.currentTimeMillis()

    override fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}

internal const val BUNDLED_VERSION = "2026.08.19"

/** The runner's interpreter; 11 requires one on every CI host, so absence is an assumption fail. */
internal fun runnerPython(): PythonRuntime {
    val found = PythonRuntimeSearch.onPath()
    assumeNotNull(
        found,
        "no python3/python on this runner — the stdio host tests require the build-machine interpreter (11)",
    )
    return found!!
}

internal fun engineResourcesDir(): File {
    val found = EngineResources.standard({ rootDir() })()
    assumeNotNull(
        found,
        "syncEngineResources did not run — the test task wires neutrodyne.engineResources",
    )
    return found!!
}

internal fun rootDir(): File? = System.getProperty("neutrodyne.rootDir")?.let(::File)

/** `recorded/<scenario>` from the shared engine corpus, as a real directory for `--replay`. */
internal fun recordedScenario(name: String): File {
    val dir = File(rootDir(), "youtube/engine/src/test/resources/recorded/$name")
    assumeNotNull(dir.takeIf(File::isDirectory), "missing recorded scenario $name")
    return dir
}

internal fun recordedRequest(name: String): Pair<String, String> {
    val text = recordedScenario(name).resolve("request.json").readText()
    val method = "\"method\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(text)!!.groupValues[1]
    val tail = text.substringAfter("\"payload\":")
    // The payload member is the object literal after "payload": — walk brace depth.
    val start = tail.indexOf('{')
    var depth = 0
    var end = tail.length
    for (index in start until tail.length) {
        when (tail[index]) {
            '{' -> {
                depth++
            }

            '}' -> {
                if (--depth == 0) {
                    end = index + 1
                    break
                }
            }
        }
    }
    return method to tail.substring(start, end)
}

internal fun recordedResult(name: String): String = recordedScenario(name).resolve("result.json").readText()

/**
 * One fixture per test: fresh dirs, the shared fakes, and the real spawner chain
 * ([YtxProcess] over the runner's interpreter). `debugHooks` is on so `_hang`/`_crash`/
 * `_emit_raw` fault injection reaches the child.
 */
internal class StdioFixture(
    root: File,
    val python: PythonRuntime = runnerPython(),
) {
    val dirs =
        AppDirs(
            data = root.resolve("data").toPath(),
            config = root.resolve("config").toPath(),
            cache = root.resolve("cache").toPath(),
            state = root.resolve("state").toPath(),
            logs = root.resolve("logs").toPath(),
            downloadsDefault = root.resolve("downloads").toPath(),
        ).apply { ensureCreated() }
    val resources = engineResourcesDir()
    val paths = DesktopEngineStorePaths(dirs, { resources }, { python })
    val store =
        FakeEngineStore().apply {
            preparation.value = EnginePreparation.COMPILED
            active.value = EngineActive(BUNDLED_VERSION, BUNDLED_VERSION, null, 0)
        }
    val settings = FakeEngineSettings()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = RealClock
    val ping = YtxControlRequest(YtxContext("en", "US", "neutrodyne-test", "V4"))
    val spawner =
        SpySpawner(
            YtxProcess({ python }, paths, jvmPid = ProcessHandle.current().pid(), stderrSink = {}),
        )

    fun transport(
        replay: File? = null,
        idleStopMs: Long = 500,
        startTimeoutMs: Long = 30_000,
        compileTimeoutMs: Long = 120_000,
        hangGraceMs: Long = 150,
    ) = StdioYtxTransport(
        spawner,
        store,
        settings,
        arming = replay?.let { dir -> StdioReplayArming { dir.canonicalPath.toPath() } },
        debugHooks = true,
        clock = clock,
        scope = scope,
        ping = ping,
        idleStopMs = idleStopMs,
        startTimeoutMs = startTimeoutMs,
        compileTimeoutMs = compileTimeoutMs,
        hangGraceMs = hangGraceMs,
    )

    /** Serves [libDir] as the prepared engine; the marker's minor tracks the probed runtime. */
    fun serve(
        libDir: File,
        version: String = BUNDLED_VERSION,
    ) {
        store.preparation.value = EnginePreparation.COMPILED
        store.trialPrepared =
            PreparedEngine(libDir.canonicalPath.toPath(), CompileMarker(1, version, paths.pythonMinor))
    }

    fun deadline(ms: Long): YtxDeadline = YtxDeadline(clock.elapsedRealtime() + ms)
}

/** Wraps the real [YtxProcess] so tests can reach the live child (kill, isAlive, pid). */
internal class SpySpawner(
    private val inner: YtxSpawner,
) : YtxSpawner {
    @Volatile var last: YtxChild? = null
    var starts = 0

    /** Set to a spawn failure (e.g. `YtxSpawnException`) to force a START_FAILED transition. */
    @Volatile var failStart: YtxSpawnException? = null

    override fun start(
        libDir: File,
        engineVersion: String,
        replayDir: File?,
        debugHooks: Boolean,
        listener: YtxChild.Listener,
    ): YtxChild {
        starts++
        failStart?.let { throw it }
        return inner.start(libDir, engineVersion, replayDir, debugHooks, listener).also { last = it }
    }
}

internal fun tempRoot(name: String): File = Files.createTempDirectory("nd-ytx-$name").toFile().apply { deleteOnExit() }

/**
 * The compiled bundled lib, built once per test JVM: `BundledEngineStore` extracts the shipped
 * zip, then a real child runs its documented compile pass (announce → compileall → marker).
 * Tests share the result: children see marker+sentinel and reach `ready` in one import.
 * A test that needs the *uncompiled* tree uses its own `BundledEngineStore.prepareBundled()`.
 */
internal object SharedEngine {
    val lib: File by lazy { compile() }

    private fun compile(): File =
        runBlocking {
            val python = runnerPython()
            val root = tempRoot("shared")
            val dirs =
                AppDirs(
                    data = root.resolve("data").toPath(),
                    config = root.resolve("config").toPath(),
                    cache = root.resolve("cache").toPath(),
                    state = root.resolve("state").toPath(),
                    logs = root.resolve("logs").toPath(),
                    downloadsDefault = root.resolve("downloads").toPath(),
                ).apply { ensureCreated() }
            val resources = engineResourcesDir()
            val paths = DesktopEngineStorePaths(dirs, { resources }, { python })
            val store = BundledEngineStore(paths, 1, RealClock)
            val prepared = store.prepareBundled()
            val ready = java.util.concurrent.CountDownLatch(1)
            val failure =
                java.util.concurrent.atomic
                    .AtomicReference<Throwable?>()
            val child =
                YtxProcess({ python }, paths, stderrSink = {}).start(
                    prepared.libDir.toFile(),
                    prepared.marker.engineVersion,
                    replayDir = null,
                    debugHooks = false,
                    listener =
                        object : YtxChild.Listener {
                            override fun onMessage(message: kotlinx.serialization.json.JsonObject) {
                                if (message["state"]?.jsonPrimitive?.contentOrNull == "ready") {
                                    ready.countDown()
                                }
                            }

                            override fun onProtocolViolation(reason: String) {
                                failure.compareAndSet(null, IllegalStateException("protocol violation: $reason"))
                                ready.countDown()
                            }

                            override fun onExit(code: Int) {
                                ready.countDown()
                            }
                        },
                )
            if (!ready.await(180, java.util.concurrent.TimeUnit.SECONDS)) {
                child.destroyForcibly()
                error("shared engine compile timed out")
            }
            failure.get()?.let { throw it }
            check(
                prepared.libDir
                    .toFile()
                    .resolve("yt_dlp/__init__.pyc")
                    .isFile,
            ) {
                "child did not compile the shared lib"
            }
            EngineCompileMarkers.write(prepared)
            child.closeStdin()
            child.awaitExit(10_000) ?: child.destroyForcibly()
            prepared.libDir.toFile()
        }
}
