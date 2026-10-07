// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import com.google.common.truth.Truth.assertThat
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Request
import org.junit.Rule
import org.junit.Test

class IdentityEncodingInterceptorTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    @Test
    fun `media and download requests send Accept-Encoding identity`() {
        server.enqueue(mockResponse())
        server.enqueue(mockResponse())
        val clients = newNetworkClients()
        val request = { Request.Builder().url(server.url("/")).build() }
        clients.media
            .newCall(request())
            .execute()
            .close()
        clients.download
            .newCall(request())
            .execute()
            .close()

        assertThat(server.takeRequest().headers["Accept-Encoding"]).isEqualTo("identity")
        assertThat(server.takeRequest().headers["Accept-Encoding"]).isEqualTo("identity")
    }

    @Test
    fun `other clients keep OkHttp's gzip default`() {
        server.enqueue(mockResponse())
        newNetworkClients()
            .feed
            .newCall(Request.Builder().url(server.url("/")).build())
            .execute()
            .close()

        // OkHttp's bridge adds gzip itself — the island must not have pinned identity here.
        assertThat(server.takeRequest().headers["Accept-Encoding"]).isEqualTo("gzip")
    }
}
