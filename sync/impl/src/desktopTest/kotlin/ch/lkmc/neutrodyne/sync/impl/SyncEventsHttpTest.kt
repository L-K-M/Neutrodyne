// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.ProtocolRange
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.BufferedSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 10 Live updates over the real client stack: the `SYNC_STREAM` Ktor client opens
 * `GET /api/v1/events` with the device token and `hb`, a remote `changed` signal pokes the
 * scheduler's `SSE` round — the real engine pulls through `POST /api/v1/sync` on the same
 * wire — and a `401` closes the stream and marks the link revoked. The server half of the
 * same wire contract is proven by `:sync:server`'s `EventsHttpTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEventsHttpTest {
    private var db: NeutrodyneDatabase? = null
    private var server: MockWebServer? = null
    private var clients: OkHttpNeutrodyneHttpClients? = null

    @AfterTest
    fun tearDown() {
        clients?.close()
        server?.close()
        db?.close()
    }

    @Test
    fun `a remote push signal reaches the scheduler through SSE inside the budget`() =
        runTest {
            val fixture = fixture(sseBody = pushedFromOtherDevice())
            fixture.link()
            fixture.events.start(allowedFlow = flowOf(true), heartbeatSeconds = 60)

            // The stream opens (discover → /events), the remote `changed` pokes, and the round
            // pulls through /api/v1/sync — all within a fraction of PB31's 10 s.
            await("the /api/v1/sync pull") { fixture.syncRequested.takeIf { it.isCompleted } }
            await("the pulled cursor") {
                fixture.db
                    .syncStateDao()
                    .get()
                    ?.takeIf { it.cursor == PULLED_CURSOR }
            }

            assertTrue(fixture.rounds.isNotEmpty(), "the signal ran no round")
            assertTrue(
                fixture.rounds.all { it == SyncRoundReason.SSE },
                "every streamed signal must land as an SSE round: ${fixture.rounds}",
            )
        }

    @Test
    fun `the events request carries the token the protocol header and hb`() =
        runTest {
            val fixture = fixture(sseBody = stallThenClose())
            fixture.link()
            fixture.events.start(allowedFlow = flowOf(true), heartbeatSeconds = 90)

            val request = fixture.eventsRequested.await()

            assertEquals("GET", request.method)
            assertEquals("/api/v1/events", request.url.encodedPath)
            assertEquals("90", request.url.queryParameter("hb"))
            assertEquals("Bearer nds_wire", request.headers["Authorization"])
            assertEquals("1", request.headers["Neutrodyne-Sync-Protocol"])
            // ContentNegotiation prepends application/json; the SSE plugin adds the stream type.
            assertTrue(request.headers["Accept"]?.contains("text/event-stream") == true)
        }

    @Test
    fun `a 401 on the stream marks the link revoked and stops reconnecting`() =
        runTest {
            val fixture = fixture(eventsStatus = 401)
            fixture.link()
            fixture.events.start(allowedFlow = flowOf(true), heartbeatSeconds = 60)

            await("the revocation") { fixture.revoked.takeIf { it.isCompleted } }

            assertEquals(
                SYNC_ERROR_REVOKED,
                fixture.db
                    .syncStateDao()
                    .get()
                    ?.lastError,
            )
            // No reconnect: the stream died on the first open.
            assertEquals(1, fixture.eventRequests.get())
        }

    // --- fixture -----------------------------------------------------------------------------

    private class Fixture(
        val db: NeutrodyneDatabase,
        val engine: SyncEngine,
        val events: SyncEventsClient,
        val network: FakeNetworkMonitor,
        val clock: TestClock,
        val serverUrl: String,
        val rounds: MutableList<SyncRoundReason>,
        val syncRequested: CompletableDeferred<RecordedRequest>,
        val eventsRequested: CompletableDeferred<RecordedRequest>,
        val revoked: CompletableDeferred<Unit>,
        val eventRequests: AtomicInteger,
    )

    private suspend fun Fixture.link() {
        db.syncStateDao().upsert(
            SyncStateEntity(
                enabled = true,
                serverUrl = serverUrl,
                accountId = "acc",
                deviceId = "dev-B",
                nodeId = "0000000000000001",
                linkedAt = clock.now(),
            ),
        )
    }

    private suspend fun TestScope.fixture(
        sseBody: MockResponseBody? = null,
        eventsStatus: Int = 200,
    ): Fixture {
        val db = TestDb.inMemory().also { this@SyncEventsHttpTest.db = it }
        val server =
            MockWebServer()
                .also { this@SyncEventsHttpTest.server = it }
                .apply { start() }
        val platform = fakePlatform()
        val userAgent = UserAgentProvider(platform, "0.1.0", "https://example.com/repo")
        val networkClients =
            NetworkClients(
                CoreClients(
                    ua = UserAgentInterceptor(userAgent),
                    lanGuard = LocalNetworkGuard(LocalNetworkAccess(), platform),
                    hints = DnsFamilyHints(),
                    debugInterceptors = emptySet(),
                ),
                AuthInterceptor { CredentialLookup.None },
            )
        val http = OkHttpNeutrodyneHttpClients(networkClients, userAgent).also { clients = it }
        val serverUrl = server.url("/").toString().trimEnd('/')
        val secrets = FakeSecretStore()
        val tokens = SyncTokenStore(secrets)
        tokens.save(hostOf(serverUrl), "nds_wire")
        val address = FakeSyncServerAddress(serverUrl = serverUrl)
        val clock = TestClock()
        val client =
            KtorSyncClient(
                clients = http,
                tokenProvider = { tokens.load(hostOf(serverUrl)) },
                baseUrlProvider = { serverUrl },
            )
        val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
        val rounds = mutableListOf<SyncRoundReason>()
        val roundFailures = mutableListOf<Throwable>()
        val pushes = mutableListOf<SyncRoundReason>()
        val syncRequested = CompletableDeferred<RecordedRequest>()
        val eventsRequested = CompletableDeferred<RecordedRequest>()
        val revoked = CompletableDeferred<Unit>()
        val eventRequests = AtomicInteger()
        val engine = engine(db, client, clock, secrets, tokens, address)
        lateinit var events: SyncEventsClient
        // The production wiring (SyncBindings): the client's pokes ride the scheduler's round
        // path, revocation funnels through the unlink path, and every link change re-checks
        // the stream's gate.
        val scheduler =
            SyncScheduler(
                db = db,
                round = { reason ->
                    rounds += reason
                    try {
                        engine.round(reason)
                    } catch (failure: Throwable) {
                        roundFailures += failure
                        throw failure
                    }
                },
                playback = FakePlaybackSyncPort(),
                network = network,
                clock = clock,
                scope = backgroundScope,
                hooks = interactiveHooks(pushes),
                linkStateChanged = { events.reevaluate() },
            )
        events =
            SyncEventsClient(
                db = db,
                transport = KtorSyncEventTransport(http),
                discover = { url -> client.discover(url) },
                address = address,
                tokens = tokens,
                network = network,
                scope = backgroundScope,
                onRemoteChanged = { scheduler.onRemoteChanged() },
                onRevoked = {
                    revoked.complete(Unit)
                    scheduler.onUnlinked()
                },
            )
        val fixture =
            Fixture(
                db,
                engine,
                events,
                network,
                clock,
                serverUrl,
                rounds,
                syncRequested,
                eventsRequested,
                revoked,
                eventRequests,
            )
        diagnostics = {
            "events=${eventRequests.get()} rounds=$rounds requests=${server.requestCount} " +
                "connected=${events.connected.value} linked=${db.isSyncLinked()} " +
                "pushes=$pushes failures=${roundFailures.map { it.toString() }}"
        }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.url.encodedPath == "/.well-known/neutrodyne-sync" -> {
                            json(
                                SyncJson.encodeToString(
                                    DiscoveryDocument.serializer(),
                                    DiscoveryDocument(
                                        name = "test",
                                        serverVersion = "1.0.0",
                                        protocol = ProtocolRange(min = 1, max = 1),
                                        features = listOf("sse", "link"),
                                        maxBatch = 1_000,
                                        maxSkewMs = 300_000,
                                        serverTime = "2026-11-06T00:00:00Z",
                                    ),
                                ),
                            )
                        }

                        request.url.encodedPath == "/api/v1/events" -> {
                            eventRequests.incrementAndGet()
                            eventsRequested.complete(request)
                            when {
                                eventsStatus != 200 -> {
                                    MockResponse
                                        .Builder()
                                        .code(eventsStatus)
                                        .body("")
                                        .build()
                                }

                                sseBody != null -> {
                                    MockResponse
                                        .Builder()
                                        .code(200)
                                        .addHeader("Content-Type", "text/event-stream")
                                        .addHeader("Cache-Control", "no-store")
                                        .addHeader("X-Accel-Buffering", "no")
                                        .body(sseBody)
                                        .build()
                                }

                                else -> {
                                    MockResponse
                                        .Builder()
                                        .code(200)
                                        .body("")
                                        .build()
                                }
                            }
                        }

                        request.url.encodedPath == "/api/v1/sync" -> {
                            syncRequested.complete(request)
                            json(
                                SyncJson.encodeToString(
                                    SyncResponse.serializer(),
                                    SyncResponse(
                                        results = emptyList(),
                                        records = emptyList(),
                                        cursor = PULLED_CURSOR,
                                        hasMore = false,
                                        serverTime = "2026-11-06T00:00:00Z",
                                    ),
                                ),
                            )
                        }

                        else -> {
                            MockResponse
                                .Builder()
                                .code(404)
                                .body("")
                                .build()
                        }
                    }
            }
        return fixture
    }

    private fun engine(
        db: NeutrodyneDatabase,
        client: SyncClient,
        clock: TestClock,
        secrets: FakeSecretStore,
        tokens: SyncTokenStore,
        address: FakeSyncServerAddress,
    ): SyncEngine =
        SyncEngine(
            db = db,
            client = client,
            applier =
                SyncApplier(
                    db = db,
                    clock = clock,
                    secrets = secrets,
                    settings = FakeSettingsSyncPort(),
                    playback = NoPlaybackSync,
                    syncIngestHook = SyncIngestHook.None,
                    channelSync =
                        object : GroupChannelSync {
                            override suspend fun sync() = Unit

                            override suspend fun onUuidChanged(
                                oldUuid: String,
                                newUuid: String,
                            ) = Unit

                            override suspend fun onDeleted(uuid: String) = Unit
                        },
                    sharePasswords = { false },
                ),
            clock = clock,
            secrets = secrets,
            sharePasswords = { false },
            address = address,
            tokens = tokens,
            effects =
                SyncApplyEffects(
                    db = db,
                    settings = FakeSettingsSyncPort(),
                    secrets = secrets,
                    coordinator = FakeCredentialCommitCoordinator(),
                ),
            notices = SyncNoticeBus(),
        )

    private fun interactiveHooks(pushes: MutableList<SyncRoundReason> = mutableListOf()) =
        object : SyncScheduleHooks {
            override fun interactive(): Boolean = true

            override fun schedulePush(reason: SyncRoundReason) {
                pushes += reason
            }

            override suspend fun onLinked() = Unit

            override suspend fun onUnlinked() = Unit
        }

    /**
     * Real wire against a virtual clock: poll in small real-time slices while pumping the test
     * scheduler, so the coalesced `SSE` round's 500 ms delay and the stream's own coroutines run.
     */
    private suspend fun TestScope.await(
        what: String,
        deadlineMs: Long = AWAIT_MS,
        found: suspend () -> Any?,
    ) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (found() == null) {
            if (System.currentTimeMillis() > deadline) {
                fail("$what did not arrive (${diagnostics()})")
            }
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(PUMP_MS)
            Thread.sleep(POLL_MS)
        }
        testScheduler.runCurrent()
    }

    /** Failure-time breadcrumbs: which wire requests landed and which rounds ran. */
    private var diagnostics: suspend () -> String = { "" }

    private fun json(body: String): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun fakePlatform() =
        object : PlatformInfo {
            override val kind = PlatformKind.DESKTOP
            override val userAgentPlatform = "Linux; x64"
            override val androidSdkInt: Int? = null
            override val regionCode = "DE"
        }

    // --- scripted stream bodies ------------------------------------------------------------

    /**
     * One `retry` line and one remote `changed`, then the server ends the stream — the client's
     * next reconnect serves the same body again (a stream cycling is harmless for the test).
     */
    private fun pushedFromOtherDevice(): MockResponseBody =
        object : MockResponseBody {
            override val contentLength: Long get() = -1L

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("retry: 10000\n\n")
                sink.writeUtf8(
                    "event: changed\ndata: {\"cursor\":\"$PULLED_CURSOR\",\"by\":\"dev-A\"}\n\n",
                )
                sink.flush()
            }
        }

    /**
     * Writes the `retry` line, then holds the stream open — the request-headers test reads the
     * exchange from the dispatcher while the body still blocks.
     */
    private fun stallThenClose(): MockResponseBody =
        object : MockResponseBody {
            private val hold = CountDownLatch(1)

            override val contentLength: Long get() = -1L

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("retry: 10000\n\n")
                sink.flush()
                hold.await(HOLD_SECONDS, TimeUnit.SECONDS)
            }
        }

    private companion object {
        const val PULLED_CURSOR = "c:00000001:2"
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 10L
        const val PUMP_MS = 50L

        /** How long the held stream body may block the server's write thread. */
        const val HOLD_SECONDS = 3L
    }
}
