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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The M7 actual-peer guard end to end (03 note 2026-11-02): [server] is the public stand-in on
 * `127.0.0.1`, as is [lan] on a separate port. Named DNS answers and the LAN port distinguish
 * the recorder without requiring a second loopback alias on macOS. A marked call
 * must fail before the request is written; `lan.requestCount == 0` proves the refusal.
 */
class PreConfirmationPeerGuardTest {
    private val server = MockWebServer()
    private val lan = MockWebServer()
    private val lanAddress = InetAddress.getByAddress(LAN_HOST, byteArrayOf(127, 0, 0, 1))

    private fun isLocalPeer(peer: InetSocketAddress) = peer.hostString == LAN_HOST || peer.port == lan.port

    @Before fun setup() {
        server.start()
        lan.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun teardown() {
        server.close()
        lan.close()
    }

    private fun clients(): NetworkClients =
        newNetworkClients(
            baseDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) },
            isLocalPeer = ::isLocalPeer,
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

    @Test fun multipleChoicesRedirectToLocalLiteralOpensNoSocket() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { recorder ->
            val clients =
                newNetworkClients(
                    baseDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) },
                    isLocalPeer = { it.port == recorder.localPort },
                )
            server.enqueue(mockResponse(300, "", "Location" to "http://127.0.0.1:${recorder.localPort}/action"))
            assertFailsWith<LocalNetworkUnsupportedException> {
                clients.image
                    .newCall(marked(publicUrl("/art")))
                    .execute()
                    .use { }
            }
            assertEquals(1, server.requestCount)
            assertZeroConnections(recorder)
        }
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
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { recorder ->
            val target = "http://redirect-peer.test:${recorder.localPort}/action"
            val clients =
                newNetworkClients(
                    baseDns =
                        Dns { host ->
                            listOf(if (host == "public.test") InetAddress.getByName("127.0.0.1") else lanAddress)
                        },
                    isLocalPeer = ::isLocalPeer,
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
                baseDns = Dns { listOf(InetAddress.getByName("127.0.0.1"), lanAddress) },
                isLocalPeer = ::isLocalPeer,
            )
        server.enqueue(mockResponse(body = "public stand-in"))
        val target =
            server
                .url("/feed")
                .newBuilder()
                .host("mixed.test")
                .build()
        assertFailsWith<LocalNetworkUnsupportedException> {
            clients.image
                .newCall(marked(target))
                .execute()
                .use { }
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
                        listOf(if (lookups.getAndIncrement() == 0) InetAddress.getByName("127.0.0.1") else lanAddress)
                    },
                isLocalPeer = ::isLocalPeer,
            )
        server.enqueue(mockResponse(body = "public stand-in"))
        val target =
            server
                .url("/feed")
                .newBuilder()
                .host("rebound.test")
                .build()
        assertFailsWith<LocalNetworkUnsupportedException> {
            clients.image
                .newCall(marked(target))
                .execute()
                .use { }
        }
        assertEquals(0, server.requestCount)
        assertEquals(0, lan.requestCount)
    }

    @Test fun rebindingBetweenPreflightAndConnectOpensNoSocket() {
        MockWebServer().use { publicServer ->
            publicServer.start()
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { recorder ->
                for (kind in listOf(HttpClientKind.FEED, HttpClientKind.IMAGE)) {
                    val lookups = AtomicInteger()
                    val clients =
                        newNetworkClients(
                            baseDns =
                                Dns {
                                    listOf(
                                        if (lookups.getAndIncrement() ==
                                            0
                                        ) {
                                            InetAddress.getByName("127.0.0.1")
                                        } else {
                                            lanAddress
                                        },
                                    )
                                },
                            isLocalPeer = ::isLocalPeer,
                        )
                    val client = clients[kind]
                    publicServer.enqueue(mockResponse(body = "public stand-in"))
                    val target =
                        publicServer
                            .url("/feed")
                            .newBuilder()
                            .port(recorder.localPort)
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
            clients.image
                .newCall(marked(target))
                .execute()
                .use { }
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
                        listOf(if (lookups.getAndIncrement() == 0) lanAddress else InetAddress.getByName("203.0.113.1"))
                    },
                isLocalPeer = { peer ->
                    peer.hostString == LAN_HOST ||
                        (peer.port == lan.port && peer.address?.isLoopbackAddress == true)
                },
            )
        val target =
            lan
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
            clients.image
                .newCall(marked(target))
                .execute()
                .use { }
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
        const val LAN_HOST = "lan.test"
    }
}
