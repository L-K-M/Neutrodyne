// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import ch.lkmc.neutrodyne.desktop.smoke.SmokeMode
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ExtractionReservation
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealthState
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.HostKind
import ch.lkmc.neutrodyne.youtube.engine.HostState
import ch.lkmc.neutrodyne.youtube.engine.HostStatus
import ch.lkmc.neutrodyne.youtube.engine.IpFamilyHints
import ch.lkmc.neutrodyne.youtube.engine.ResolvedUrlCache
import ch.lkmc.neutrodyne.youtube.engine.YtDlpClient
import ch.lkmc.neutrodyne.youtube.engine.YtDlpEngine
import ch.lkmc.neutrodyne.youtube.engine.YtDlpErrorMapper
import ch.lkmc.neutrodyne.youtube.engine.YtDlpStreamResolver
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.engine.YtxTransport
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DesktopEngineStorePaths
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.DesktopPythonRuntimeLocator
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.PythonRuntime
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.PythonRuntimeLocator
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.StdioReplayArming
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.StdioYtxTransport
import ch.lkmc.neutrodyne.youtube.ytdlpdesktop.YtxProcess
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [PackagedEngineProof]'s fail-closed gates (MD3 AC2): a development override, a missing or
 * mismatched packaged resource tree and a non-packaged interpreter must each refuse the
 * proof — never fabricate a packaged success. The real collaborators are never reached:
 * every case dies on a `require`/`error` before the child, store or resolver is touched.
 */
class PackagedEngineProofTest {
    private val savedProperties = mutableMapOf<String, String?>()

    @Before
    fun saveProperties() {
        for (property in PROOF_PROPERTIES) {
            savedProperties[property] = System.getProperty(property)
            System.clearProperty(property)
        }
    }

    @After
    fun restoreProperties() {
        for ((property, value) in savedProperties) {
            if (value == null) System.clearProperty(property) else System.setProperty(property, value)
        }
    }

    private val unexercisedTransport =
        object : YtxTransport {
            override val status = MutableStateFlow(HostStatus(HostState.STOPPED, HostKind.STDIO, workers = 1))

            override suspend fun call(
                method: YtxMethod,
                json: String,
                deadline: YtxDeadline,
            ): String = error("the proof must not reach the transport")

            override fun cancel(callId: Long) {}

            override fun prewarm(reason: PrewarmReason) {}

            override fun shutdown() {}
        }

    private val unexercisedStreams =
        object : YouTubeStreamResolver {
            override suspend fun resolveAudio(
                videoId: String,
                pref: AudioPref,
                purpose: ResolvePurpose,
            ): ResolveResult = error("the proof must not reach the resolver")

            override fun invalidate(videoId: String) {}

            override fun invalidateAll() {}
        }

    private val unexercisedCapabilities =
        object : YouTubeCapabilitiesSource {
            override val capabilities =
                MutableStateFlow(
                    YouTubeCapabilities(
                        inAppPlayback = false,
                        downloads = false,
                        channelSearch = false,
                        enrichment = false,
                        backCatalogue = false,
                        externalReason = ExternalReason.ENGINE_FAILED,
                    ),
                )
        }

    private fun proof(
        resources: File?,
        located: PythonRuntime? = null,
    ): PackagedEngineProof {
        val dirs = tempAppDirs("nd-proof")
        return PackagedEngineProof(
            engineResources = EngineResourceDir { resources },
            runtime = PythonRuntimeLocator { located },
            paths = DesktopEngineStorePaths(dirs, { resources }, { located }),
            store = FakeEngineStore(),
            identity = CompileMarker(1, "2026.08.19", "3.14"),
            transport = unexercisedTransport,
            streams = unexercisedStreams,
            capabilities = unexercisedCapabilities,
            context = { YtxContext("en", "US", "test", "V4") },
            clock = DesktopClock,
            database = { error("the proof must not reach the media phase") },
            playbackController = { error("the proof must not reach the media phase") },
            playbackStateSource = { error("the proof must not reach the media phase") },
            downloadController = { error("the proof must not reach the media phase") },
            localMediaIndex = { error("the proof must not reach the media phase") },
        )
    }

