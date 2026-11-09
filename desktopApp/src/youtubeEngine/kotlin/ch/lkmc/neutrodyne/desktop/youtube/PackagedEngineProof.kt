// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.desktop.smoke.EngineSmokeProof
import ch.lkmc.neutrodyne.desktop.smoke.SmokeMode
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.download.api.RequestResult
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.StreamKind
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

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
 * the network. `hl`/`gl` are pinned to the recording's en/US for every resolve in the proof
 * so the strict replay key does not depend on the host locale.
 *
 * When the armed scenario also carries `media.json`, the proof continues past the recorded
 * resolve into the media chain (the Android `YouTubeReleaseSmokeTest` flow): a
 * [LoopbackMediaFixture] bound to `127.0.0.1:0`, the recorded response bodies rewritten to
 * the bound URL through [RecordedMediaScenario] (requests, statuses and metadata untouched),
 * a synthetic `YOUTUBE_CHANNEL` podcast/episode seeded into the throwaway store, real
 * `playEpisode` playback on the headless FFmpeg backend until the consumed position passes
 * [MIN_PLAYED_MS], then a `DownloadController` MANUAL download whose completed file must
 * hash to the deterministic fixture composition and whose post-boundary HTTP requests must
 * be exactly the 10 MiB chunks plus tail.
 *
 * The returned evidence is what `smoke-start.sh --engine-replay` validates: the resolve's
 * meaningful answer, the actual interpreter and resource paths inside the image, the
 * child-reported interpreter and package versions against the build's pin, `child` `exited`
 * once the OS reports the last child dead after `shutdown`, and — when the media phase
 * ran — the separate `playback` and `download` evidence objects.
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
    private val database: () -> NeutrodyneDatabase,
    private val playbackController: () -> PlaybackController,
    private val playbackStateSource: () -> PlaybackStateSource,
    private val downloadController: () -> DownloadController,
    private val localMediaIndex: () -> LocalMediaIndex,
) : EngineSmokeProof {
    override suspend fun run(replayDir: File): JsonObject {
        require(replayDir.isDirectory && File(replayDir, "http.json").isFile) {
            "engine proof needs a recorded scenario directory with http.json"
        }
        val engineDir = packagedEngineDir()
        val interpreter = packagedInterpreter(engineDir)
        val expectedVersion = packagedInputs(engineDir)
        val mediaSpec = MediaScenarioSpec.read(replayDir)

        // The recorded exchanges were captured with hl=en/gl=US; pin the request context's
        // locale for the whole proof — the download lane's re-resolves included — so the
        // strict replay key does not depend on the host's locale.
        val previousLocale = Locale.getDefault()
        var fixture: LoopbackMediaFixture? = null
        var rewritten: File? = null
        var seeded: LongArray? = null
        var playbackStarted = false
        try {
            Locale.setDefault(RECORDED_LOCALE)

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
                withTimeoutOrNull(CAPABILITY_WAIT_MS) {
                    capabilities.capabilities.first { it.inAppPlayback }
                },
            ) {
                "in-app playback never became capable (reason ${capabilities.capabilities.value.externalReason})"
            }

            var audio = resolveRecorded()
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
            check(version.host == HOST_STDIO) {
                "engine proof must run on the stdio host, got ${version.host}"
            }
            check(version.ytDlp == expectedVersion) {
                "the child's yt-dlp ${version.ytDlp} is not the pinned bundled $expectedVersion"
            }

            var ready = transport.status.value
            check(ready.state == HostState.READY && ready.engineVersion == expectedVersion) {
                "the engine host is not READY on the bundled version"
            }
            var pid = ready.pid ?: error("the engine child has no pid")
            transport.shutdown()
            check(awaitExited(pid)) { "the engine child did not exit after shutdown" }
            check(
                transport.status.value.state == HostState.STOPPED &&
                    transport.status.value.pid == null,
            ) {
                "the engine transport did not report the child stopped"
            }

            var playbackEvidence: JsonObject? = null
            var downloadEvidence: JsonObject? = null
            if (mediaSpec != null) {
                val loop = mediaSpec.openFixture(replayDir)
                fixture = loop
                val rewrite = Files.createTempDirectory(REPLAY_TEMP_PREFIX).toFile()
                rewritten = rewrite
                RecordedMediaScenario.rewriteResolve(replayDir, rewrite, loop.mediaUrl, loop.media.size)

                // The previous child is dead (the shutdown above): repoint the arming-owned
                // scenario path so the next spawn replays the rewritten bodies, and drop the
                // recorded-URL cache entry so nothing replays the stale media URL.
                armedScenario.set(rewrite)
                streams.invalidateAll()
                audio = resolveRecorded()
                checkMediaResolve(audio, loop.media.size)

                seeded = seedRecordedItem()
                playbackStarted = true // playEpisode may have committed before a later await throws
                playbackEvidence = provePlayback(seeded[1])
                // Pause retains decoder reads. Release them and account for their completed
                // responses before measuring the download's three bounded range requests.
                withTimeout(PAUSE_WAIT_MS) {
                    playbackController().dismiss()
                    playbackStateSource().nowPlaying.first { it == null }
                }
                val boundary = runInterruptible(Dispatchers.IO) { loop.awaitRequestBoundary() }
                downloadEvidence = proveDownload(loop, seeded[1], boundary)

                ready = transport.status.value
                pid = ready.pid ?: error("the media-phase engine child has no pid")
                transport.shutdown()
                check(awaitExited(pid)) { "the media-phase engine child did not exit after shutdown" }
                check(
                    transport.status.value.state == HostState.STOPPED &&
                        transport.status.value.pid == null,
                ) {
                    "the engine transport did not report the media-phase child stopped"
                }
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
                playbackEvidence?.let { put("playback", it) }
                downloadEvidence?.let { put("download", it) }
                put("child", "exited")
            }
        } finally {
            cleanUp(seeded, playbackStarted, fixture, rewritten, replayDir)
            Locale.setDefault(previousLocale)
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

    /** The one recorded `resolve` call, in the recording's locale. */
    private suspend fun resolveRecorded(): ResolvedAudio =
        when (
            val result =
                withTimeout(RESOLVE_BUDGET_MS) {
                    streams.resolveAudio(RECORDED_VIDEO_ID, AudioPref(), ResolvePurpose.PLAYBACK)
                }
        ) {
            is ResolveResult.Ok -> result.audio
            else -> error("recorded resolve did not succeed: $result")
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

    /**
     * The rewritten scenario's resolve contract: same video, format, mime, duration, expiry
     * and last-modified as the recording; the media URL's length and host are now the
     * loopback fixture's.
     */
    private fun checkMediaResolve(
        audio: ResolvedAudio,
        mediaSize: Long,
    ) {
        require(audio.videoId == RECORDED_VIDEO_ID) { "media resolve answered for the wrong video" }
        require(audio.formatId == EXPECTED_FORMAT_ID) { "media resolve returned format ${audio.formatId}" }
        require(audio.mimeType == EXPECTED_MIME) { "media resolve returned ${audio.mimeType}" }
        require(audio.contentLength == mediaSize) {
            "media resolve returned a ${audio.contentLength}-byte stream"
        }
        require(audio.durationMs == EXPECTED_DURATION_MS) {
            "media resolve returned a ${audio.durationMs}ms video"
        }
        require(audio.expiresAtMs == EXPECTED_EXPIRES_AT_MS) { "media resolve dropped the recorded expiry" }
        require(audio.lastModifiedMicros == EXPECTED_LAST_MODIFIED_MICROS) {
            "media resolve dropped the recorded last-modified"
        }
        require(!audio.isDrc && audio.audioTrackId == null) { "media resolve picked a drc/dub variant" }
        require(urlHost(audio) == LOOPBACK_HOST) { "media resolve did not return the loopback stream" }
    }

    /**
     * The Android `YouTubeReleaseSmokeTest` seed: one `YOUTUBE_CHANNEL` podcast whose
     * channel ID matches the recording and one stub episode carrying the recorded video ID
     * — the only state the real `playEpisode`/download flows need in the throwaway store.
     */
    internal suspend fun seedRecordedItem(): LongArray {
        val db = database()
        val now = clock.now()
        val podcastId =
            db.podcastDao().insertPodcast(
                PodcastEntity(
                    syncId = PROOF_SYNC_ID,
                    sourceType = SourceType.YOUTUBE_CHANNEL,
                    feedUrl = "https://www.youtube.com/channel/$RECORDED_CHANNEL_ID",
                    feedKey = "youtube:channel:$RECORDED_CHANNEL_ID",
                    youtubeChannelId = RECORDED_CHANNEL_ID,
                    title = "Recorded channel",
                    artworkKey = "proof-media",
                    status = PodcastStatus.ACTIVE,
                    initialFetch = false,
                    // The live runner must never poll this fixture through the RSS client.
                    nextRefreshAt = Long.MAX_VALUE,
                    pagingComplete = true,
                    subscribedAt = now,
                ),
            )
        val episodeId =
            db.episodeDao().insertStub(
                EpisodeEntity(
                    podcastId = podcastId,
                    identityKey = "youtube:$RECORDED_VIDEO_ID",
                    title = "Recorded audio",
                    sortDate = now,
                    feedOrder = 0,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    inFeed = false,
                    externalMediaId = RECORDED_VIDEO_ID,
                    durationMs = EXPECTED_DURATION_MS,
                    contentHash = 0,
                ),
            )
        check(episodeId > 0) { "the proof episode did not insert" }
        return longArrayOf(podcastId, episodeId)
    }

    /**
     * Real `playEpisode` through the graph's controller: READY on the YOUTUBE stream and
     * playing, then the consumed position reaching [MIN_PLAYED_MS] — decoded frames counted
     * by the engine's output path, not a fake clock — then pause and idle before the
     * download so its HTTP requests cannot blur into the playback prefetch evidence.
     */
    private suspend fun provePlayback(episodeId: Long): JsonObject {
        val controller = playbackController()
        val states = playbackStateSource()
        var started = controller.playEpisode(episodeId)
        if (started is PlayResult.NeedsMeteredConsent) {
            // A metered NIC asks for the same consent a user grants; answer it once and retry.
            controller.grantMeteredStreaming()
            started = controller.playEpisode(episodeId)
        }
        require(started == PlayResult.Started) { "the recorded YouTube start was refused: $started" }
        val playing =
            checkNotNull(
                withTimeout(PLAYING_WAIT_MS) {
                    states.nowPlaying.first {
                        it != null && it.episodeId == episodeId &&
                            it.stream == StreamKind.YOUTUBE &&
                            it.phase == PlayerPhase.READY && it.isPlaying
                    }
                },
            )
        val reached =
            checkNotNull(
                withTimeout(POSITION_WAIT_MS) {
                    states.nowPlaying.first {
                        it != null && it.episodeId == episodeId &&
                            it.position.positionMs >= MIN_PLAYED_MS
                    }
                },
            )
        val positionMs = reached.position.positionMs
        controller.pause()
        withTimeout(PAUSE_WAIT_MS) {
            states.nowPlaying.first { it == null || (!it.isPlaying && !it.position.advancing) }
        }
        return buildJsonObject {
            put("stream", StreamKind.YOUTUBE.name)
            put("phase", playing.phase.name)
            put("playing", true)
            put("positionMs", positionMs)
        }
    }

    /**
     * The real `DownloadController` lane: queue MANUAL with metered allowed, await the
     * `COMPLETED` row plus its local URI, then prove the file is byte-identical to the
     * deterministic fixture composition and the post-boundary HTTP requests are exactly the
     * download's own chunks — never a digest over every served byte (playback prefetch and
     * retries may repeat ranges legitimately).
     */
    private suspend fun proveDownload(
        fixture: LoopbackMediaFixture,
        episodeId: Long,
        boundary: Int,
    ): JsonObject {
        val db = database()
        val request =
            downloadController().request(
                listOf(episodeId),
                DownloadLane.MANUAL,
                allowMetered = true,
            )
        require(request is RequestResult.Queued && request.queued == 1) {
            "the recorded YouTube download was refused: $request"
        }
        val completed =
            withTimeout(DOWNLOAD_WAIT_MS) {
                var row = db.downloadDao().byEpisode(episodeId)
                while (row?.state != DownloadState.COMPLETED &&
                    row?.state != DownloadState.FAILED &&
                    row?.state != DownloadState.MISSING
                ) {
                    delay(POLL_INTERVAL_MS)
                    row = db.downloadDao().byEpisode(episodeId)
                }
                row
            }
        require(completed.state == DownloadState.COMPLETED) {
            "the recorded download did not complete: ${completed.state} ${completed.lastError}"
        }
        val uri =
            withTimeout(LOCAL_URI_WAIT_MS) {
                var local: String?
                do {
                    local = localMediaIndex().localUriOrNull(episodeId)
                    if (local == null) delay(POLL_INTERVAL_MS)
                } while (local == null)
                local
            }
        val file = Path.of(URI(checkNotNull(uri)))
        check(Files.size(file) == fixture.media.size) {
            "the completed file is ${Files.size(file)} bytes, not ${fixture.media.size}"
        }
        val fileSha = sha256Hex(file)
        check(fileSha == fixture.media.sha256Hex) {
            "the completed file's digest does not match the fixture composition"
        }
        val chunks = downloadChunks(fixture.requests(), fixture.media, boundary)
        return buildJsonObject {
            put("state", DownloadState.COMPLETED.name)
            put("bytes", fixture.media.size)
            putJsonArray("chunks") { chunks.forEach { add(JsonPrimitive(it)) } }
            put("sha256", fileSha)
        }
    }

    /** Bounded, best-effort teardown of whatever the media phase reached; never masks the real error. */
    private suspend fun cleanUp(
        seeded: LongArray?,
        playbackStarted: Boolean,
        fixture: LoopbackMediaFixture?,
        rewritten: File?,
        replayDir: File,
    ) {
        if (playbackStarted) {
            runCatching {
                withTimeout(CLEANUP_WAIT_MS) { playbackController().dismiss() }
            }.onFailure { Log.w(TAG, it) { "media proof playback teardown failed" } }
        }
        seeded?.let { (podcastId, episodeId) ->
            val db = runCatching { database() }.getOrNull()
            runCatching {
                downloadController().cancel(listOf(episodeId))
                downloadController().delete(listOf(episodeId), DeleteReason.LIFECYCLE)
            }.onFailure { Log.w(TAG, it) { "media proof download teardown failed" } }
            if (db != null) {
                runCatching {
                    withTimeout(CLEANUP_WAIT_MS) {
                        db.podcastDao().deleteCascade(podcastId, clock.now())
                    }
                }.onFailure { Log.w(TAG, it) { "media proof seed teardown failed" } }
            }
        }
        runCatching { transport.shutdown() }
            .onFailure { Log.w(TAG, it) { "media proof engine shutdown failed" } }
        runCatching { fixture?.close() }
            .onFailure { Log.w(TAG, it) { "media proof fixture close failed" } }
        armedScenario.set(replayDir)
        rewritten?.deleteRecursively()
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

    /** The armed scenario's optional media contract; absent means the resolve-only proof. */
    internal data class MediaScenarioSpec(
        val total: Long,
        val head: String,
        val headSha256: String,
    ) {
        /** Reads the declared head (digest-checked), composes the fixture and binds the server. */
        fun openFixture(scenarioDir: File): LoopbackMediaFixture {
            val headFile = File(scenarioDir, head)
            require(headFile.isFile) { "the media scenario head is missing: $head" }
            val headBytes = headFile.readBytes()
            require(sha256Hex(headBytes).equals(headSha256, ignoreCase = true)) {
                "the media scenario head's digest does not match $FILE_NAME"
            }
            return LoopbackMediaFixture(FixtureMediaBytes(headBytes, total))
        }

        companion object {
            const val FILE_NAME = "media.json"
            private val HEX_64 = Regex("[0-9a-fA-F]{64}")

            /** Null when the armed scenario has no media contract; errors on a malformed one. */
            fun read(scenarioDir: File): MediaScenarioSpec? {
                val file = File(scenarioDir, FILE_NAME)
                if (!file.isFile) return null
                val spec =
                    try {
                        Json.parseToJsonElement(file.readText()).jsonObject
                    } catch (failure: Exception) {
                        throw IllegalArgumentException("$FILE_NAME is not a JSON object", failure)
                    }
                val total =
                    spec["total"]?.jsonPrimitive?.longOrNull
                        ?: throw IllegalArgumentException("$FILE_NAME carries no byte total")
                val head =
                    spec["head"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("$FILE_NAME carries no head file")
                val headSha256 =
                    spec["headSha256"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("$FILE_NAME carries no head digest")
                require(total > 0) { "$FILE_NAME total must be positive" }
                require(
                    head.isNotBlank() && head != "." && head != ".." &&
                        head.none { it == '/' || it == '\\' },
                ) {
                    "$FILE_NAME head must be a bare file name inside the scenario"
                }
                require(headSha256.matches(HEX_64)) { "$FILE_NAME headSha256 must be 64 hex digits" }
                return MediaScenarioSpec(total, head, headSha256)
            }
        }
    }

    companion object {
        /**
         * The arming-owned mutable scenario path: [replayArming] seeds it with the armed
         * directory, the media phase repoints it at the rewritten copy only after the
         * previous child is dead — `StdioYtxTransport` re-reads it at every child spawn,
         * so no process-global property is ever mutated.
         */
        private val armedScenario = AtomicReference<File?>()

        /**
         * The production wiring of the test-only replay seam (11 Stdio protocol): the smoke
         * launcher names the recorded scenario through [SmokeMode.ENGINE_REPLAY_PROPERTY]
         * and [SmokeMode.isEnabled] must also hold — every other run keeps the documented
         * null arming. [SmokeMode.engineReplayDir] reads both properties once.
         */
        fun replayArming(): StdioReplayArming? {
            val dir = SmokeMode.engineReplayDir()
            armedScenario.set(dir)
            return dir?.let {
                StdioReplayArming { armedScenario.get()?.canonicalPath?.toPath() }
            }
        }

        /**
         * The ranges the download lane's 10 MiB chunking must request for [total] bytes —
         * the same constant `YouTubeTransferSource.CHUNK_BYTES` chunks with, repeated here
         * because the gate compares verbatim evidence, not code.
         */
        internal fun expectedChunkRanges(total: Long): List<String> {
            require(total > 0)
            val ranges = mutableListOf<String>()
            var offset = 0L
            while (offset < total) {
                ranges += "bytes=$offset-${minOf(offset + CHUNK_BYTES, total) - 1}"
                offset += CHUNK_BYTES
            }
            return ranges
        }

        /**
         * The post-boundary download evidence: every request after [boundary] must be a
         * 206 for exactly the expected chunk range of [media], with the full range's bytes
         * written and the served body's digest equal to the composition's own range digest.
         * Returns the verbatim range list for the report.
         */
        internal fun downloadChunks(
            requests: List<LoopbackMediaFixture.ServedRequest>,
            media: FixtureMediaBytes,
            boundary: Int,
        ): List<String> {
            require(boundary in 0..requests.size)
            val post = requests.drop(boundary)
            val expected = expectedChunkRanges(media.size)
            val actual = post.map { it.range }
            require(actual == expected) {
                "download requests after the playback boundary are $actual, expected $expected"
            }
            var offset = 0L
            for (request in post) {
                val length = minOf(CHUNK_BYTES, media.size - offset)
                require(request.status == STATUS_PARTIAL_CONTENT) {
                    "a download chunk was answered ${request.status}, not 206"
                }
                require(request.bytesWritten == length) {
                    "a download chunk wrote ${request.bytesWritten} bytes, not $length"
                }
                require(request.bodySha256 == sha256Hex(media.readRange(offset, length.toInt()))) {
                    "a download chunk's served digest does not match the fixture"
                }
                offset += length
            }
            return expected
        }

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").apply { update(bytes) }.let(::hex)

        private fun sha256Hex(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { stream ->
                val buffer = ByteArray(DIGEST_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return hex(digest)
        }

        private fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

        private const val TAG = "EngineProof"
        private const val RECORDED_VIDEO_ID = "aaaaaaaaaaA"
        private const val RECORDED_CHANNEL_ID = "UCaaaaaaaaaaaaaaaaaaaaaA"
        private const val PROOF_SYNC_ID = "00000000-0000-4000-8000-00000000ac2e"
        private const val EXPECTED_FORMAT_ID = "140"
        private const val EXPECTED_MIME = "audio/mp4"
        private const val EXPECTED_CONTENT_LENGTH = 1_000L
        private const val EXPECTED_DURATION_MS = 120_000L
        private const val EXPECTED_EXPIRES_AT_MS = 4_102_444_800_000L
        private const val EXPECTED_LAST_MODIFIED_MICROS = 123L
        private const val EXPECTED_URL_HOST = "media.example.invalid"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val HOST_STDIO = "stdio"
        private const val REPLAY_TEMP_PREFIX = "nd-media-replay-"

        /** The download lane's chunk size (07 `YouTubeTransferSource.CHUNK_BYTES`). */
        private const val CHUNK_BYTES = 10L * 1024 * 1024
        private const val STATUS_PARTIAL_CONTENT = 206
        private const val DIGEST_BUFFER_BYTES = 64 * 1024

        /** The consumed position that proves real decoding (never a fake clock). */
        private const val MIN_PLAYED_MS = 500L

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
        private const val PLAYING_WAIT_MS = 30_000L
        private const val POSITION_WAIT_MS = 60_000L
        private const val PAUSE_WAIT_MS = 15_000L
        private const val DOWNLOAD_WAIT_MS = 90_000L
        private const val LOCAL_URI_WAIT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 50L
        private const val CLEANUP_WAIT_MS = 10_000L
    }
}
