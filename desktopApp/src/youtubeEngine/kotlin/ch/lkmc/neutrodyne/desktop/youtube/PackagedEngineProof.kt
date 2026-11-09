// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.desktop.smoke.EngineSmokeProof
import ch.lkmc.neutrodyne.desktop.smoke.SmokeMode
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.ResolvedAudio
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.HostState
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.engine.YtxTransport
import ch.lkmc.neutrodyne.youtube.engine.YtxVersion
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DesktopEngineStorePaths
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.PythonRuntime
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.PythonRuntimeLocator
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.StdioReplayArming
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * MD3 AC2's packaged engine proof (11 Smoke mode step 6, [SmokeMode]'s `engine` step): the
 * installed image proves it resolves a repository-recorded YouTube item through its own
 * bundled runtime — the packaged CPython under `engine/python/`, the packaged
 * `neutrodyne_ytx` shim, the packaged yt-dlp zip prepared into the smoke's throwaway store,
 * the packaged keys and CA bundle — over the real `StdioYtxTransport`/`YtxProcess` child and
 * the real `YouTubeStreamResolver` application service.
 *
 * The run is packaged-only and fail-closed: every development override
 * (`neutrodyne.ytx.python`, `neutrodyne.engineResources`, `neutrodyne.rootDir`) must be
 * unset, `compose.application.resources.dir` must name a directory carrying `engine/`, the
 * resolved resources must canonicalise to it and the probed interpreter must canonicalise
 * inside `engine/python/` — no PATH, property or dev-tree fallback can stand in. The replay
 * arming the child carries is the test-only seam's one production-wired use
 * ([replayArming]), enabled only inside smoke mode, and `ReplayRH` answers exactly the
 * recorded `resolve` exchanges — any other request poisons the call rather than reaching
 * the network. `hl`/`gl` are pinned to the recording's en/US for the one resolve so the
 * strict replay key does not depend on the host locale.
 *
 * The returned evidence is what `smoke-start.sh --engine-replay` validates: the resolve's
 * meaningful answer, the actual interpreter and resource paths inside the image, the
 * child-reported interpreter and package versions against the build's pin, and `child`
 * `exited` once the OS reports the child's process dead after `shutdown`.
 */