    private fun scenarioDir(): File =
        tempAppDirs("nd-scenario").data.toFile().parentFile!!.resolve("scenario").apply {
            mkdirs()
            resolve("http.json").writeText("[]")
        }

    private fun failure(block: suspend () -> Unit): String =
        runBlocking { runCatching { block() }.exceptionOrNull()!!.message!! }

    /** A fake image root: `<root>/engine/` exists so the packaged-dir gate passes. */
    private fun imageEngine(): Pair<File, File> {
        val root = tempAppDirs("nd-image").data.toFile().parentFile!!
        val engine = root.resolve("engine").apply { mkdirs() }
        return root to engine
    }

    @Test
    fun `refuses a scenario dir without http json`() {
        val empty = tempAppDirs("nd-empty").data.toFile().parentFile!!
        val message = failure { proof(null).run(empty) }
        assertThat(message).contains("recorded scenario")
    }

    @Test
    fun `refuses while any development override is armed`() {
        for (property in DEV_PROPERTIES) {
            System.setProperty(property, "/somewhere")
            val message = failure { proof(null).run(scenarioDir()) }
            assertThat(message).contains("forbids -D$property")
            System.clearProperty(property)
        }
    }

    @Test
    fun `refuses without the packaged resources property`() {
        val message = failure { proof(null).run(scenarioDir()) }
        assertThat(message).contains("compose.application.resources.dir")
    }

    @Test
    fun `refuses when the engine dir is missing`() {
        val (root, _) = imageEngine()
        val empty = root.resolve("empty").apply { mkdirs() }
        System.setProperty("compose.application.resources.dir", empty.path)
        val message = failure { proof(null).run(scenarioDir()) }
        assertThat(message).contains("packaged engine resources are missing")
    }

    @Test
    fun `refuses engine resources resolving outside the image`() {
        val (root, engine) = imageEngine()
        System.setProperty("compose.application.resources.dir", root.path)
        val elsewhere = tempAppDirs("nd-elsewhere").data.toFile().parentFile!!
        val message = failure { proof(elsewhere).run(scenarioDir()) }
        assertThat(message).contains("outside the packaged image")
    }

    @Test
    fun `refuses when the interpreter does not probe`() {
        val (root, engine) = imageEngine()
        System.setProperty("compose.application.resources.dir", root.path)
        val message = failure { proof(engine, located = null).run(scenarioDir()) }
        assertThat(message).contains("did not probe")
    }

    @Test
    fun `refuses an interpreter outside engine-python`() {
        val (root, engine) = imageEngine()
        System.setProperty("compose.application.resources.dir", root.path)
        val outside = root.resolve("python3").apply { writeText("#!") }
        val located = PythonRuntime(outside, "3.14")
        val message = failure { proof(engine, located).run(scenarioDir()) }
        assertThat(message).contains("not the packaged one")
    }

    /**
     * The gate chain reaching the bundled-input checks: image-shaped `engine/` plus an
     * interpreter inside `engine/python/` must next fail on the missing shim — proof that
     * the resources gate ordered before it accepted both.
     */
    @Test
    fun `reaches the bundled input checks on a packaged-shaped image`() {
        val (root, engine) = imageEngine()
        val interpreter = engine.resolve("python/bin/python3")
        interpreter.parentFile.mkdirs()
        interpreter.writeText("#!")
        System.setProperty("compose.application.resources.dir", root.path)
        val message = failure { proof(engine, PythonRuntime(interpreter, "3.14")).run(scenarioDir()) }
        assertThat(message).contains("packaged shim is missing")
    }

    /** The production boundary: replay arming exists only inside an armed smoke run. */
    @Test
    fun `replayArming arms only inside a smoke run`() {
        val dir = scenarioDir()
        assertThat(PackagedEngineProof.replayArming()).isNull()

        System.setProperty(SmokeMode.ENGINE_REPLAY_PROPERTY, dir.path)
        assertThat(PackagedEngineProof.replayArming()).isNull()

        System.setProperty(SmokeMode.SYSTEM_PROPERTY, "true")
        val armed = PackagedEngineProof.replayArming()
        assertThat(armed).isNotNull()
        assertThat(armed!!.replayDir()).isEqualTo(dir.canonicalPath.toPath())

        System.clearProperty(SmokeMode.SYSTEM_PROPERTY)
        assertThat(PackagedEngineProof.replayArming()).isNull()
    }

