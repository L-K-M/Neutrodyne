// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.model.PRE_CONFIRMATION_HEADER
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
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
            clients().image.newCall(marked(server.url("/page"))).execute()
        }
        assertEquals(1, server.requestCount)
        assertEquals(0, lan.requestCount)
    }

    @Test fun markedNonCanonicalLocalUrlsAreRefusedOnFeedAndImageClients() {
        val clients = newNetworkClients()
        for (client in listOf(clients.feed, clients.image)) {
            for (prefix in listOf("http:/", "http:\\\\", "http:")) {
                val request = Request.Builder()
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

    @Test fun markerNeverReachesTheWire() {
        server.enqueue(mockResponse(body = "public"))
        clients().image.newCall(marked(server.url("/feed"))).execute().use {
            assertEquals(200, it.code)
        }
        val recorded = server.takeRequest()
        assertNull(recorded.headers[PRE_CONFIRMATION_HEADER])
    }

    private companion object {
        const val LAN_ADDR = "127.0.0.2"
    }
}
