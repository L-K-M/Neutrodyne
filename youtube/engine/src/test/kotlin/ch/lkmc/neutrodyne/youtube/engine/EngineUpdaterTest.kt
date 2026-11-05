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
    private var manifestBytes: ByteArray = byteArrayOf()
    private var manifestSig: String = ""
    private var sumsBytes: ByteArray = byteArrayOf()
    private var sumsSig: ByteArray = byteArrayOf()
    private var archiveBytes: ByteArray = byteArrayOf()

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

    private fun prepareServer(
        manifest: ByteArray? = null,
        signature: String? = null,
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
        sumsBytes = sums ?: fixtures.sums(candidateArchive)
        sumsSig = sumsSignature ?: fixtures.sumsSignature(sumsBytes)
        archiveBytes = archive ?: candidateArchive
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requested += path
                    val bytes =
                        when (path) {
                            "/engine/ytdlp-approved.json" -> manifestBytes
                            "/engine/ytdlp-approved.json.sig" -> manifestSig.toByteArray()
                            "/releases/download/${EngineUpdateFixtures.NEW_VERSION}/SHA2-256SUMS" -> sumsBytes
                            "/releases/download/${EngineUpdateFixtures.NEW_VERSION}/SHA2-256SUMS.sig" -> sumsSig
                            "/releases/download/${EngineUpdateFixtures.NEW_VERSION}/yt-dlp" -> archiveBytes
                            else -> return MockResponse.Builder().code(404).build()
                        }
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
            http = fixtures.engineHttp(),
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
            manifestUrl = server.url("/engine/ytdlp-approved.json").toString(),
            control = { YtxControlRequest(YtxContext("en", "US", "fixture/1")) },
            releaseBaseUrl = server.url("/releases").toString(),
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