    /** `expectedChunkRanges` chunks in `YouTubeTransferSource.CHUNK_BYTES` pieces with a closed tail. */
    @Test
    fun `expectedChunkRanges covers two full chunks plus the tail`() {
        assertThat(PackagedEngineProof.expectedChunkRanges(MEDIA_TOTAL))
            .containsExactly(
                "bytes=0-10485759",
                "bytes=10485760-20971519",
                "bytes=20971520-22020218",
            )
            .inOrder()
        assertThat(PackagedEngineProof.expectedChunkRanges(CHUNK)).containsExactly("bytes=0-10485759")
        assertThat(PackagedEngineProof.expectedChunkRanges(5)).containsExactly("bytes=0-4")
        assertThat(PackagedEngineProof.expectedChunkRanges(CHUNK + 1))
            .containsExactly("bytes=0-10485759", "bytes=10485760-10485760")
            .inOrder()
    }

    /** A synthetic ftyp-headed body: `FixtureMediaBytes`' contract without a test resource. */
    private fun syntheticMedia(total: Long): FixtureMediaBytes {
        val head = ByteArray(16)
        head[0] = 0
        head[1] = 0
        head[2] = 0
        head[3] = 16
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(head, 4)
        return FixtureMediaBytes(head, total)
    }

    private fun served(
        ordinal: Long,
        range: String?,
        status: Int = 206,
        bytesWritten: Long = 0,
        bodySha256: String? = "d".repeat(64),
    ) = LoopbackMediaFixture.ServedRequest(ordinal, "GET", "/audio", range, status, bytesWritten, bodySha256)

    /** The three good records whose body digests match the composition's own ranges. */
    private fun goodChunks(
        media: FixtureMediaBytes,
        firstOrdinal: Long,
    ): List<LoopbackMediaFixture.ServedRequest> {
        val out = mutableListOf<LoopbackMediaFixture.ServedRequest>()
        var offset = 0L
        var ordinal = firstOrdinal
        while (offset < media.size) {
            val length = minOf(CHUNK, media.size - offset)
            out +=
                served(
                    ordinal++,
                    "bytes=$offset-${offset + length - 1}",
                    bytesWritten = length,
                    bodySha256 = PackagedEngineProof.sha256Hex(media.readRange(offset, length.toInt())),
                )
            offset += length
        }
        return out
    }

    @Test
    fun `downloadChunks accepts the expected post-boundary chunk evidence`() {
        val media = syntheticMedia(MEDIA_TOTAL)
        val playback = listOf(served(0, "bytes=0-1023"), served(1, "bytes=0-"))
        val chunks = PackagedEngineProof.downloadChunks(playback + goodChunks(media, 2), media, 2)
        assertThat(chunks).isEqualTo(PackagedEngineProof.expectedChunkRanges(MEDIA_TOTAL))
    }

    @Test
    fun `downloadChunks rejects missing or extra ranges`() {
        val media = syntheticMedia(MEDIA_TOTAL)
        val chunks = goodChunks(media, 0)
        val missing = failure { PackagedEngineProof.downloadChunks(chunks.drop(1), media, 0) }
        assertThat(missing).contains("expected")
        val extra = failure { PackagedEngineProof.downloadChunks(chunks + chunks.last(), media, 0) }
        assertThat(extra).contains("expected")
    }

    @Test
    fun `downloadChunks rejects a non-206 answer and a short body`() {
        val media = syntheticMedia(MEDIA_TOTAL)
        val chunks = goodChunks(media, 0).toMutableList()
        val notPartial = failure {
            PackagedEngineProof.downloadChunks(chunks.map { it.copy(status = 200) }, media, 0)
        }
        assertThat(notPartial).contains("206")
        val short = failure {
            PackagedEngineProof.downloadChunks(
                chunks.map { it.copy(bytesWritten = it.bytesWritten - 1) },
                media,
                0,
            )
        }
        assertThat(short).contains("bytes")
        val wrongDigest = failure {
            PackagedEngineProof.downloadChunks(chunks.map { it.copy(bodySha256 = "0".repeat(64)) }, media, 0)
        }
        assertThat(wrongDigest).contains("digest")
    }

