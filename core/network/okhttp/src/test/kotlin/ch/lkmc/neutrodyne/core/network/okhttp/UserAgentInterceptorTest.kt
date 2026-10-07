// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import com.google.common.truth.Truth.assertThat
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Request
import org.junit.Rule
import org.junit.Test

class UserAgentInterceptorTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    @Test
    fun `sets the app user agent when the request has none`() {
        server.enqueue(mockResponse())
        newNetworkClients()
            .feed
            .newCall(Request.Builder().url(server.url("/")).build())
            .execute()
            .close()

        assertThat(server.takeRequest().headers["User-Agent"])
            .isEqualTo("Neutrodyne/0.1.0 (Linux; x64; +https://example.com/repo)")
    }

    @Test
    fun `leaves an explicit user agent alone`() {
        server.enqueue(mockResponse())
        val request =
            Request
                .Builder()
                .url(server.url("/"))
                .header("User-Agent", "yt-dlp/2025.1")
                .build()
        newNetworkClients()
            .feed
            .newCall(request)
            .execute()
            .close()

        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo("yt-dlp/2025.1")
    }
}
