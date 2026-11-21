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
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheQuery
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The feed-cache half of [KtorSyncClient] over the production `OkHttpNeutrodyneHttpClients`
 * against a local `MockWebServer`: the binding tests fake [SyncClient], so the wire path is
 * only observable here.
 */
class KtorSyncClientFeedCacheTest {
    private var server: MockWebServer? = null
    private var clients: OkHttpNeutrodyneHttpClients? = null

    @AfterTest
    fun tearDown() {
        clients?.close()
        server?.close()
    }

    @Test
    fun feedCachePostsToTheApiRootWithAuthAndProtocol() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(
                json(
                    """{"feeds":[{"url":"https://feed.example/rss",""" +
                        """"finalUrl":"https://feed.example/rss","sha256":"${"0".repeat(64)}",""" +
                        """"checkedAt":1791072000000}]}""",
                ),
            )

            val response = fixture.client.feedCache(listOf(FeedCacheQuery("https://feed.example/rss", null)))

            val request = fixture.take()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/feed-cache", request.url.encodedPath)
            assertEquals("Bearer tok", request.headers["Authorization"])
            assertEquals(PROTOCOL_VERSION.toString(), request.headers["Neutrodyne-Sync-Protocol"])
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("\"url\":\"https://feed.example/rss\""), "body was: $body")
            assertEquals(1, response.feeds.size)
            assertEquals("https://feed.example/rss", response.feeds.single().url)
        }

    @Test
    fun aMissingEndpointIsATransportException() =
        runTest {
            val fixture = fixture()
            fixture.server.enqueue(json("""{"code":"not_found"}""", 404))

            val failure =
                kotlin.test.assertFailsWith<SyncTransportException> {
                    fixture.client.feedCache(listOf(FeedCacheQuery("https://feed.example/rss", null)))
                }

            assertEquals(404, failure.status)
        }

    private class Fixture(
        val client: KtorSyncClient,
        val server: MockWebServer,
    )

    private fun Fixture.take(): RecordedRequest =
        assertNotNull(
            server.takeRequest(5, TimeUnit.SECONDS),
            "no request reached the server",
        )

    private fun fixture(): Fixture {
        val server = MockWebServer().also { this.server = it }.apply { start() }
        val platform =
            object : PlatformInfo {
                override val kind = PlatformKind.DESKTOP
                override val userAgentPlatform = "Linux; x64"
                override val androidSdkInt: Int? = null
                override val regionCode = "DE"
            }
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
        return Fixture(
            client =
                KtorSyncClient(
                    http,
                    tokenProvider = { "tok" },
                    baseUrlProvider = { server.url("/").toString() },
                ),
            server = server,
        )
    }

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
}
