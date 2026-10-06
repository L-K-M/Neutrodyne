// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformKind
import com.google.common.truth.Truth.assertThat
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class ClientFamilyTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private fun allKinds(clients: NetworkClients): List<OkHttpClient> = HttpClientKind.entries.map(clients::get)

    @Test
    fun `all seven kinds derive from one dispatcher and pool`() {
        val clients = newNetworkClients()
        val core = clients.base.dispatcher
        for ((kind, client) in HttpClientKind.entries.zip(allKinds(clients))) {
            assertThat(client.dispatcher).isSameInstanceAs(core)
            assertThat(client.connectionPool).isSameInstanceAs(clients.base.connectionPool)
            assertThat(clients[kind]).isSameInstanceAs(client)
        }
    }

    @Test
    fun `feed does not follow redirects, api does`() {
        val clients = newNetworkClients()
        assertThat(clients.feed.followRedirects).isFalse()
        assertThat(clients.feed.followSslRedirects).isFalse()
        assertThat(clients.api.followRedirects).isTrue()
    }

    @Test
    fun `per-kind timeouts come from the doc table`() {
        val clients = newNetworkClients()
        assertThat(clients.feed.callTimeoutMillis).isEqualTo(120_000)
        assertThat(clients.api.callTimeoutMillis).isEqualTo(8_000)
        assertThat(clients.image.readTimeoutMillis).isEqualTo(20_000)
        assertThat(clients.image.callTimeoutMillis).isEqualTo(60_000)
        assertThat(clients.download.readTimeoutMillis).isEqualTo(60_000)
        assertThat(clients[HttpClientKind.YOUTUBE].callTimeoutMillis).isEqualTo(60_000)
        assertThat(clients[HttpClientKind.SYNC].callTimeoutMillis).isEqualTo(60_000)
    }

    @Test
    fun `no client configures an OkHttp Cache`() {
        val clients = newNetworkClients()
        for (client in allKinds(clients)) assertThat(client.cache).isNull()
    }

    @Test
    fun `media and download carry identity encoding, others do not`() {
        val clients = newNetworkClients()
        assertThat(clients.media.interceptors).contains(IdentityEncodingInterceptor)
        assertThat(clients.download.interceptors).contains(IdentityEncodingInterceptor)
        assertThat(clients.feed.interceptors).doesNotContain(IdentityEncodingInterceptor)
        assertThat(clients.api.interceptors).doesNotContain(IdentityEncodingInterceptor)
    }

    @Test
    fun `base and its derivatives share the auth network interceptor, sync and youtube do not`() {
        val clients = newNetworkClients()
        val hasAuth = { c: OkHttpClient -> c.networkInterceptors.any { it is AuthInterceptor } }
        assertThat(hasAuth(clients.feed)).isTrue()
        assertThat(hasAuth(clients.api)).isTrue()
        assertThat(hasAuth(clients.media)).isTrue()
        assertThat(hasAuth(clients.download)).isTrue()
        assertThat(hasAuth(clients.image)).isTrue()
        assertThat(hasAuth(clients[HttpClientKind.SYNC])).isFalse()
        assertThat(hasAuth(clients[HttpClientKind.YOUTUBE])).isFalse()
    }

    @Test
    fun `guard interceptor runs first, user agent second`() {
        val access = LocalNetworkAccess()
        val clients = newNetworkClients(access = access, platform = fakePlatform(PlatformKind.ANDROID, 37))
        for (client in allKinds(clients)) {
            assertThat(client.interceptors[0]).isInstanceOf(LocalNetworkGuardInterceptor::class.java)
            assertThat(client.interceptors[1]).isInstanceOf(UserAgentInterceptor::class.java)
        }
        // On the desktop the guard is a pass-through but still sits in slot 0.
        val desktop = newNetworkClients()
        assertThat(desktop.feed.interceptors[0]).isSameInstanceAs(LocalNetworkGuard.PASS_THROUGH)
    }

    @Test
    fun `sync kind gets the SYNC-mode guard, others get STRICT`() {
        val access = LocalNetworkAccess()
        val clients = newNetworkClients(access = access, platform = fakePlatform(PlatformKind.ANDROID, 37))
        assertThat(clients[HttpClientKind.SYNC].dns).isInstanceOf(LocalNetworkGuardDns::class.java)
        assertThat(clients.feed.dns).isInstanceOf(LocalNetworkGuardDns::class.java)
        // On desktop the dns chain is just FamilyHintDns — no guard wrapper.
        assertThat(newNetworkClients().feed.dns).isInstanceOf(FamilyHintDns::class.java)
    }

    @Test
    fun `debug interceptors are appended to the application chain`() {
        val marker =
            Interceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .build()
            }
        val clients = newCoreClients(debug = setOf(marker))
        assertThat(clients.core.interceptors).contains(marker)
    }

    @Test
    fun `feed surfaces the 302 instead of following it`() {
        server.enqueue(mockResponse(code = 302, body = "", "Location" to "/moved"))
        val clients = newNetworkClients()
        clients.feed.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
            assertThat(it.code).isEqualTo(302)
        }
        assertThat(server.requestCount).isEqualTo(1)
    }
}
