// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `KtorSyncClient` against Ktor's `MockEngine`: the scripted `SyncClient` fakes the engine tests
 * use never see the wire, so serialization and URL building are only observable here.
 */
class KtorSyncClientTest {
    private val device = DeviceInfoDto("dev-1", "desk", "desktop", "1.0", "node-1")
    private val tokenJson = """{"token":"t","accountId":"a","deviceId":"d","username":"u"}"""

    private fun client(
        baseUrl: String = "https://sync.test/",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorSyncClient {
        val engine = MockEngine(handler)
        val clients =
            object : NeutrodyneHttpClients {
                override fun client(kind: HttpClientKind): HttpClient = HttpClient(engine)
            }
        return KtorSyncClient(clients, tokenProvider = { "tok" }, baseUrlProvider = { baseUrl })
    }

    private fun jsonHeaders(vararg extra: Pair<String, String>) =
        headersOf(
            *(
                listOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())) +
                    extra.map { it.first to listOf(it.second) }
            ).toTypedArray(),
        )

    @Test
    fun aLoginSerializesTheRequestBodyAsLoginRequest() =
        runTest {
            var captured: String? = null
            val client =
                client {
                    captured = (it.body as TextContent).text
                    respond(ByteReadChannel(tokenJson), HttpStatusCode.OK, jsonHeaders())
                }
            client.login("https://sync.test", "alice", "secret", device)
            val body = captured.orEmpty()
            assertTrue(body.contains("\"username\":\"alice\""), "body was: $body")
            assertTrue(body.contains("\"id\":\"dev-1\""), "body was: $body")
        }

    @Test
    fun aLinkTokenFailureBecomesATransportException() =
        runTest {
            val client =
                client {
                    respond(
                        ByteReadChannel("""{"code":"SERVER_ERROR"}"""),
                        HttpStatusCode.InternalServerError,
                        jsonHeaders(),
                    )
                }
            val failure = assertFailsWith<Exception> { client.linkToken("https://sync.test", "dc") }
            assertIs<SyncTransportException>(failure)
        }

    @Test
    fun aLinkTokenRateLimitIsATransportException() =
        runTest {
            val client =
                client {
                    respond(
                        ByteReadChannel("""{"code":"RATE_LIMITED"}"""),
                        HttpStatusCode.TooManyRequests,
                        jsonHeaders(HttpHeaders.RetryAfter to "12"),
                    )
                }
            val failure = assertFailsWith<Exception> { client.linkToken("https://sync.test", "dc") }
            assertIs<SyncTransportException>(failure)
            assertEquals(12L, failure.retryAfterSeconds)
        }

    @Test
    fun theChangesRequestTrimsTheBaseAndEncodesTheCursor() =
        runTest {
            var path: String? = null
            var since: String? = null
            var limit: String? = null
            val client =
                client {
                    path = it.url.encodedPath
                    since = it.url.parameters["since"]
                    limit = it.url.parameters["limit"]
                    respond(
                        ByteReadChannel(
                            """{"records":[],"cursor":"c:1:0","hasMore":false,"serverTime":"t"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }
            client.changes("c:0000 0001:4 2", 5)
            assertEquals("/api/v1/changes", path)
            assertEquals("c:0000 0001:4 2", since)
            assertEquals("5", limit)
        }
}
