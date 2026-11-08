// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStorePaths
import ch.lkmc.neutrodyne.core.testing.FakeEngineUpdateScheduler
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeHealth
import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.EngineRejectReason
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineUpdatePolicy
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 04 acceptance set, all through the loopback fixture server: signature failure, tampered
 * archive, unapproved version, staged self-test rollback, successful switch, crash-loop rollback,
 * and interrupted-staging resume. No live network and no emulator anywhere.
 */
class EngineUpdaterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = EngineUpdateFixtures()
    private val clock = TestClock()
    private lateinit var server: MockWebServer
    private lateinit var paths: FakeEngineStorePaths
    private lateinit var store: BundledEngineStore
    private lateinit var transport: FakeYtxTransport
    private lateinit var settings: FakeEngineSettings
    private lateinit var health: FakeYouTubeHealth
    private lateinit var scheduler: FakeEngineUpdateScheduler
    private lateinit var monitor: EngineRollbackMonitor
    private lateinit var candidateArchive: ByteArray
    private var selftestOk = true
    private val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val served = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private var manifestBytes: ByteArray = byteArrayOf()
    private var manifestSig: String = ""
    private var heartbeatBytes: ByteArray? = null
    private var heartbeatSig: ByteArray? = null
    private var sumsBytes: ByteArray = byteArrayOf()
    private var sumsSig: ByteArray = byteArrayOf()
    private var archiveBytes: ByteArray = byteArrayOf()

    private companion object {
        const val INDEX_TAG = "engine-approvals"
        const val SNAPSHOT_TAG = "engine-approvals-4242-3"
        const val SNAPSHOT_TAG_NEXT = "engine-approvals-7777-2"
        const val INDEX_PATH = "/repos/L-K-M/Neutrodyne/releases/tags/engine-approvals"
        val DEFAULT_HEARTBEAT: ByteArray =
            """{"kind":"heartbeat","lastRunAt":"2026-11-08T00:00:00Z"}""".toByteArray()
    }

    @BeforeTest fun serve() {
        server = MockWebServer()
        server.start()
        candidateArchive = fixtures.archive(EngineUpdateFixtures.NEW_VERSION)
        prepareServer()

        val bundled = fixtures.archive(EngineUpdateFixtures.BUNDLED_VERSION)
        val root = temporary.root.canonicalFile
        paths =
            FakeEngineStorePaths().apply {
                this.root = root.resolve("ytdlp").path.toPath()
                ytDlpCacheDir = root.resolve("cache/yt-dlp").path.toPath()
                zip = bundled
                metadata =
                    """{"version":"${EngineUpdateFixtures.BUNDLED_VERSION}",""" +
                    """"tag":"${EngineUpdateFixtures.BUNDLED_VERSION}",""" +
                    """"sha256":"${fixtures.sha256(bundled)}","ejsVersion":"0.8.0"}"""
            }
        transport = FakeYtxTransport()
        settings =
            FakeEngineSettings().apply {
                state.value =
                    state.value.copy(enabled = true, updatePolicy = EngineUpdatePolicy.APPROVED)
            }
        health = FakeYouTubeHealth()
        scheduler = FakeEngineUpdateScheduler()
        store = BundledEngineStore(paths, 1, clock)
        monitor = EngineRollbackMonitor(store, transport, health, clock, unconfined())
        transport.answer = { call ->
            when (call.method) {
                YtxMethod.SELFTEST -> """{"ok":$selftestOk,"missing":[]}"""
                else -> throw YtxCallException("ENGINE_UNAVAILABLE")
            }
        }
    }

    private fun unconfined() = CoroutineScope(Dispatchers.Unconfined)

    private fun indexDocument(
        snapshotTag: String = SNAPSHOT_TAG,
        releaseTag: String = INDEX_TAG,
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

    /** Registers one immutable snapshot's four documents plus the upstream release files. */
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
        if (heartbeatSignature != null) served["/releases/download/$tag/ytdlp-heartbeat.json.sig"] = heartbeatSignature
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
        manifestBytes =
            manifest
                ?: fixtures.manifest(
                    EngineUpdateFixtures.NEW_VERSION,
                    sha256 = fixtures.sha256(candidateArchive),
                )
        manifestSig = signature ?: fixtures.signManifest(manifestBytes)
        heartbeatBytes = heartbeat
        heartbeatSig = heartbeatSignature ?: heartbeat?.let { fixtures.signManifest(it).toByteArray() }
        sumsBytes = sums ?: fixtures.sums(candidateArchive)
        sumsSig = sumsSignature ?: fixtures.sumsSignature(sumsBytes)
        archiveBytes = archive ?: candidateArchive
        served.clear()
        index?.let { served[INDEX_PATH] = it }
        serveSnapshot(SNAPSHOT_TAG)
        serveUpstream(EngineUpdateFixtures.NEW_VERSION)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requested += path
                    val bytes = served[path] ?: return MockResponse.Builder().code(404).build()
                    return MockResponse
                        .Builder()
                        .code(200)
                        .body(Buffer().write(bytes))
                        .build()
                }
            }
    }

    @AfterTest fun shutdown() = server.close()

    private fun updater(store: EngineStore = this.store) =
        EngineUpdater(
            store = store,
            settings = settings,
            http = LoopbackEngineHttp(),
            keys = fixtures.keys,
            verifier = JdkEd25519Verifier,
            scheduler = scheduler,
            transport = transport,
            client = YtDlpClient(transport, clock, unconfined()),
            compiler = NoOpEngineCompiler,
            preflight = FakeEnginePlatformPreflight(),
            health = health,
            monitor = monitor,
            clock = clock,
            indexUrl = server.url(INDEX_PATH).toString(),
            control = { YtxControlRequest(YtxContext("en", "US", "fixture/1")) },
            releaseBaseUrl = server.url("/releases").toString(),
            approvalBaseUrl = server.url("/releases").toString(),
        )

    private suspend fun prepare() {
        store.loadStatus()
        store.prepareBundled()
    }

    @Test fun manifestSignatureFailureRejectsBeforeAnyDownload() =
        runTest {
            prepare()
            prepareServer(signature = fixtures.signManifest("forged".toByteArray()))
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(
                EngineUpdateOutcome.Rejected(null, EngineRejectReason.MANIFEST_SIGNATURE),
                outcome,
            )
            assertEquals(EngineUpdateFixtures.BUNDLED_VERSION, store.active.value.current)
            assertTrue(requested.none { it.endsWith("/yt-dlp") })
        }

    // --- Immutable approval index (steps 2–4): one bounded index fetch names the snapshot tag. ---

    private fun assertIndexRejected(outcome: EngineUpdateOutcome) {
        assertEquals(EngineUpdateOutcome.Rejected(null, EngineRejectReason.ORIGIN), outcome)
        assertEquals(EngineUpdateFixtures.BUNDLED_VERSION, store.active.value.current)
        assertTrue(requested.none { it.contains("/releases/download/") })
    }

    @Test fun indexFetchFailureLeavesTheActiveEngineUntouched() =
        runTest {
            prepare()
            prepareServer(index = null)
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Failed(TransientKind.NETWORK), outcome)
            assertEquals(EngineUpdateFixtures.BUNDLED_VERSION, store.active.value.current)
            assertTrue(requested.all { it == INDEX_PATH })
        }

    @Test fun malformedIndexFailsClosedBeforeAnyDocumentFetch() =
        runTest {
            prepare()
            prepareServer(index = "garbage".toByteArray())
            assertIndexRejected(updater().runUpdate(EngineUpdateTrigger.SCHEDULED))
        }

    @Test fun draftIndexIsNotAnApproval() =
        runTest {
            prepare()
            prepareServer(index = indexDocument(draft = true))
            assertIndexRejected(updater().runUpdate(EngineUpdateTrigger.SCHEDULED))
        }

    @Test fun nonPrereleaseIndexIsNotAnApproval() =
        runTest {
            prepare()
            prepareServer(index = indexDocument(prerelease = false))
            assertIndexRejected(updater().runUpdate(EngineUpdateTrigger.SCHEDULED))
        }

    @Test fun foreignIndexTagFailsClosed() =
        runTest {
            prepare()
            prepareServer(index = indexDocument(releaseTag = "nightly"))
            assertIndexRejected(updater().runUpdate(EngineUpdateTrigger.SCHEDULED))
        }

    @Test fun foreignIndexIdentityFailsClosed() =
        runTest {
            prepare()
            prepareServer(index = indexDocument(url = server.url("/repos/evil/mirror/releases/1").toString()))
            assertIndexRejected(updater().runUpdate(EngineUpdateTrigger.SCHEDULED))
        }

    @Test fun oversizedIndexBodyFailsClosed() =
        runTest {
            prepare()
            val body = """{"schema":1,"snapshotTag":"$SNAPSHOT_TAG","pad":"${"x".repeat(8192)}"}"""
            prepareServer(index = indexDocument(body = body))
            assertIndexRejected(updater().runUpdate(EngineUpdateTrigger.SCHEDULED))
        }

    @Test fun snapshotTagGrammarFailsClosed() =
        runTest {
            prepare()
            for (tag in listOf(
                "latest",
                "engine-approvals-1",
                "engine-approvals-1-x",
                "engine-approvals-1-2-3",
                "../etc",
                "engine-approvals--1",
            )) {
                requested.clear()
                prepareServer(index = indexDocument(body = """{"schema":1,"snapshotTag":"$tag"}"""))
                assertIndexRejected(updater().runUpdate(EngineUpdateTrigger.SCHEDULED))
            }
        }

    @Test fun allFourDocumentsComeFromOneSnapshot() =
        runTest {
            prepare()
            // The index flips to the next snapshot right after this run resolves it; a second
            // index read would mix documents across snapshots.
            var indexReads = 0
            val flipped = indexDocument(SNAPSHOT_TAG_NEXT)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.url.encodedPath
                        requested += path
                        var bytes = served[path]
                        if (path == INDEX_PATH && ++indexReads > 1) bytes = flipped
                        bytes ?: return MockResponse.Builder().code(404).build()
                        return MockResponse
                            .Builder()
                            .code(200)
                            .body(Buffer().write(bytes))
                            .build()
                    }
                }
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Activated(EngineUpdateFixtures.NEW_VERSION), outcome)
            assertEquals(1, requested.count { it == INDEX_PATH })
            val docs = requested.filter { it.contains("/download/$SNAPSHOT_TAG/") }
            assertEquals(4, docs.size)
            assertTrue(requested.none { it.contains("/download/$SNAPSHOT_TAG_NEXT/") })
            // The valid heartbeat reached the diagnostic record through the same snapshot.
            assertTrue(settings.state.value.heartbeatAtMs != null)
        }

    @Test fun missingHeartbeatStaysDiagnosticAndNeverGates() =
        runTest {
            prepare()
            prepareServer(heartbeat = null)
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Activated(EngineUpdateFixtures.NEW_VERSION), outcome)
            assertTrue(requested.any { it.contains("ytdlp-heartbeat") })
            assertTrue(settings.state.value.heartbeatAtMs == null)
        }

    @Test fun aNewIndexBetweenRunsResolvesEachSnapshotsOwnDocuments() =
        runTest {
            prepare()
            assertEquals(
                EngineUpdateOutcome.Activated(EngineUpdateFixtures.NEW_VERSION),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            // The next round's index points at a different immutable snapshot release.
            val newer = fixtures.archive(EngineUpdateFixtures.OTHER_VERSION)
            val nextSums = fixtures.sums(newer)
            val nextManifest =
                fixtures.manifest(
                    EngineUpdateFixtures.OTHER_VERSION,
                    sequence = 8,
                    sha256 = fixtures.sha256(newer),
                )
            served[INDEX_PATH] = indexDocument(SNAPSHOT_TAG_NEXT)
            serveSnapshot(
                SNAPSHOT_TAG_NEXT,
                manifest = nextManifest,
                signature = fixtures.signManifest(nextManifest).toByteArray(),
            )
            serveUpstream(
                EngineUpdateFixtures.OTHER_VERSION,
                sums = nextSums,
                sumsSignature = fixtures.sumsSignature(nextSums),
                archive = newer,
            )
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Activated(EngineUpdateFixtures.OTHER_VERSION), outcome)
            assertTrue(requested.any { it.contains("/download/$SNAPSHOT_TAG_NEXT/ytdlp-approved.json") })
        }

    @Test fun tamperedArchiveFailsTheSumsLineCheck() =
        runTest {
            prepare()
            val tampered =
                candidateArchive.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
            prepareServer(archive = tampered)
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(
                EngineUpdateOutcome.Rejected(EngineUpdateFixtures.NEW_VERSION, EngineRejectReason.HASH_MISMATCH),
                outcome,
            )
            assertNull(store.active.value.staged)
            assertEquals(EngineUpdateFixtures.BUNDLED_VERSION, store.active.value.current)
        }

    @Test fun archiveNamingAnotherVersionIsNotTheApprovedOne() =
        runTest {
            prepare()
            // The manifest approves NEW_VERSION; the served archive calls itself OTHER_VERSION.
            val foreign = fixtures.archive(EngineUpdateFixtures.OTHER_VERSION)
            prepareServer(
                archive = foreign,
                sums = fixtures.sums(foreign),
                manifest =
                    fixtures.manifest(
                        EngineUpdateFixtures.NEW_VERSION,
                        sha256 = fixtures.sha256(foreign),
                    ),
            )
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(
                EngineUpdateOutcome.Rejected(EngineUpdateFixtures.NEW_VERSION, EngineRejectReason.ORIGIN),
                outcome,
            )
            assertEquals(EngineUpdateFixtures.BUNDLED_VERSION, store.active.value.current)
        }

    @Test fun stagedSelftestFailureAbortsAndKeepsTheBundledCurrent() =
        runTest {
            prepare()
            selftestOk = false
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(
                EngineUpdateOutcome.Rejected(EngineUpdateFixtures.NEW_VERSION, EngineRejectReason.SELFTEST_FAILED),
                outcome,
            )
            val active = store.active.value
            assertEquals(EngineUpdateFixtures.BUNDLED_VERSION, active.current)
            assertNull(active.trial)
            assertNull(active.staged)
            assertTrue(
                File(paths.root.toFile(), "versions/${EngineUpdateFixtures.NEW_VERSION}").exists().not(),
            )
            assertEquals(EngineRejectReason.SELFTEST_FAILED, active.rejected.single().reason)
        }

    @Test fun signedApprovedEngineActivatesAtomically() =
        runTest {
            prepare()
            val outcome = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Activated(EngineUpdateFixtures.NEW_VERSION), outcome)
            val active = store.active.value
            assertEquals(EngineUpdateFixtures.NEW_VERSION, active.current)
            assertEquals(EngineUpdateFixtures.BUNDLED_VERSION, active.previous)
            assertEquals(
                EngineUpdateFixtures.NEW_VERSION,
                store.hostPrepared()!!.marker.engineVersion,
            )
            // A second run on the same manifest sequence is a no-op, not a re-install.
            assertEquals(EngineUpdateOutcome.UpToDate, updater().runUpdate(EngineUpdateTrigger.SCHEDULED))
        }

    @Test fun crashLoopAfterActivationRollsBackToPrevious() =
        runTest {
            prepare()
            // The previous version needs a recorded recent success for rollback to engage.
            health.reportExtraction(ExtractionOutcome.Success)
            assertEquals(
                EngineUpdateOutcome.Activated(EngineUpdateFixtures.NEW_VERSION),
                updater().runUpdate(EngineUpdateTrigger.SCHEDULED),
            )
            health.reportExtraction(ExtractionOutcome.ParseFailure("a"))
            health.reportExtraction(ExtractionOutcome.ParseFailure("b"))
            health.reportExtraction(ExtractionOutcome.ParseFailure("c"))
            awaitReal { store.active.value.current == EngineUpdateFixtures.BUNDLED_VERSION }
            assertNull(store.active.value.previous)
            assertTrue(
                store.active.value.rejected
                    .any { it.reason == EngineRejectReason.ROLLED_BACK },
            )
        }

    @Test fun interruptedStagingResumesWithoutSecondDownload() =
        runTest {
            prepare()
            // Keep the host busy so the run stops after staging instead of trying the candidate.
            transport.status.value = transport.status.value.copy(state = HostState.STARTING)
            val first = updater().runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Staged(EngineUpdateFixtures.NEW_VERSION), first)
            assertEquals(EngineUpdateFixtures.NEW_VERSION, store.active.value.staged)
            val downloadsBeforeResume = requested.count { it.endsWith("/yt-dlp") }
            // A restarted store re-discovers the staged tree on disk.
            val restarted = BundledEngineStore(paths, 1, clock)
            restarted.loadStatus()
            assertEquals(EngineUpdateFixtures.NEW_VERSION, restarted.active.value.staged)
            transport.status.value = transport.status.value.copy(state = HostState.STOPPED)
            val outcome = updater(restarted).runUpdate(EngineUpdateTrigger.SCHEDULED)
            assertEquals(EngineUpdateOutcome.Activated(EngineUpdateFixtures.NEW_VERSION), outcome)
            assertEquals(EngineUpdateFixtures.NEW_VERSION, restarted.active.value.current)
            assertEquals(downloadsBeforeResume, requested.count { it.endsWith("/yt-dlp") })
        }

    /** Rollback monitoring runs on a real IO thread inside the store; poll for its effect. */
    private suspend fun awaitReal(
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "rollback condition not met")
            withContext(Dispatchers.IO) { delay(20) }
        }
    }
}

/**
 * Loopback `EngineHttp` on `HttpURLConnection`: the fixture needs real HTTP against
 * MockWebServer, and `checkBannedApis` keeps `OkHttpClient` construction inside
 * :core:network:okhttp. Mirrors `OkHttpEngineHttp`'s semantics (no redirects, status and
 * size-cap failures) so EngineUpdater exercises the production port, not a stub.
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
            return connection.inputStream.use { it.readCapped(maxBytes) }
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
