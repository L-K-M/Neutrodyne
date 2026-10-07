// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Request
import org.junit.Rule
import org.junit.Test

/**
 * `KtorUserAgentTest` (01 Testing): the Ktor `UserAgent` plugin and the island's
 * `UserAgentInterceptor` must agree — default requests carry the Neutrodyne agent and an explicit
 * per-request agent (yt-dlp's) survives both layers.
 */
class KtorUserAgentTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val agent = "Neutrodyne/0.1.0 (Linux; x64; +https://example.com/repo)"

    @Test
    fun `ktor request without a user agent gets the app agent`() =
        runBlocking {
            server.enqueue(mockResponse())
            val clients = newHttpClients()
            try {
                clients.client(HttpClientKind.FEED).get(server.url("/").toString()).bodyAsText()
            } finally {
                clients.closeAll()
            }
            assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo(agent)
        }

    @Test
    fun `a per-request user agent is left alone by the plugin`() =
        runBlocking {
            server.enqueue(mockResponse())
            val clients = newHttpClients()
            try {
                clients
                    .client(HttpClientKind.FEED)
                    .get(server.url("/").toString()) {
                        header("User-Agent", "yt-dlp/2025.09.26")
                    }.bodyAsText()
            } finally {
                clients.closeAll()
            }
            assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo("yt-dlp/2025.09.26")
        }

    @Test
    fun `the underlying okhttp client gets the same treatment`() =
        runBlocking {
            server.enqueue(mockResponse())
            val networkClients = newNetworkClients()
            networkClients.feed
                .newCall(Request.Builder().url(server.url("/")).build())
                .execute()
                .close()
            assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo(agent)
        }
}
