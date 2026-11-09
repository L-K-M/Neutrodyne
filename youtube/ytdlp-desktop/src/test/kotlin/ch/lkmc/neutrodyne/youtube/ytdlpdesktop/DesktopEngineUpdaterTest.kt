// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.testing.EngineUpdateFixtures
import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineUpdateScheduler
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeHealth
import ch.lkmc.neutrodyne.youtube.api.EngineRejectReason
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineUpdatePolicy
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EngineCompileMarkers
import ch.lkmc.neutrodyne.youtube.engine.EngineHttp
import ch.lkmc.neutrodyne.youtube.engine.EngineHttpException
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineRollbackMonitor
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateTrigger
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdater
import ch.lkmc.neutrodyne.youtube.engine.JdkEd25519Verifier
import ch.lkmc.neutrodyne.youtube.engine.PreparedEngine
import ch.lkmc.neutrodyne.youtube.engine.YtDlpClient
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import okio.buffer
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * MD3 AC4 — the M9 AC10 cases against the real desktop store and host: signed fixture
 * documents over loopback HTTP, the real [BundledEngineStore] on disk, [DesktopEngineCompiler]
 * driving the packaged interpreter, and the real [StdioYtxTransport]/[YtxProcess] child for
 * the staged trial's actual selftest. Rejections, the idle gate, cancellation, restart
 * resume, rollback monitoring and reset-to-bundled run the shared `:youtube:engine`
 * `EngineUpdater` unchanged — desktop supplies only the ports.
 */
class DesktopEngineUpdaterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = EngineUpdateFixtures()
    private lateinit var server: MockWebServer
    private lateinit var fixture: StdioFixture
    private lateinit var store: BundledEngineStore
    private lateinit var transport: StdioYtxTransport
    private lateinit var monitor: EngineRollbackMonitor
    private lateinit var client: YtDlpClient
    private lateinit var health: FakeYouTubeHealth
    private lateinit var scheduler: FakeEngineUpdateScheduler
    private var candidateArchive: ByteArray = byteArrayOf()
    private val requested = CopyOnWriteArrayList<String>()
    private val served = ConcurrentHashMap<String, ByteArray>()
    private var manifestBytes: ByteArray = byteArrayOf()
    private var manifestSig: String = ""
    private var heartbeatBytes: ByteArray? = null
    private var heartbeatSig: ByteArray? = null
    private var sumsBytes: ByteArray = byteArrayOf()
    private var sumsSig: ByteArray = byteArrayOf()
    private var archiveBytes: ByteArray = byteArrayOf()

    private companion object {
        const val NEW = EngineUpdateFixtures.NEW_VERSION
        const val OTHER = EngineUpdateFixtures.OTHER_VERSION
        const val SNAPSHOT_TAG = "engine-approvals-4242-3"
        const val SNAPSHOT_TAG_NEXT = "engine-approvals-7777-2"
        const val INDEX_PATH = "/repos/L-K-M/Neutrodyne/releases/tags/engine-approvals"
        val DEFAULT_HEARTBEAT: ByteArray =
            """{"kind":"heartbeat","lastRunAt":"2026-11-08T00:00:00Z"}""".toByteArray()
    }

    @BeforeTest fun serve() {
        server = MockWebServer()
        server.start()
        fixture = StdioFixture(temporary.root)
        candidateArchive = repinnedBundled(NEW)
        store = BundledEngineStore(fixture.paths, 1, RealClock)
        fixture.settings.state.value =
            fixture.settings.state.value.copy(enabled = true, updatePolicy = EngineUpdatePolicy.APPROVED)
        health = FakeYouTubeHealth()
        scheduler = FakeEngineUpdateScheduler()
        transport =
            StdioYtxTransport(
                fixture.spawner,
                store,
                fixture.settings,
                arming = null,
                debugHooks = true,
                clock = RealClock,
                scope = fixture.scope,
                ping = fixture.ping,
            )
        client = YtDlpClient(transport, RealClock, fixture.scope)
        monitor = EngineRollbackMonitor(store, transport, health, RealClock, fixture.scope)
        prepareServer()
    }

    @AfterTest fun shutdown() {
        transport.shutdown()
        fixture.scope.cancel()
        server.close()
    }

    private fun updater(
        store: BundledEngineStore = this.store,
        monitor: EngineRollbackMonitor = this.monitor,
    ) = EngineUpdater(
        store = store,
        settings = fixture.settings,
        http = LoopbackEngineHttp(),
        keys = fixtures.keys,
        verifier = JdkEd25519Verifier,
        scheduler = scheduler,
        transport = transport,
        client = client,
        compiler = DesktopEngineCompiler({ fixture.python }, fixture.paths, 1, RealClock),
        preflight = FakeEnginePlatformPreflight(),
        health = health,
        monitor = monitor,
        clock = RealClock,
        indexUrl = server.url(INDEX_PATH).toString(),
        control = { fixture.ping },
        releaseBaseUrl = server.url("/releases").toString(),
        approvalBaseUrl = server.url("/releases").toString(),
    )

    // --- The real bundled tree, compiled once per JVM, overlaid per test. ---

    /**
     * The build-shipped tree in compiled form without a child compile per test:
     * `SharedEngine.lib` is the same zip's real compile output; copying it over
     * `prepareBundled`'s extracted tree and writing the marker gives the exact
     * post-compile state (read-only, marker-pinned, no .py left).
     */
    private suspend fun prepareCompiledBundled(store: BundledEngineStore = this.store) {
        store.loadStatus()
        val prepared = store.prepareBundled()
        val lib = prepared.libDir.toFile()
        SharedEngine.lib.walkTopDown().forEach { src ->
            if (src == SharedEngine.lib) return@forEach
            val relative = src.relativeTo(SharedEngine.lib).path
            if (src.name.startsWith(".compiled-")) return@forEach
            if (src.isDirectory) {
                lib.resolve(relative).mkdirs()
                return@forEach
            }
            val target = lib.resolve(relative)
            target.parentFile?.mkdirs()
            // Extracted bundled files are read-only; replace them rather than append.
            if (target.exists()) check(target.delete()) { "Cannot replace $target" }
            src.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        }
        // The extracted tree is read-only down to the directories; loosen before stripping.
        lib.walkTopDown().filter { it.isDirectory }.forEach { it.setWritable(true) }
        lib.walkTopDown().filter { it.extension == "py" }.forEach {
            it.setWritable(true)
            it.delete()
        }
        EngineCompileMarkers.write(
            PreparedEngine(prepared.libDir, CompileMarker(1, BUNDLED_VERSION, fixture.paths.pythonMinor)),
        )
        store.loadStatus()
    }

    /**
     * The real bundled zipapp with only `yt_dlp/version.py`'s pin moved to [version].
     * [java.util.zip.ZipFile] reads through the `#!` prefix a `ZipInputStream` would choke
     * on; the rewritten output carries no prefix, which `BundledZip` treats as prefix=0.
     */
    private fun repinnedBundled(version: String): ByteArray {
        val original = File.createTempFile("yt-dlp-orig", ".zip", temporary.root)
        fixture.paths.openBundledZip().use { source ->
            FileOutputStream(original).use { it.write(source.buffer().readByteArray()) }
        }
        val out = ByteArrayOutputStream()
        java.util.zip.ZipFile(original).use { input ->
            ZipOutputStream(out).use { zip ->
                for (entry in input.entries()) {
                    var bytes = input.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "yt_dlp/version.py") {
                        val text = String(bytes)
                        val replaced =
                            text.replace(
                                Regex("__version__ = ['\"][^'\"]+['\"]"),
                                "__version__ = '$version'",
                            )
                        require(replaced != text) { "version pin not found in yt_dlp/version.py" }
                        bytes = replaced.toByteArray()
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    // --- The fixture server (same documents as the shared harness). ---

    private fun indexDocument(
        snapshotTag: String = SNAPSHOT_TAG,
        releaseTag: String = "engine-approvals",
        draft: Boolean = false,
        prerelease: Boolean = true,
        url: String = server.url("/repos/L-K-M/Neutrodyne/releases/99").toString(),
        body: String = """{"schema":1,"snapshotTag":"$snapshotTag"}""",
    ): ByteArray {
        val escaped = body.replace("\\", "\\\\").replace("\"", "\\\"")
        return (
            """{"url":"$url","tag_name":"$releaseTag","draft":$draft,""" +
                """"prerelease":$prerelease,"body":"$escaped"}"""
        ).toByteArray()
    }

    private fun serveSnapshot(
        tag: String,
        manifest: ByteArray = manifestBytes,
        signature: ByteArray = manifestSig.toByteArray(),
        heartbeat: ByteArray? = heartbeatBytes,
        heartbeatSignature: ByteArray? = heartbeatSig,
    ) {
        served["/releases/download/$tag/ytdlp-approved.json"] = manifest
        served["/releases/download/$tag/ytdlp-approved.json.sig"] = signature
        if (heartbeat != null) served["/releases/download/$tag/ytdlp-heartbeat.json"] = heartbeat
        if (heartbeatSignature != null) {
            served["/releases/download/$tag/ytdlp-heartbeat.json.sig"] = heartbeatSignature
        }
    }

    private fun serveUpstream(
        version: String,
        sums: ByteArray = sumsBytes,
        sumsSignature: ByteArray = sumsSig,
        archive: ByteArray = archiveBytes,
    ) {
        served["/releases/download/$version/SHA2-256SUMS"] = sums
        served["/releases/download/$version/SHA2-256SUMS.sig"] = sumsSignature
        served["/releases/download/$version/yt-dlp"] = archive
    }

    private fun prepareServer(
        index: ByteArray? = indexDocument(),
        manifest: ByteArray? = null,
        signature: String? = null,
        heartbeat: ByteArray? = DEFAULT_HEARTBEAT,
        heartbeatSignature: ByteArray? = null,
        sums: ByteArray? = null,
        sumsSignature: ByteArray? = null,
        archive: ByteArray? = null,
    ) {
        manifestBytes = manifest ?: fixtures.manifest(NEW, sha256 = fixtures.sha256(candidateArchive))
        manifestSig = signature ?: fixtures.signManifest(manifestBytes)
        heartbeatBytes = heartbeat
        heartbeatSig = heartbeatSignature ?: heartbeat?.let { fixtures.signManifest(it).toByteArray() }
        sumsBytes = sums ?: fixtures.sums(candidateArchive)
        sumsSig = sumsSignature ?: fixtures.sumsSignature(sumsBytes)
        archiveBytes = archive ?: candidateArchive
        served.clear()
        index?.let { served[INDEX_PATH] = it }
        serveSnapshot(SNAPSHOT_TAG)
        serveUpstream(NEW)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requested += path
                    val bytes = served[path] ?: return MockResponse.Builder().code(404).build()
                    return MockResponse.Builder().code(200).body(Buffer().write(bytes)).build()
                }
            }
    }

    private suspend fun awaitReal(
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "condition not met")
            withContext(Dispatchers.IO) { delay(20) }
        }
    }

    // --- Trust rejections: the shared updater decides, the desktop store records. ---

    @Test fun manifestSignatureFailureRejectsBeforeAnyDownload() =
        runBlocking {
            prepareCompiledBundled()
            prepareServer(signature = fixtures.signManifest("forged".toByteArray()))
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Rejected(null, EngineRejectReason.MANIFEST_SIGNATURE), outcome)
            assertEquals(BUNDLED_VERSION, store.active.value.current)
            assertTrue(requested.none { it.endsWith("/yt-dlp") })
        }

    @Test fun malformedIndexFailsClosed() =
        runBlocking {
            prepareCompiledBundled()
            prepareServer(index = "garbage".toByteArray())
            assertEquals(
                EngineUpdateOutcome.Rejected(null, EngineRejectReason.ORIGIN),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            assertEquals(BUNDLED_VERSION, store.active.value.current)
        }

    @Test fun foreignIndexIdentityFailsClosed() =
        runBlocking {
            prepareCompiledBundled()
            prepareServer(index = indexDocument(url = server.url("/repos/evil/mirror/releases/1").toString()))
            assertEquals(
                EngineUpdateOutcome.Rejected(null, EngineRejectReason.ORIGIN),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
        }

    @Test fun tamperedArchiveFailsTheSumsCheck() =
        runBlocking {
            prepareCompiledBundled()
            val tampered = candidateArchive.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
            prepareServer(archive = tampered)
            assertEquals(
                EngineUpdateOutcome.Rejected(NEW, EngineRejectReason.HASH_MISMATCH),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            assertNull(store.active.value.staged)
            assertEquals(BUNDLED_VERSION, store.active.value.current)
        }

    @Test fun archiveNamingAnotherVersionIsNotTheApprovedOne() =
        runBlocking {
            prepareCompiledBundled()
            val foreign = repinnedBundled(OTHER)
            prepareServer(
                archive = foreign,
                sums = fixtures.sums(foreign),
                manifest = fixtures.manifest(NEW, sha256 = fixtures.sha256(foreign)),
            )
            assertEquals(
                EngineUpdateOutcome.Rejected(NEW, EngineRejectReason.ORIGIN),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            assertEquals(BUNDLED_VERSION, store.active.value.current)
        }

    @Test fun upstreamSignatureFailureRejectsBeforeStaging() =
        runBlocking {
            prepareCompiledBundled()
            prepareServer(sumsSignature = fixtures.signManifest("forged".toByteArray()).toByteArray())
            assertEquals(
                EngineUpdateOutcome.Rejected(NEW, EngineRejectReason.UPSTREAM_SIGNATURE),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            assertNull(store.active.value.staged)
        }

    @Test fun belowBundledManifestIsAntiRollbackRejected() =
        runBlocking {
            prepareCompiledBundled()
            prepareServer(
                manifest =
                    fixtures.manifest("2026.01.01", sha256 = fixtures.sha256(candidateArchive)),
            )
            assertEquals(
                EngineUpdateOutcome.Rejected("2026.01.01", EngineRejectReason.BELOW_BUNDLED),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            assertEquals(BUNDLED_VERSION, store.active.value.current)
        }

    @Test fun shimOutsideTheManifestRangeIsRejected() =
        runBlocking {
            prepareCompiledBundled()
            prepareServer(
                manifest =
                    fixtures.manifest(NEW, sha256 = fixtures.sha256(candidateArchive), shimMin = 2, shimMax = 2),
            )
            assertEquals(
                EngineUpdateOutcome.Rejected(NEW, EngineRejectReason.SHIM_INCOMPATIBLE),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
        }

    @Test fun replayedManifestSequenceIsRejected() =
        runBlocking {
            prepareCompiledBundled()
            assertEquals(
                EngineUpdateOutcome.Activated(NEW),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            val older =
                fixtures.manifest(OTHER, sequence = 6, sha256 = fixtures.sha256(candidateArchive))
            prepareServer(manifest = older)
            assertEquals(
                EngineUpdateOutcome.Rejected(OTHER, EngineRejectReason.MANIFEST_REPLAYED),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            assertEquals(NEW, store.active.value.current)
        }

    // --- Real host boundary: compile, trial child, selftest, idle, cancellation, resume. ---

    @Test fun stagedSelftestFailureAbortsAndKeepsBundledCurrent() =
        runBlocking {
            prepareCompiledBundled()
            // The minimal fixture archive stages and compiles, but the real child's selftest
            // probe fails on an empty yt_dlp package — the shared runner aborts the trial.
            val minimal = fixtures.archive(NEW)
            prepareServer(
                archive = minimal,
                sums = fixtures.sums(minimal),
                manifest = fixtures.manifest(NEW, sha256 = fixtures.sha256(minimal)),
            )
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Rejected(NEW, EngineRejectReason.SELFTEST_FAILED), outcome)
            val active = store.active.value
            assertEquals(BUNDLED_VERSION, active.current)
            assertNull(active.trial)
            assertNull(active.staged)
            assertFalse(File(fixture.paths.root.toFile(), "versions/$NEW").exists())
            assertEquals(EngineRejectReason.SELFTEST_FAILED, active.rejected.single().reason)
        }

    @Test fun signedCandidateActivatesInAFreshTrialChild() =
        runBlocking {
            prepareCompiledBundled()
            // The bundled host serving before the update is a distinct process generation.
            transport.call(YtxMethod.PING, fixture.ping.toJson(), fixture.deadline(60_000))
            val servingPid = transport.status.value.pid!!
            assertEquals(BUNDLED_VERSION, transport.status.value.engineVersion)
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Activated(NEW), outcome)
            val active = store.active.value
            assertEquals(NEW, active.current)
            assertEquals(BUNDLED_VERSION, active.previous)
            assertEquals(NEW, store.hostPrepared()!!.marker.engineVersion)
            assertEquals(EnginePreparation.COMPILED, store.preparation.value)
            // The serving child now is the fresh trial host: a different pid, reporting the
            // candidate's version both in its hello and in the served version call.
            val trialStatus = transport.status.value
            assertNotEquals(servingPid, trialStatus.pid)
            assertEquals(NEW, trialStatus.engineVersion)
            val version =
                transport.call(YtxMethod.VERSION, fixture.ping.toJson(), fixture.deadline(30_000))
            assertEquals(NEW, kotlinx.serialization.json.Json.parseToJsonElement(version).jsonObject["ytDlp"]!!.jsonPrimitive.content)
        }

    @Test fun activationWaitsForTheHostToGoIdle() =
        runBlocking {
            prepareCompiledBundled()
            transport.call(YtxMethod.PING, fixture.ping.toJson(), fixture.deadline(60_000))
            val hang =
                async {
                    transport.callForTest("_hang", JsonObject(emptyMap()), fixture.deadline(300_000))
                }
            awaitReal { transport.status.value.inFlight.isNotEmpty() }
            val run = async { updater().runUpdate(EngineUpdateTrigger.SCHEDULED) }
            // Staged and compiled, but the busy host holds activation back.
            awaitReal { store.active.value.staged == NEW }
            delay(500)
            assertEquals(BUNDLED_VERSION, store.active.value.current)
            assertTrue(run.isActive)
            transport.cancel(transport.status.value.inFlight.first())
            hang.await()
            assertEquals(EngineUpdateOutcome.Activated(NEW), run.await())
            assertEquals(NEW, store.active.value.current)
        }

    @Test fun cancelledInsideTrialClearsTrialWithoutRejection() =
        runBlocking {
            prepareCompiledBundled()
            val run = async { updater().runUpdate(EngineUpdateTrigger.SCHEDULED) }
            awaitReal { store.active.value.trial != null }
            run.cancel()
            try {
                run.await()
                fail("runUpdate must propagate cancellation")
            } catch (cancelled: CancellationException) {
                // Honest cancellation: never a rejection outcome.
            }
            val active = store.active.value
            assertNull(active.trial)
            assertEquals(BUNDLED_VERSION, active.current)
            assertEquals(NEW, active.staged)
            assertTrue(active.rejected.none { it.version == NEW })
            assertEquals(BUNDLED_VERSION, store.hostPrepared()!!.marker.engineVersion)
        }

    @Test fun stagedCandidateResumesAfterRestartWithoutRedownload() =
        runBlocking {
            prepareCompiledBundled()
            val first = async { updater().runUpdate(EngineUpdateTrigger.SCHEDULED) }
            awaitReal { store.active.value.staged == NEW }
            first.cancel()
            try {
                first.await()
            } catch (cancelled: CancellationException) {
                // Expected: the run died between staging and commit.
            }
            val downloads = requested.count { it.endsWith("/yt-dlp") }
            // A restarted store rediscovers the staged tree on disk; the resume does not
            // fetch the archive again.
            val restarted = BundledEngineStore(fixture.paths, 1, RealClock)
            restarted.loadStatus()
            assertEquals(NEW, restarted.active.value.staged)
            val restartedMonitor =
                EngineRollbackMonitor(restarted, transport, health, RealClock, fixture.scope)
            val outcome = updater(restarted, restartedMonitor).runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Activated(NEW), outcome)
            assertEquals(NEW, restarted.active.value.current)
            assertEquals(downloads, requested.count { it.endsWith("/yt-dlp") })
        }

    @Test fun crashLoopAfterActivationRollsBackToPrevious() =
        runBlocking {
            prepareCompiledBundled()
            health.reportExtraction(ExtractionOutcome.Success)
            assertEquals(
                EngineUpdateOutcome.Activated(NEW),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            health.reportExtraction(ExtractionOutcome.ParseFailure("a"))
            health.reportExtraction(ExtractionOutcome.ParseFailure("b"))
            health.reportExtraction(ExtractionOutcome.ParseFailure("c"))
            awaitReal { store.active.value.current == BUNDLED_VERSION }
            assertNull(store.active.value.previous)
            assertTrue(
                store.active.value.rejected
                    .any { it.version == NEW && it.reason == EngineRejectReason.ROLLED_BACK },
            )
        }

    @Test fun updatedVersionStartFailureRollsBack() =
        runBlocking {
            prepareCompiledBundled()
            health.reportExtraction(ExtractionOutcome.Success)
            assertEquals(
                EngineUpdateOutcome.Activated(NEW),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            transport.shutdown()
            fixture.spawner.failStart = YtxSpawnException(null)
            try {
                transport.call(YtxMethod.VERSION, fixture.ping.toJson(), fixture.deadline(30_000))
            } catch (_: Exception) {
                // The call itself fails; the monitor's transition is the behavior under test.
            }
            awaitReal { store.active.value.current == BUNDLED_VERSION }
            assertTrue(
                store.active.value.rejected
                    .any { it.version == NEW && it.reason == EngineRejectReason.ROLLED_BACK },
            )
        }

    @Test fun resetToBundledReturnsTheBuildVersion() =
        runBlocking {
            prepareCompiledBundled()
            assertEquals(
                EngineUpdateOutcome.Activated(NEW),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            updater().resetToBundled()
            val active = store.active.value
            assertEquals(BUNDLED_VERSION, active.current)
            assertEquals(BUNDLED_VERSION, store.hostPrepared()!!.marker.engineVersion)
            // The host serves the bundled build again: fresh child, bundled version hello.
            transport.shutdown()
            transport.call(YtxMethod.PING, fixture.ping.toJson(), fixture.deadline(60_000))
            assertEquals(BUNDLED_VERSION, transport.status.value.engineVersion)
        }
}

/**
 * Loopback `EngineHttp` on `HttpURLConnection`: real HTTP against MockWebServer while
 * `checkBannedApis` keeps `OkHttpClient` construction inside :core:network:okhttp.
 * Same semantics as `OkHttpEngineHttp` (no redirects, status and size-cap failures).
 */
private class LoopbackEngineHttp : EngineHttp {
    override suspend fun get(
        url: String,
        maxBytes: Long,
    ): ByteArray = fetch(url, maxBytes)

    override suspend fun download(
        url: String,
        maxBytes: Long,
    ): ByteArray = fetch(url, maxBytes)

    override suspend fun redirectLocation(url: String): String? {
        val connection = open(url)
        try {
            val code = connection.responseCode
            return if (code in 300..399) connection.getHeaderField("Location") else null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetch(
        url: String,
        maxBytes: Long,
    ): ByteArray {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw EngineHttpException(code)
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > maxBytes) throw EngineHttpException(-1)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return connection
    }
}
