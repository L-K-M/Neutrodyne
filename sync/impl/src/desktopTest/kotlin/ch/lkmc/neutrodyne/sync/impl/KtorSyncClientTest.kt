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
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertTrue

/**
 * `KtorSyncClient` over the production `OkHttpNeutrodyneHttpClients` against a local
 * `MockWebServer`: the scripted `SyncClient` fakes the engine tests use never see the wire, so
 * serialization and URL building are only observable on a real HTTP exchange. `checkBannedApis`
 * keeps `HttpClient` construction inside `:core:network`, so no `MockEngine` here.
 */
class KtorSyncClientTest {
    private var server: MockWebServer? = null
    private var clients: OkHttpNeutrodyneHttpClients? = null

    private val device = DeviceInfoDto("dev-1", "desk", "desktop", "1.0", "node-1")
    private val tokenJson = """{"token":"t","accountId":"a","deviceId":"d","username":"u"}"""

    @AfterTest
    fun tearDown() {
        clients?.close()
        server?.close()
    }

    @Test
    fun aLoginSerializesTheRequestBodyAsLoginRequest() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json(tokenJson))

            fixture.client.login(fixture.baseUrl, "alice", "secret", device)

            val body =
                fixture
                    .take()
                    .body
                    ?.utf8()
                    .orEmpty()
            assertTrue(body.contains("\"username\":\"alice\""), "body was: $body")
            assertTrue(body.contains("\"id\":\"dev-1\""), "body was: $body")
        }

    @Test
    fun aLinkTokenBadRequestBecomesAProblemException() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json("""{"error":"authorization_pending"}""", 400))

            val failure = assertFailsWith<Exception> { fixture.client.linkToken(fixture.baseUrl, "dc") }

            assertIs<SyncProblemException>(failure)
            assertEquals("authorization_pending", failure.error)
        }

    @Test
    fun aLinkTokenFailureBecomesATransportException() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json("""{"code":"SERVER_ERROR"}""", 500))

            val failure = assertFailsWith<Exception> { fixture.client.linkToken(fixture.baseUrl, "dc") }

            assertIs<SyncTransportException>(failure)
        }

    @Test
    fun aLinkTokenRateLimitIsATransportException() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json("""{"code":"RATE_LIMITED"}""", 429, "Retry-After" to "12"))

            val failure = assertFailsWith<Exception> { fixture.client.linkToken(fixture.baseUrl, "dc") }

            assertIs<SyncTransportException>(failure)
            assertEquals(12L, failure.retryAfterSeconds)
        }

    @Test
    fun theChangesRequestTrimsTheBaseAndEncodesTheCursor() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(
                json("""{"records":[],"cursor":"c:1:0","hasMore":false,"serverTime":"t"}"""),
            )

            fixture.client.changes("c:0000 0001:4 2", 5)

            val request = fixture.take()
            assertEquals("/api/v1/changes", request.url.encodedPath)
            assertEquals("c:0000 0001:4 2", request.url.queryParameter("since"))
            assertEquals("5", request.url.queryParameter("limit"))
        }

    @Test
    fun aLinkApproveDecodesThePendingDevice() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(
                json(
                    """{"deviceName":"Anna's phone","platform":"android","appVersion":"0.9.3",""" +
                        """"requestedAt":"2026-11-04T10:00:00Z","purpose":"device","sameNetwork":false}""",
                ),
            )

            val response = fixture.client.linkApprove(fixture.baseUrl, "link-tok", "WDJB-MJHT")

            val request = fixture.take()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/link/approve", request.url.encodedPath)
            assertEquals("Bearer link-tok", request.headers["Authorization"])
            assertEquals(PROTOCOL_VERSION.toString(), request.headers["Neutrodyne-Sync-Protocol"])
            assertTrue(
                request.body
                    ?.utf8()
                    .orEmpty()
                    .contains("\"userCode\":\"WDJB-MJHT\""),
            )
            assertEquals("Anna's phone", response.deviceName)
            assertEquals("android", response.platform)
            assertEquals("0.9.3", response.appVersion)
            assertEquals("2026-11-04T10:00:00Z", response.requestedAt)
            assertEquals("device", response.purpose)
            assertEquals(false, response.sameNetwork)
        }

    @Test
    fun aLinkConfirmApproveAcceptsTheEmptyNoContentReply() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(noContent())

            fixture.client.linkConfirm(fixture.baseUrl, "link-tok", "ABCD-EFGH", approve = true)

            val request = fixture.take()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/link/confirm", request.url.encodedPath)
            assertEquals("Bearer link-tok", request.headers["Authorization"])
            assertEquals(PROTOCOL_VERSION.toString(), request.headers["Neutrodyne-Sync-Protocol"])
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("\"userCode\":\"ABCD-EFGH\""), "body was: $body")
            assertTrue(body.contains("\"approve\":true"), "body was: $body")
        }

    @Test
    fun aLinkConfirmDenyAcceptsTheEmptyNoContentReply() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(noContent())

            fixture.client.linkConfirm(fixture.baseUrl, "link-tok", "ABCD-EFGH", approve = false)

            val body =
                fixture
                    .take()
                    .body
                    ?.utf8()
                    .orEmpty()
            assertTrue(body.contains("\"approve\":false"), "body was: $body")
        }

    @Test
    fun aLinkConfirmFailureBecomesATransportException() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json("""{"code":"LINK_CODE_CONSUMED"}""", 409))

            val failure =
                assertFailsWith<Exception> {
                    fixture.client.linkConfirm(fixture.baseUrl, "link-tok", "ABCD-EFGH", approve = true)
                }

            assertIs<SyncTransportException>(failure)
            assertEquals(409, failure.status)
        }

    @Test
    fun aLogoutCarriesTheBearerToken() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(noContent())

            fixture.client.logout()

            val request = fixture.take()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/logout", request.url.encodedPath)
            assertEquals("Bearer tok", request.headers["Authorization"])
        }

    @Test
    fun aLogoutFailureBecomesATransportException() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json("""{"code":"SERVER_ERROR"}""", 500))

            val failure = assertFailsWith<Exception> { fixture.client.logout() }

            assertIs<SyncTransportException>(failure)
            assertEquals(500, failure.status)
        }

    // The no-content handling is per endpoint: endpoints that must decode a body still reject
    // empty or malformed replies instead of silently succeeding.
    @Test
    fun anEmptyReplyWhereJsonIsRequiredStillFails() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json(""))

            assertFailsWith<Exception> { fixture.client.account() }
        }

    @Test
    fun aMalformedReplyWhereJsonIsRequiredStillFails() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json("{malformed"))

            assertFailsWith<Exception> { fixture.client.login(fixture.baseUrl, "a", "s", device) }
        }

    // --- fixture -----------------------------------------------------------------------------

    private class Fixture(
        val client: KtorSyncClient,
        val server: MockWebServer,
        /** `server.url("/")` keeps its trailing slash — every call proves the client's `trimEnd('/')`. */
        val baseUrl: String,
    )

    private fun Fixture.take(): RecordedRequest =
        assertNotNull(
            server.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS),
            "no request reached the server",
        )

    private fun fixture(): Fixture {
        val server = MockWebServer().also { this.server = it }.apply { start() }
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
            client = KtorSyncClient(http, tokenProvider = { "tok" }, baseUrlProvider = { baseUrl }),
            server = server,
            baseUrl = baseUrl,
        )
    }

    private fun noContent(): MockResponse = MockResponse.Builder().code(204).build()

    private fun json(
        body: String,
        code: Int = 200,
        vararg headers: Pair<String, String>,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
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
