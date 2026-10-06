// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformKind
import com.google.common.truth.Truth.assertThat
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Dns
import okhttp3.Request
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress

class LocalNetworkGuardTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val android37 get() = fakePlatform(PlatformKind.ANDROID, sdkInt = 37)

    private fun guardDns(
        vararg addresses: InetAddress,
        mode: LocalNetworkGuard.Mode = LocalNetworkGuard.Mode.STRICT,
        access: LocalNetworkAccess = LocalNetworkAccess(),
    ) = LocalNetworkGuardDns({ addresses.toList() }, mode, access)

    private fun addr(vararg bytes: Int): InetAddress = InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())

    @Test
    fun `activates only on Android API 37 and up`() {
        assertThat(LocalNetworkGuard(LocalNetworkAccess(), fakePlatform()).active).isFalse()
        assertThat(LocalNetworkGuard(LocalNetworkAccess(), fakePlatform(PlatformKind.ANDROID, 36)).active)
            .isFalse()
        assertThat(LocalNetworkGuard(LocalNetworkAccess(), android37).active).isTrue()
        // A null SDK (never happens on Android) degrades to pass-through, not a crash.
        assertThat(LocalNetworkGuard(LocalNetworkAccess(), fakePlatform(PlatformKind.ANDROID, null)).active)
            .isFalse()
    }

    @Test
    fun `strict client rejects an RFC 1918 IP literal before connecting`() {
        val clients = newNetworkClients(platform = android37)
        val e =
            assertThrows(LocalNetworkUnsupportedException::class.java) {
                clients.feed.newCall(Request.Builder().url("http://10.1.2.3/").build()).execute()
            }
        assertThat(e).isInstanceOf(java.net.UnknownHostException::class.java)
    }

    @Test
    fun `strict client rejects link-local, ULA and dot-local`() {
        val clients = newNetworkClients(platform = android37)
        for (url in listOf("http://169.254.10.20/", "http://[fd00::1]/", "http://[fe80::5]/", "http://nas.local/")) {
            assertThrows(LocalNetworkUnsupportedException::class.java) {
                clients.feed.newCall(Request.Builder().url(url).build()).execute()
            }
        }
    }

    @Test
    fun `loopback literals always pass`() {
        server.enqueue(mockResponse())
        val clients = newNetworkClients(platform = android37)
        clients.feed
            .newCall(Request.Builder().url("http://127.0.0.1:${server.port}/").build())
            .execute()
            .close()
        assertThat(server.takeRequest()).isNotNull()
    }

    @Test
    fun `guard is a pass-through on desktop and below API 37`() {
        server.enqueue(mockResponse())
        server.enqueue(mockResponse())
        for (platform in listOf(fakePlatform(), fakePlatform(PlatformKind.ANDROID, 36))) {
            newNetworkClients(platform = platform)
                .feed
                .newCall(Request.Builder().url(server.url("/")).build())
                .execute()
                .close()
            assertThat(server.takeRequest()).isNotNull()
        }
    }

    @Test
    fun `dns rejects an all-private answer but passes mixed and loopback`() {
        val publicIp = addr(93, 184, 216, 34)
        assertThrows(LocalNetworkUnsupportedException::class.java) {
            guardDns(addr(192, 168, 1, 5)).lookup("nas")
        }
        assertThrows(LocalNetworkUnsupportedException::class.java) {
            guardDns(addr(10, 0, 0, 1), addr(192, 168, 1, 5)).lookup("nas")
        }
        // A mixed public/private answer passes — the public address is reachable.
        assertThat(guardDns(addr(10, 0, 0, 1), publicIp).lookup("cdn.example.com"))
            .containsExactly(addr(10, 0, 0, 1), publicIp)
        // All-loopback answers pass (developer workstations).
        assertThat(guardDns(addr(127, 0, 0, 1)).lookup("localhost")).containsExactly(addr(127, 0, 0, 1))
        assertThat(guardDns(addr(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)).lookup("ip6-localhost"))
            .hasSize(1)
    }

    @Test
    fun `dns rejects link-local ULA and v4-mapped-v6 answers`() {
        assertThrows(LocalNetworkUnsupportedException::class.java) {
            guardDns(addr(169, 254, 1, 1)).lookup("printer")
        }
        assertThrows(LocalNetworkUnsupportedException::class.java) {
            guardDns(InetAddress.getByName("fe80::1")).lookup("printer")
        }
        assertThrows(LocalNetworkUnsupportedException::class.java) {
            guardDns(InetAddress.getByName("fd00::1")).lookup("printer")
        }
        assertThrows(LocalNetworkUnsupportedException::class.java) {
            guardDns(InetAddress.getByName("::ffff:10.0.0.9")).lookup("printer")
        }
    }

    @Test
    fun `sync mode bypasses only while access is granted`() {
        val access = LocalNetworkAccess()
        val privateIp = addr(192, 168, 1, 5)
        val syncDns = guardDns(privateIp, mode = LocalNetworkGuard.Mode.SYNC, access = access)
        val strictDns = guardDns(privateIp, mode = LocalNetworkGuard.Mode.STRICT, access = access)

        assertThrows(LocalNetworkUnsupportedException::class.java) { syncDns.lookup("syncbox") }
        access.setSyncAllowed(true)
        // SYNC passes while allowed …
        assertThat(syncDns.lookup("syncbox")).containsExactly(privateIp)
        // … and every other client still fails fast (01 Interceptors).
        assertThrows(LocalNetworkUnsupportedException::class.java) { strictDns.lookup("syncbox") }
    }

    @Test
    fun `sync client end to end reaches a private host once allowed`() {
        // A private literal is rejected while disallowed …
        val access = LocalNetworkAccess()
        val clients = newNetworkClients(access = access, platform = android37)
        val url = "http://10.1.2.3/"
        val sync = clients[ch.lkmc.neutrodyne.core.common.HttpClientKind.SYNC]
        assertThrows(LocalNetworkUnsupportedException::class.java) {
            sync.newCall(Request.Builder().url(url).build()).execute()
        }
        // … and passes the guard once the gate grants access. The extra interceptor answers the
        // call before the connect, so the test never touches the network.
        access.setSyncAllowed(true)
        val shortCircuit =
            okhttp3.Interceptor { chain ->
                okhttp3.Response
                    .Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .build()
            }
        val allowed = sync.newBuilder().addInterceptor(shortCircuit).build()
        allowed.newCall(Request.Builder().url(url).build()).execute().close()
    }
}