internal class PackagedEngineProof(
    private val engineResources: EngineResourceDir,
    private val runtime: PythonRuntimeLocator,
    private val paths: DesktopEngineStorePaths,
    private val store: EngineStore,
    private val identity: CompileMarker,
    private val transport: YtxTransport,
    private val streams: YouTubeStreamResolver,
    private val capabilities: YouTubeCapabilitiesSource,
    private val context: () -> YtxContext,
    private val clock: Clock,
) : EngineSmokeProof {
    override suspend fun run(replayDir: File): JsonObject {
        require(replayDir.isDirectory && File(replayDir, "http.json").isFile) {
            "engine proof needs a recorded scenario directory with http.json"
        }
        val engineDir = packagedEngineDir()
        val interpreter = packagedInterpreter(engineDir)
        val expectedVersion = packagedInputs(engineDir)

        // The production prepare+ping pass, driven explicitly so the proof does not depend
        // on the preparation observer's schedule: extract the bundled tree, bind the child
        // (which compiles the lib once), then let the store see the compile marker.
        withTimeout(PREPARE_BUDGET_MS) {
            store.prepareBundled()
            transport.call(
                YtxMethod.PING,
                YtxControlRequest(context()).toJson(),
                YtxDeadline(clock.elapsedRealtime() + PREPARE_BUDGET_MS),
            )
            store.loadStatus()
        }
        check(store.preparation.value == EnginePreparation.COMPILED) {
            "engine preparation did not reach COMPILED"
        }
        checkNotNull(
            withTimeoutOrNull(CAPABILITY_WAIT_MS) { capabilities.capabilities.first { it.inAppPlayback } },
        ) {
            "in-app playback never became capable (reason ${capabilities.capabilities.value.externalReason})"
        }

        // The recorded exchanges were captured with hl=en/gl=US; pin the request context's
        // locale for this one resolve so the strict replay key does not depend on the
        // host's locale (request_key compares method, scrubbed URL and JSON body).
        val previousLocale = Locale.getDefault()
        val audio =
            try {
                Locale.setDefault(RECORDED_LOCALE)
                when (
                    val result =
                        withTimeout(RESOLVE_BUDGET_MS) {
                            streams.resolveAudio(RECORDED_VIDEO_ID, AudioPref(), ResolvePurpose.PLAYBACK)
                        }
                ) {
                    is ResolveResult.Ok -> result.audio
                    else -> error("recorded resolve did not succeed: $result")
                }
            } finally {
                Locale.setDefault(previousLocale)
            }
        checkResolve(audio)

        val version =
            Json.decodeFromString(
                YtxVersion.serializer(),
                transport.call(
                    YtxMethod.VERSION,
                    YtxControlRequest(context()).toJson(),
                    YtxDeadline(clock.elapsedRealtime() + VERSION_BUDGET_MS),
                ),
            )
        check(version.host == HOST_STDIO) { "engine proof must run on the stdio host, got ${version.host}" }
        check(version.ytDlp == expectedVersion) {
            "the child's yt-dlp ${version.ytDlp} is not the pinned bundled $expectedVersion"
        }

        val ready = transport.status.value
        check(ready.state == HostState.READY && ready.engineVersion == expectedVersion) {
            "the engine host is not READY on the bundled version"
        }
        val pid = ready.pid ?: error("the engine child has no pid")
        transport.shutdown()
        check(awaitExited(pid)) { "the engine child did not exit after shutdown" }
        check(
            transport.status.value.state == HostState.STOPPED && transport.status.value.pid == null,
        ) {
            "the engine transport did not report the child stopped"
        }

        return buildJsonObject {
            put("resolve", "ok")
            put("videoId", audio.videoId)
            put("formatId", audio.formatId)
            put("urlHost", urlHost(audio))
            put("durationMs", audio.durationMs)
            put("interpreter", interpreter.executable.canonicalPath)
            put("resources", engineDir.path)
            put("python", version.python)
            put("engineVersion", ready.engineVersion)
            put("ytDlp", version.ytDlp)
            put("ejs", version.ejs)
            put("expectedYtdlp", expectedVersion)
            put("shimApi", version.shimApi)
            put("host", version.host)
            put("cacert", paths.cacertFile()?.isFile == true)
            put("child", "exited")
        }
    }

    /**
     * The packaged-only gate (MD3 AC2): every development override must be unset, the
     * packaged resource directory must carry `engine/`, and the production resolver must
     * agree it resolves there — a dev tree can never manufacture this proof.
     */
    private fun packagedEngineDir(): File {
        for (property in DEV_PROPERTIES) {
            require(System.getProperty(property).isNullOrBlank()) {
                "the packaged engine proof forbids -D$property"
            }
        }
        val resources =
            System.getProperty("compose.application.resources.dir")?.let(::File)
                ?: error("the packaged engine proof needs compose.application.resources.dir")
        val dir = File(resources, "engine")
        require(dir.isDirectory) { "packaged engine resources are missing" }
        require(engineResources.dir?.canonicalFile == dir.canonicalFile) {
            "engine resources resolve outside the packaged image"
        }
        return dir.canonicalFile
    }

    /** The probed runtime, required to canonicalise inside `engine/python/`. */
    private fun packagedInterpreter(engineDir: File): PythonRuntime {
        val located = runtime.locate() ?: error("the packaged interpreter did not probe")
        val home = File(engineDir, "python").canonicalFile
        require(
            located.executable.canonicalFile.path
                .startsWith(home.path + File.separatorChar),
        ) {
            "the engine interpreter is not the packaged one"
        }
        return located
    }

    /**
     * The bundled inputs the child needs, through the production resolvers: the shim's
     * bootstrap, the CA bundle, the yt-dlp archive's readability and both trust keys —
     * then the manifest's pinned version, which must equal the build's engine pin.
     */
    private fun packagedInputs(engineDir: File): String {
        require(paths.shimPackageDir()?.let { File(it, "bootstrap.py").isFile } == true) {
            "the packaged shim is missing under ${engineDir.path}"
        }
        require(paths.cacertFile()?.isFile == true) { "the packaged CA bundle is missing" }
        val bundled = Json.parseToJsonElement(paths.bundledJson()).jsonObject
        val version =
            bundled["version"]?.jsonPrimitive?.content
                ?: error("the packaged engine manifest carries no version")
        require(version == identity.engineVersion) {
            "the packaged engine manifest is not the build's pinned engine"
        }
        paths.openBundledZip().use { source ->
            check(source.buffer().readByteArray(4).size == 4) {
                "the packaged yt-dlp archive is empty"
            }
        }
        check(paths.manifestKeysText().isNotBlank()) { "the packaged engine manifest key is missing" }
        check(paths.upstreamKeyText().isNotBlank()) { "the packaged upstream release key is missing" }
        return version
    }

    private fun checkResolve(audio: ResolvedAudio) {
        require(audio.videoId == RECORDED_VIDEO_ID) { "resolve answered for the wrong video" }
        require(audio.formatId == EXPECTED_FORMAT_ID) { "resolve returned format ${audio.formatId}" }
        require(audio.mimeType == EXPECTED_MIME) { "resolve returned ${audio.mimeType}" }
        require(audio.contentLength == EXPECTED_CONTENT_LENGTH) {
            "resolve returned a ${audio.contentLength}-byte stream"
        }
        require(audio.durationMs == EXPECTED_DURATION_MS) { "resolve returned a ${audio.durationMs}ms video" }
        require(audio.expiresAtMs == EXPECTED_EXPIRES_AT_MS) { "resolve returned an unexpected expiry" }
        require(!audio.isDrc && audio.audioTrackId == null) { "resolve picked a drc/dub variant" }
        require(urlHost(audio) == EXPECTED_URL_HOST) { "resolve did not return the recorded stream" }
    }

    /** The URL's host only — URLs themselves must never reach logs or the report. */
    private fun urlHost(audio: ResolvedAudio): String =
        URI(audio.url).host ?: error("resolve returned a URL with no host")

    /** OS-level proof the child is gone after `shutdown`, not merely reported stopped. */
    private suspend fun awaitExited(pid: Long): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return true
        val deadline = clock.elapsedRealtime() + EXIT_BUDGET_MS
        while (clock.elapsedRealtime() < deadline) {
            if (!handle.isAlive) return true
            delay(EXIT_POLL_MS)
        }
        return !handle.isAlive
    }

    companion object {
        /**
         * The production wiring of the test-only replay seam (11 Stdio protocol): the smoke
         * launcher names the recorded scenario through [SmokeMode.ENGINE_REPLAY_PROPERTY]
         * and [SmokeMode.isEnabled] must also hold — every other run keeps the documented
         * null arming. [SmokeMode.engineReplayDir] reads both properties once.
         */
        fun replayArming(): StdioReplayArming? =
            SmokeMode.engineReplayDir()?.let { dir ->
                StdioReplayArming { dir.canonicalPath.toPath() }
            }

        private const val RECORDED_VIDEO_ID = "aaaaaaaaaaA"
        private const val EXPECTED_FORMAT_ID = "140"
        private const val EXPECTED_MIME = "audio/mp4"
        private const val EXPECTED_CONTENT_LENGTH = 1_000L
        private const val EXPECTED_DURATION_MS = 120_000L
        private const val EXPECTED_EXPIRES_AT_MS = 4_102_444_800_000L
        private const val EXPECTED_URL_HOST = "media.example.invalid"
        private const val HOST_STDIO = "stdio"

        /** Overrides that must stay unset: each one steers resolution off the packaged image. */
        private val DEV_PROPERTIES =
            listOf("neutrodyne.ytx.python", "neutrodyne.engineResources", "neutrodyne.rootDir")

        private val RECORDED_LOCALE: Locale = Locale.US

        private const val PREPARE_BUDGET_MS = YtxLimits.COMPILE_MS + YtxLimits.START_MS + 30_000L
        private const val CAPABILITY_WAIT_MS = 30_000L
        private const val RESOLVE_BUDGET_MS = 45_000L
        private const val VERSION_BUDGET_MS = 15_000L
        private const val EXIT_BUDGET_MS = 10_000L
        private const val EXIT_POLL_MS = 50L
    }
}