    @Test
    fun `downloadChunks rejects a wrong boundary`() {
        val media = syntheticMedia(MEDIA_TOTAL)
        val message =
            failure {
                PackagedEngineProof.downloadChunks(
                    goodChunks(media, 0) + goodChunks(media, 3).take(1),
                    media,
                    0,
                )
            }
        assertThat(message).contains("expected")
    }

    @Test
    fun `media spec reads only a well-formed contract`() {
        val dir = tempAppDirs("nd-spec").data.toFile().parentFile!!
        assertThat(PackagedEngineProof.MediaScenarioSpec.read(dir)).isNull()

        dir.resolve("media.json").writeText("not json")
        val malformed = failure { PackagedEngineProof.MediaScenarioSpec.read(dir) }
        assertThat(malformed).contains("media.json")

        dir.resolve("media.json").writeText("""{"total": 100, "headSha256": "${"a".repeat(64)}"}""")
        assertThat(failure { PackagedEngineProof.MediaScenarioSpec.read(dir) }).contains("head")

        dir.resolve("media.json").writeText(
            """{"total": 100, "head": "h.m4a", "headSha256": "not-hex"}""",
        )
        assertThat(failure { PackagedEngineProof.MediaScenarioSpec.read(dir) }).contains("headSha256")

        dir.resolve("media.json").writeText(
            """{"total": 100, "head": "../escape", "headSha256": "${"a".repeat(64)}"}""",
        )
        assertThat(failure { PackagedEngineProof.MediaScenarioSpec.read(dir) }).contains("head")

        dir.resolve("media.json").writeText(
            """{"total": 22020219, "head": "media-head.m4a", "headSha256": "${"a".repeat(64)}"}""",
        )
        val spec = PackagedEngineProof.MediaScenarioSpec.read(dir)
        assertThat(spec).isNotNull()
        assertThat(spec!!.total).isEqualTo(22020219L)
        assertThat(spec.head).isEqualTo("media-head.m4a")
    }

    @Test
    fun `media spec refuses a head whose digest differs`() {
        val dir = tempAppDirs("nd-spec-head").data.toFile().parentFile!!
        val head = ByteArray(16)
        head[3] = 16
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(head, 4)
        dir.resolve("head.m4a").writeBytes(head)
        val spec =
            PackagedEngineProof.MediaScenarioSpec(
                total = 1024L,
                head = "head.m4a",
                headSha256 = "0".repeat(64),
            )
        assertThat(failure { spec.openFixture(dir) }).contains("digest")

        val honest = spec.copy(headSha256 = PackagedEngineProof.sha256Hex(head))
        honest.openFixture(dir).use { fixture ->
            assertThat(fixture.media.size).isEqualTo(1024L)
            assertThat(fixture.mediaUrl).startsWith("http://127.0.0.1:")
            assertThat(fixture.mediaUrl).endsWith("/audio")
        }
    }

