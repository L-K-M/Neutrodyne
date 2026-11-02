// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.model.PRE_CONFIRMATION_HEADER
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The M7 actual-peer guard end to end (03 note 2026-11-02): [server] is the public stand-in on
 * `127.0.0.1`, [lan] the LAN recorder on `127.0.0.2`, and the seam narrows "local peer" to the
 * recorder's address — exactly the split the add pipeline's fake-DNS tests use. A marked call
 * must fail before the request is written; `lan.requestCount == 0` proves the refusal.
 */
class PreConfirmationPeerGuardTest {
    private val server = MockWebServer()
    private val lan = MockWebServer()

    @Before fun setup() {
        server.start()
        lan.start(InetAddress.getByName(LAN_ADDR), server.port)
    }

    @After fun teardown() {
        server.close()
        lan.close()
    }

    private fun clients(): NetworkClients =
        newNetworkClients(
            baseDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) },
            isLocalPeer = { peer -> peer.address?.hostAddress == LAN_ADDR },
        )

    private fun publicUrl(path: String): okhttp3.HttpUrl =
        server
            .url(path)
            .newBuilder()
            .host("public.test")
            .build()

    private fun marked(url: okhttp3.HttpUrl): Request =
        Request
            .Builder()
            .url(url)
            .header(PRE_CONFIRMATION_HEADER, "1")
            .build()

    @Test fun unmarkedRequestToLoopbackPeerSucceeds() {
        server.enqueue(mockResponse(body = "public"))
        clients().image.newCall(Request.Builder().url(server.url("/feed")).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun markedRequestToLocalPeerIsRefused() {
        assertFailsWith<LocalNetworkUnsupportedException> {
            clients().image.newCall(marked(lan.url("/feed"))).execute()
        }
        assertEquals(0, lan.requestCount)
    }

    @Test fun markedFollowUpToLocalPeerIsRefused() {
        server.enqueue(mockResponse(302, "", "Location" to lan.url("/").toString()))
        assertFailsWith<LocalNetworkUnsupportedException> {
            clients().image.newCall(marked(publicUrl("/page"))).execute()
        }
        assertEquals(1, server.requestCount)
        assertEquals(0, lan.requestCount)
    }

    @Test fun markedNonCanonicalLocalUrlsAreRefusedOnFeedAndImageClients() {
        val clients = newNetworkClients()
        for (client in listOf(clients.feed, clients.image)) {
            for (prefix in listOf("http:/", "http:\\", "http:\\\\", "http:")) {
                val request =
                    Request
                        .Builder()
                        .url("${prefix}127.0.0.1:${server.port}/action")
                        .header(PRE_CONFIRMATION_HEADER, "1")
                        .build()
                assertFailsWith<LocalNetworkUnsupportedException> {
                    client.newCall(request).execute()
                }
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun localDnsAnswerOpensNoSocketOnFeedOrImage() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { recorder ->
            val clients = newNetworkClients(baseDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) })
            for (client in listOf(clients.feed, clients.image)) {
                val request =
                    Request
                        .Builder()
                        .url("http://local-peer.test:${recorder.localPort}/action")
                        .header(PRE_CONFIRMATION_HEADER, "1")
                        .build()
                assertFailsWith<LocalNetworkUnsupportedException> { client.newCall(request).execute() }
            }
            assertZeroConnections(recorder)
        }
    }

    @Test fun localImageRedirectOpensNoSocket() {
        ServerSocket(0, 1, InetAddress.getByName(LAN_ADDR)).use { recorder ->
            val target = "http://redirect-peer.test:${recorder.localPort}/action"
            val clients =
                newNetworkClients(
                    baseDns =
                        Dns { host ->
                            listOf(InetAddress.getByName(if (host == "public.test") "127.0.0.1" else LAN_ADDR))
                        },
                    isLocalPeer = { peer -> peer.address?.hostAddress == LAN_ADDR },
                )
            server.enqueue(mockResponse(302, "", "Location" to target))
            val publicUrl =
                server
                    .url("/art")
                    .newBuilder()
                    .host("public.test")
                    .build()
            assertFailsWith<LocalNetworkUnsupportedException> {
                clients.image.newCall(marked(publicUrl)).execute()
            }
            assertEquals(1, server.requestCount)
            assertZeroConnections(recorder)
        }
    }

    @Test fun mixedDnsAnswersCannotOpenTheLocalRecorder() {
        val clients =
            newNetworkClients(
                baseDns = Dns { listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName(LAN_ADDR)) },
                isLocalPeer = { peer -> peer.address?.hostAddress == LAN_ADDR },
            )
        server.enqueue(mockResponse(body = "public stand-in"))
        val target =
            server
                .url("/feed")
                .newBuilder()
                .host("mixed.test")
                .build()
        assertFailsWith<LocalNetworkUnsupportedException> {
            clients.image.newCall(marked(target)).execute().use { }
        }
        assertEquals(0, server.requestCount)
        assertEquals(0, lan.requestCount)
    }

    @Test fun routeDnsRejectsAnAnswerChangedAfterPreflight() {
        val lookups = AtomicInteger()
        val clients =
            newNetworkClients(
                baseDns =
                    Dns {
                        listOf(InetAddress.getByName(if (lookups.getAndIncrement() == 0) "127.0.0.1" else LAN_ADDR))
                    },
                isLocalPeer = { peer -> peer.address?.hostAddress == LAN_ADDR },
            )
        server.enqueue(mockResponse(body = "public stand-in"))
        val target =
            server
                .url("/feed")
                .newBuilder()
                .host("rebound.test")
                .build()
        assertFailsWith<LocalNetworkUnsupportedException> {
            clients.image.newCall(marked(target)).execute().use { }
        }
        assertEquals(0, server.requestCount)
        assertEquals(0, lan.requestCount)
    }

    @Test fun rebindingBetweenPreflightAndConnectOpensNoSocket() {
        MockWebServer().use { publicServer ->
            publicServer.start()
            ServerSocket(publicServer.port, 1, InetAddress.getByName(LAN_ADDR)).use { recorder ->
                for (kind in listOf(HttpClientKind.FEED, HttpClientKind.IMAGE)) {
                    val lookups = AtomicInteger()
                    val clients =
                        newNetworkClients(
                            baseDns =
                                Dns {
                                    listOf(
                                        InetAddress.getByName(
                                            if (lookups.getAndIncrement() == 0) "127.0.0.1" else LAN_ADDR,
                                        ),
                                    )
                                },
                            isLocalPeer = { peer -> peer.address?.hostAddress == LAN_ADDR },
                        )
                    val client = clients[kind]
                    publicServer.enqueue(mockResponse(body = "public stand-in"))
                    val target =
                        publicServer
                            .url("/feed")
                            .newBuilder()
                            .host("rebound.test")
                            .build()
                    assertFailsWith<LocalNetworkUnsupportedException> {
                        client.newCall(marked(target)).execute().use { }
                    }
                }
                assertEquals(0, publicServer.requestCount)
                assertZeroConnections(recorder)
            }
        }
    }

    @Test fun markedFailureDoesNotRestrictTheNextExplicitCall() {
        val clients = newNetworkClients(baseDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        val target =
            server
                .url("/feed")
                .newBuilder()
                .host("local-peer.test")
                .build()
        assertFailsWith<LocalNetworkUnsupportedException> {
            clients.image.newCall(marked(target)).execute().use { }
        }
        server.enqueue(mockResponse(body = "explicit"))
        clients.image.newCall(Request.Builder().url(target).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun pooledLocalPeerIsStillRefusedAfterPublicPreflight() {
        val lookups = AtomicInteger()
        val clients =
            newNetworkClients(
                baseDns =
                    Dns {
                        listOf(InetAddress.getByName(if (lookups.getAndIncrement() == 0) LAN_ADDR else "127.0.0.1"))
                    },
                isLocalPeer = { peer -> peer.address?.hostAddress == LAN_ADDR },
            )
        val target =
            server
                .url("/feed")
                .newBuilder()
                .host("pooled.test")
                .build()
        lan.enqueue(mockResponse(body = "explicit"))
        lan.enqueue(mockResponse(body = "must not be requested"))
        clients.image.newCall(Request.Builder().url(target).build()).execute().use {
            assertEquals("explicit", it.body.string())
        }
        assertFailsWith<LocalNetworkUnsupportedException> {
            clients.image.newCall(marked(target)).execute().use { }
        }
        assertEquals(1, lan.requestCount)
        assertEquals(0, server.requestCount)
    }

    private fun assertZeroConnections(recorder: ServerSocket) {
        recorder.soTimeout = 100
        var count = 0
        while (true) {
            try {
                recorder.accept().use { count++ }
            } catch (_: SocketTimeoutException) {
                break
            }
        }
        assertEquals(0, count)
    }

    @Test fun markerNeverReachesTheWire() {
        server.enqueue(mockResponse(body = "public"))
        clients().image.newCall(marked(publicUrl("/feed"))).execute().use {
            assertEquals(200, it.code)
        }
        val recorded = server.takeRequest()
        assertNull(recorded.headers[PRE_CONFIRMATION_HEADER])
    }

    private companion object {
        const val LAN_ADDR = "127.0.0.2"
    }
}
