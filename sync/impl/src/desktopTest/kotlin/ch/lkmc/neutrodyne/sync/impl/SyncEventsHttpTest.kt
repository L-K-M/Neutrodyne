// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KtorSyncEventTransport` over the production `OkHttpNeutrodyneHttpClients` against a local
 * `MockWebServer` (10 Live updates): the scripted transport the supervisor tests use never
 * sees the wire, so the events URL, the bearer header, the SSE parse and the redirect's
 * credential handling are only observable on a real exchange. The same stack then runs the
 * authoritative pull — the stream is signaling only.
 */
class SyncEventsHttpTest {
    private val servers = mutableListOf<MockWebServer>()
    private var clients: OkHttpNeutrodyneHttpClients? = null

    @AfterTest
    fun tearDown() {
        clients?.close()
        servers.forEach { it.close() }
    }

    @Test
    fun theEventsRequestCarriesTheBearerAndParsesTheStream() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(
                sse(
                    "retry: 10000\n\n" +
                        ": hb\n\n" +
                        "event: changed\n" +
                        "data: {\"cursor\":\"c:1:0\",\"by\":\"dev-2\"}\n\n",
                    // Real wire latency: a virtual-clock deadline would elapse
                    // before the delayed body lands.
                    bodyDelayMs = 100,
                ),
            )

            val session = fixture.transport.open(fixture.baseUrl, "tok", 60)
            try {
                val events = session.collectEvents(3)
                assertEquals(
                    listOf(
                        SyncStreamEvent.Retry(10_000),
                        SyncStreamEvent.Comment,
                        SyncStreamEvent.Changed("dev-2"),
                    ),
                    events,
                )

                val request = fixture.take()
                assertEquals("GET", request.method)
                assertEquals("/api/v1/events", request.url.encodedPath)
                assertEquals("60", request.url.queryParameter("hb"))
                assertEquals("Bearer tok", request.headers["Authorization"])
                assertEquals(PROTOCOL_VERSION.toString(), request.headers["Neutrodyne-Sync-Protocol"])
            } finally {
                session.close()
            }
        }

    @Test
    fun a401SurfacesAsTheRevokedStatus() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json("""{"error":"unauthorized"}""", 401))

            val failure =
                assertFailsWith<SyncTransportException> {
                    fixture.transport.open(fixture.baseUrl, "tok", 60)
                }

            assertEquals(401, failure.status)
        }

    @Test
    fun aCrossOriginRedirectDropsTheBearer() =
        runTest {
            val fixture = fixture()
            val other = MockWebServer().also { servers += it }.apply { start() }
            other.enqueue(sse(": hb\n\n"))
            fixture.server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", other.url("/api/v1/events?hb=60").toString())
                    .build(),
            )

            val session = fixture.transport.open(fixture.baseUrl, "tok", 60)
            try {
                val events = session.collectEvents(1)
                assertEquals(listOf(SyncStreamEvent.Comment), events)
                // The first hop carried the bearer; the cross-authority hop must not.
                assertEquals("Bearer tok", fixture.take().headers["Authorization"])
                val redirected =
                    assertNotNull(
                        other.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS),
                        "no request reached the redirect target",
                    )
                assertNull(redirected.headers["Authorization"], "bearer leaked to a cross-origin hop")
            } finally {
                session.close()
            }
        }

    @Test
    fun theStreamAndTheAuthoritativePullShareTheStack() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(sse("event: changed\ndata: {\"cursor\":\"c:1:0\",\"by\":\"dev-2\"}\n\n"))
            fixture.server.enqueue(
                json("""{"records":[],"cursor":"c:1:0","hasMore":false,"serverTime":"t"}"""),
            )

            val session = fixture.transport.open(fixture.baseUrl, "tok", 60)
            try {
                val events = session.collectEvents(1)
                assertEquals(listOf(SyncStreamEvent.Changed("dev-2")), events)

                // The changed event only signals: the pull itself is a normal sync request with
                // the bearer the per-request header adds — never an interceptor shared elsewhere.
                fixture.client.changes("c:0:0", 5)

                val pull = fixture.take(2)
                assertEquals("/api/v1/changes", pull.url.encodedPath)
                assertEquals("Bearer tok", pull.headers["Authorization"])
            } finally {
                session.close()
            }
        }

    // --- fixture -----------------------------------------------------------------------------

    /**
     * Awaits [count] stream events under a wall-clock 5 s bound. `withTimeout` inside
     * `runTest` ticks on the virtual scheduler, which fast-forwards while the collect
     * is parked on the real socket and cancels it before MockWebServer's body lands;
     * on a real dispatcher the same bound measures the wire latency it exists to cap.
     */
    private suspend fun SyncEventSession.collectEvents(count: Int): List<SyncStreamEvent> =
        withContext(Dispatchers.IO) {
            withTimeout(5_000) { events.take(count).toList() }
        }

    private class Fixture(
        val transport: KtorSyncEventTransport,
        val client: KtorSyncClient,
        val server: MockWebServer,
        val baseUrl: String,
    )

    private fun Fixture.take(skip: Int = 1): RecordedRequest {
        var request: RecordedRequest? = null
        repeat(skip) {
            request =
                assertNotNull(
                    server.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS),
                    "no request reached the server",
                )
        }
        return request!!
    }

    private fun fixture(): Fixture {
        val server = MockWebServer().also { servers += it }.apply { start() }
        val platform = fakePlatform()
        val userAgent = UserAgentProvider(platform, "0.1.0", "https://example.com/repo")
        val http =
            OkHttpNeutrodyneHttpClients(
                NetworkClients(
                    CoreClients(
                        ua = UserAgentInterceptor(userAgent),
                        lanGuard = LocalNetworkGuard(LocalNetworkAccess(), platform),
                        hints = DnsFamilyHints(),
                        debugInterceptors = emptySet(),
                    ),
                    AuthInterceptor { CredentialLookup.None },
                ),
                userAgent,
            ).also { clients = it }
        val baseUrl = server.url("/").toString()
        return Fixture(
            transport = KtorSyncEventTransport(http),
            client = KtorSyncClient(http, tokenProvider = { "tok" }, baseUrlProvider = { baseUrl }),
            server = server,
            baseUrl = baseUrl,
        )
    }

    private fun sse(
        body: String,
        bodyDelayMs: Long = 0,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "text/event-stream")
            .body(body)
            .bodyDelay(bodyDelayMs, TimeUnit.MILLISECONDS)
            .build()

    private fun json(
        body: String,
        code: Int = 200,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
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

    private companion object {
        const val TAKE_SECONDS = 5L
    }
}