    /**
     * The packaged proof end-to-end on this host: a fake image whose `engine/` carries the
     * real bundled resources (`syncEngineResources` output), the pinned CA bundle and a
     * staged standalone interpreter, then the production locator, `BundledEngineStore`,
     * `YtxProcess`, `StdioYtxTransport`, `YtDlpEngine` and `YtDlpStreamResolver` — the same
     * classes the shipped graph injects — resolving the recorded video under `ReplayRH`.
     * Skipped wherever the checkout has not staged those build outputs; the shipped-image
     * proof itself is hosted packaging CI's job.
     */
    @Test
    fun `end-to-end resolves the recorded video through the staged packaged runtime`() {
        val repo = File(System.getProperty("user.dir")).canonicalFile.parentFile
        val devResources = repo?.resolve("youtube/ytdlp-desktop/build/engine-resources")
        Assume.assumeTrue("dev engine resources are not staged", devResources?.isDirectory == true)
        val staged = stagedRuntime(repo)
        Assume.assumeTrue("no staged standalone interpreter for this host", staged != null)
        val replay = repo.resolve("youtube/engine/src/test/resources/recorded/resolve")
        Assume.assumeTrue("the recorded resolve scenario is missing", replay.isDirectory)

        // The packaged layout (11 Resources): engine/{ytdlp,keys,shim,cacert.pem,python/}.
        val img = tempAppDirs("nd-image-e2e").data.toFile().parentFile!!
        val engine = img.resolve("engine").apply { mkdirs() }
        copyTree(devResources!!, engine)
        copyTree(staged!!, engine.resolve("python"))
        staged.parentFile.resolve("cacert.pem").copyTo(engine.resolve("cacert.pem"), overwrite = true)

        System.setProperty("compose.application.resources.dir", img.path)
        val copied = engine.resolve("python/bin/python3")
        check(copied.isFile) { "the staged interpreter copy is missing: $copied" }
        check(PythonRuntime.probe(copied) != null) { "the staged interpreter copy did not probe" }
        val dirs = tempAppDirs("nd-e2e-dirs").also { it.ensureCreated() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engineDir = { engine }
            val locator = DesktopPythonRuntimeLocator.standard(engineDir, { null })
            val paths = DesktopEngineStorePaths(dirs, engineDir, locator::locate)
            val identity = CompileMarker(1, "2026.08.19", "3.14")
            val store = BundledEngineStore(paths, identity.appVersionCode, DesktopClock)
            val settings = FakeEngineSettings()
            val context = { YtxContext("en", "US", "Neutrodyne synthetic test", "V4") }
            val transport =
                StdioYtxTransport(
                    YtxProcess(locator, paths),
                    store,
                    settings,
                    arming = StdioReplayArming { replay.canonicalPath.toPath() },
                    debugHooks = false,
                    clock = DesktopClock,
                    scope = scope,
                    ping = YtxControlRequest(context()),
                )
            val health = stubExtractionHealth()
            val engineCtl =
                YtDlpEngine(
                    transport,
                    store,
                    settings,
                    FakeEnginePlatformPreflight(),
                    identity,
                    health,
                    DesktopClock,
                    scope,
                )
            val streams =
                YtDlpStreamResolver(
                    YtDlpClient(transport, DesktopClock, scope),
                    engineCtl,
                    health,
                    ResolvedUrlCache(DesktopClock),
                    YtDlpErrorMapper(DesktopClock),
                    object : IpFamilyHints {
                        override fun current() = IpFamily.V4

                        override fun set(family: IpFamily?) {}
                    },
                    context,
                    DesktopClock,
                    scope,
                )

            val evidence =
                runBlocking {
                    PackagedEngineProof(
                        engineResources = EngineResourceDir(engineDir),
                        runtime = locator,
                        paths = paths,
                        store = store,
                        identity = identity,
                        transport = transport,
                        streams = streams,
                        capabilities = engineCtl,
                        context = context,
                        clock = DesktopClock,
                        database = { error("the resolve-only proof must not reach the media phase") },
                        playbackController = { error("the resolve-only proof must not reach the media phase") },
                        playbackStateSource = { error("the resolve-only proof must not reach the media phase") },
                        downloadController = { error("the resolve-only proof must not reach the media phase") },
                        localMediaIndex = { error("the resolve-only proof must not reach the media phase") },
                    ).run(replay)
                }

            assertThat(evidence["resolve"]!!.jsonPrimitive.content).isEqualTo("ok")
            assertThat(evidence["videoId"]!!.jsonPrimitive.content).isEqualTo("aaaaaaaaaaA")
            assertThat(evidence["formatId"]!!.jsonPrimitive.content).isEqualTo("140")
            assertThat(evidence["urlHost"]!!.jsonPrimitive.content).isEqualTo("media.example.invalid")
            assertThat(evidence["host"]!!.jsonPrimitive.content).isEqualTo("stdio")
            assertThat(evidence["child"]!!.jsonPrimitive.content).isEqualTo("exited")
            assertThat(evidence["cacert"]!!.jsonPrimitive.boolean).isTrue()
            assertThat(evidence["interpreter"]!!.jsonPrimitive.content)
                .startsWith(engine.resolve("python").canonicalPath)
            assertThat(evidence["resources"]!!.jsonPrimitive.content).isEqualTo(engine.canonicalPath)
            assertThat(evidence["ytDlp"]!!.jsonPrimitive.content).isEqualTo("2026.08.19")
            assertThat(evidence["expectedYtdlp"]!!.jsonPrimitive.content).isEqualTo("2026.08.19")
        } finally {
            scope.cancel()
            img.deleteRecursively()
        }
    }

    /**
     * A staged `trimmed/python` tree — this checkout's own fetch output first, then the
     * shared standalone the task notes point at. Only its presence decides whether the
     * end-to-end case runs; nothing outside the owned temporary image is written.
     */
    private fun stagedRuntime(repo: File): File? {
        val osName = System.getProperty("os.name").lowercase()
        val os =
            when {
                osName.startsWith("windows") -> "windows"
                osName.contains("mac") || osName.contains("darwin") -> "macos"
                else -> "linux"
            }
        val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
        val candidates =
            listOfNotNull(
                repo.resolve("youtube/ytdlp-desktop/build/python-standalone/$os-$arch/trimmed/python"),
                System.getenv("NEUTRODYNE_TEST_STAGED_PYTHON")?.let(::File),
            )
        val interpreter = if (os == "windows") "python.exe" else "bin/python3"
        return candidates.firstOrNull { it.resolve(interpreter).isFile && it.isDirectory }
    }

    /**
     * A faithful staged-tree copy: `File.copyRecursively` drops the execute bit (its
     * `Files.copy` carries no attributes), which leaves the staged interpreter
     * un-runnable. File symlinks are resolved to their real targets before copying.
     */
    private fun copyTree(
        source: File,
        target: File,
    ) {
        val root = source.toPath()
        java.nio.file.Files.walk(root).use { stream ->
            stream.forEach { entry ->
                val dest = target.toPath().resolve(root.relativize(entry).toString())
                if (java.nio.file.Files
                        .isDirectory(entry)
                ) {
                    java.nio.file.Files
                        .createDirectories(dest)
                } else {
                    java.nio.file.Files.copy(
                        entry.toRealPath(),
                        dest,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
                    )
                }
            }
        }
    }

    /** An admitting no-op health port: the proof needs capability and reservation flow, not accounting. */
    private fun stubExtractionHealth(): YouTubeExtractionHealth =
        object : YouTubeExtractionHealth {
            private var nextId = 0L
            override val state = MutableStateFlow(YouTubeHealthState())
            override val outcomes = MutableSharedFlow<ExtractionOutcome>()

            override suspend fun awaitLoaded() = Unit

            override fun extractionGate(nowMs: Long): ExtractionGate = ExtractionGate.Allow

            override fun reportExtraction(outcome: ExtractionOutcome) = Unit

            override fun reportRateLimited(nowMs: Long) = Unit

            override fun reportFeedOutage(nowMs: Long) = Unit

            override fun reportFeedRecovered() = Unit

            override fun reportFeedRateLimited(
                nowMs: Long,
                retryAfterMs: Long?,
            ) = Unit

            override fun reportEngineVersion(version: String?) = Unit

            override fun retryNow() = Unit

            override fun reserveExtraction(nowMs: Long): ExtractionAdmission =
                ExtractionAdmission.Admitted(ExtractionReservation(++nextId))

            override fun completeExtraction(
                reservation: ExtractionReservation,
                outcome: ExtractionOutcome,
            ) = Unit
        }

    private companion object {
        /** The recorded media scenario's composed body size (media.json's `total`). */
        const val MEDIA_TOTAL = 22_020_219L

        /** `YouTubeTransferSource.CHUNK_BYTES`, restated for expected-range checks. */
        const val CHUNK = 10L * 1024 * 1024

        val DEV_PROPERTIES = listOf("neutrodyne.ytx.python", "neutrodyne.engineResources", "neutrodyne.rootDir")
        val PROOF_PROPERTIES =
            DEV_PROPERTIES +
                listOf("compose.application.resources.dir", SmokeMode.SYSTEM_PROPERTY, SmokeMode.ENGINE_REPLAY_PROPERTY)
    }
}
