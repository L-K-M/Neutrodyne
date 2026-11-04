// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.testing.ConnectionCountingSocketFactory
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Dns
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 03 implementation note 2026-11-02: the add pipeline's automatic fetches never reach a local
 * destination — a shared page cannot turn alternates, candidate redirects or its own 302 into a
 * LAN request — while a *typed* LAN URL still fetches (PLAN's LAN rule is Android-only).
 * [server] answers the fake public [TEST_HOST] through the stub DNS below; [lan] is a second
 * server bound to `127.0.0.1` on its own port, standing in for a LAN destination. A named
 * DNS answer distinguishes its peer during route selection without a macOS loopback alias, so a
 * public host spelled [LAN_HOST] that resolves there simulates a DNS-rebound LAN peer, and
 * both the HTTP request count and accepted TCP socket count stay zero.
 */
class AddLocalDestinationTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private val lanConnections = ConnectionCountingSocketFactory()
    private val lan = MockWebServer().apply { serverSocketFactory = lanConnections }

    private val lanAddress = InetAddress.getByAddress(LAN_HOST, byteArrayOf(127, 0, 0, 1))

    private val testDns =
        Dns { host ->
            if (host == LAN_HOST || host.endsWith(".$LAN_HOST")) {
                listOf(lanAddress)
            } else if (host == TEST_HOST || host.endsWith(".$TEST_HOST")) {
                listOf(InetAddress.getByName("127.0.0.1"))
            } else {
                Dns.SYSTEM.lookup(host)
            }
        }

    private companion object {
        const val TEST_HOST = "page.test"
        const val LAN_HOST = "lan.test"
    }

    private val clock = TestClock()
    private val db = newDb(clock)
    private val root =
        kotlin.io.path
            .createTempDirectory("nd-local")
            .toFile()
    private lateinit var bundle: ResolverBundle

    @Before fun setup() {
        server.start()
        lan.start(InetAddress.getByName("127.0.0.1"), 0)
        bundle =
            newResolver(
                root,
                db,
                clock,
                networkClients =
                    newNetworkClients(
                        baseDns = testDns,
                        // DNS names cover route checks (port zero); the port covers connected peers.
                        isLocalPeer = { peer -> peer.hostString == LAN_HOST || peer.port == lan.port },
                    ),
            )
    }

    @After fun cleanup() {
        bundle.close()
        lan.close()
        db.close()
        root.deleteRecursively()
    }

    private fun url(
        path: String,
        host: String = TEST_HOST,
    ) = server
        .url(path)
        .newBuilder()
        .host(host)
        .build()
        .toString()

    private fun notFound(request: RecordedRequest) = mockResponse(404, "no ${request.target}")

    @Test fun oversizedNumericInputDoesNotCrashSharedOrTypedAdd() =
        runBlocking {
            val input = "http://99999999999.0.0.1/feed"
            assertTrue(bundle.resolver.isLocalDestination(input))
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(bundle.resolver.resolveUnconfirmed(input)).error,
            )
            assertIs<AddResolution.Failure>(bundle.resolver.resolve(input))
            assertEquals(0, server.requestCount)
            assertEquals(0, lan.requestCount)
        }

    @Test fun wordpressFallbackCannotReuseConsentForAnotherLocalUrl() =
        runBlocking {
            lan.enqueue(mockResponse(body = rssBody(items = arrayOf("<item><title>Blog post</title></item>"))))
            lan.enqueue(mockResponse(body = rssBody(title = "LAN honeypot", items = arrayOf(rssItem("e")))))
            val result = bundle.resolver.resolve(lan.url("/feed").toString())
            assertEquals(1, lan.requestCount, "Only the exact submitted local URL may be requested")
            assertEquals(1, lanConnections.acceptedConnections)
            assertEquals("/feed", lan.takeRequest().target)
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(result).error,
            )
        }

    @Test fun wordpressFallbackCannotReachAReboundLocalPeer() =
        runBlocking {
            val lookups = AtomicInteger()
            val rebindDns =
                Dns {
                    listOf(if (lookups.getAndIncrement() == 0) InetAddress.getByName("127.0.0.1") else lanAddress)
                }
            // The inferred URL must choose a fresh route after the public feed closes its socket.
            server.enqueue(
                mockResponse(
                    body = rssBody(items = arrayOf("<item><title>Blog post</title></item>")),
                    headers = arrayOf("Connection" to "close"),
                ),
            )
            lan.enqueue(mockResponse(body = rssBody(title = "LAN honeypot", items = arrayOf(rssItem("e")))))
            newResolver(
                root,
                db,
                clock,
                networkClients =
                    newNetworkClients(
                        baseDns = rebindDns,
                        isLocalPeer = { peer -> peer.hostString == LAN_HOST || peer.port == lan.port },
                    ),
            ).use { rebound ->
                val result = rebound.resolver.resolve(url("/feed"))
                assertEquals(1, server.requestCount)
                assertEquals(0, lan.requestCount, "The inferred WordPress URL must not be requested locally")
                assertEquals(0, lanConnections.acceptedConnections)
                assertEquals(
                    AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                    assertIs<AddResolution.Failure>(result).error,
                )
            }
        }

    @Test fun discoveredLanAlternateIsNeverRequested() =
        runBlocking {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.target == "/page") {
                            mockResponse(
                                body =
                                    "<html><head><link rel='alternate' type='application/rss+xml' " +
                                        "href='${lan.url("/action")}'></head></html>",
                            )
                        } else {
                            notFound(request)
                        }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun publicAlternateCannotRedirectToLoopback() =
        runBlocking {
            val feeds =
                server
                    .url("/candidate.xml")
                    .newBuilder()
                    .host("feeds.$TEST_HOST")
                    .build()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.target) {
                            "/page" -> {
                                mockResponse(
                                    body =
                                        "<html><head><link rel='alternate' type='application/rss+xml' " +
                                            "href='$feeds'></head></html>",
                                )
                            }

                            "/candidate.xml" -> {
                                mockResponse(302, "", "Location" to "${lan.url("/")}")
                            }

                            else -> {
                                notFound(request)
                            }
                        }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun publicPageCannotRedirectToLocal() =
        runBlocking {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.target) {
                            "/page" -> mockResponse(302, "", "Location" to "${lan.url("/feed")}")
                            else -> notFound(request)
                        }
                }
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun directoryPreviewNeverFetchesLocalTarget() =
        runBlocking {
            lan.enqueue(mockResponse(body = rssBody(title = "Private", items = arrayOf(rssItem("e1")))))
            val result = bundle.resolver.preview(lan.url("/feed").toString())
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(result).error,
            )
            assertEquals(0, lan.requestCount)
        }

    @Test fun typedLanUrlIsStillFetched() =
        runBlocking {
            server.enqueue(
                mockResponse(body = rssBody(title = "LAN show", items = arrayOf(rssItem("e1")))),
            )
            // server.url() names loopback — textual-local, but the user typed it, so ADD fetches it.
            val preview =
                assertIs<AddResolution.Feed>(
                    bundle.resolver.resolve(server.url("/feed.xml").toString()),
                ).preview
            assertEquals("LAN show", preview.title)
        }

    // --- DNS-resolved local peers (03 note 2026-11-02): the spelling passes the string check,
    // so only the connected peer's address can refuse the request. ------------------------------

    /** A `lan.test` URL that resolves to the LAN recorder — public spelling, local peer. */
    private fun lanPeerUrl(path: String) =
        lan
            .url(path)
            .newBuilder()
            .host(LAN_HOST)
            .build()
            .toString()

    @Test fun alternateResolvingToLanPeerIsNeverRequested() =
        runBlocking {
            lan.enqueue(mockResponse(body = rssBody(title = "LAN honeypot")))
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.target == "/page") {
                            mockResponse(
                                body =
                                    "<html><head><link rel='alternate' type='application/rss+xml' " +
                                        "href='${lanPeerUrl("/action")}'></head></html>",
                            )
                        } else {
                            notFound(request)
                        }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun pageRedirectResolvingToLanPeerIsRefused() =
        runBlocking {
            lan.enqueue(mockResponse(body = rssBody(title = "LAN honeypot")))
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.target) {
                            "/page" -> mockResponse(302, "", "Location" to lanPeerUrl("/feed"))
                            else -> notFound(request)
                        }
                }
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun candidateRedirectResolvingToLanPeerIsRefused() =
        runBlocking {
            lan.enqueue(mockResponse(body = rssBody(title = "LAN honeypot")))
            val feeds =
                server
                    .url("/candidate.xml")
                    .newBuilder()
                    .host("feeds.$TEST_HOST")
                    .build()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.target) {
                            "/page" -> {
                                mockResponse(
                                    body =
                                        "<html><head><link rel='alternate' type='application/rss+xml' " +
                                            "href='$feeds'></head></html>",
                                )
                            }

                            "/candidate.xml" -> {
                                mockResponse(302, "", "Location" to lanPeerUrl("/"))
                            }

                            else -> {
                                notFound(request)
                            }
                        }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun directoryPreviewResolvingToLanPeerIsRefused() =
        runBlocking {
            lan.enqueue(mockResponse(body = rssBody(title = "LAN honeypot")))
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(bundle.resolver.preview(lanPeerUrl("/feed"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun directoryLiteralLocalPreviewIsRefused() =
        runBlocking {
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(
                    bundle.resolver.preview(lan.url("/feed").toString()),
                ).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun directoryPreviewCannotRedirectToLocal() =
        runBlocking {
            server.enqueue(mockResponse(302, "", "Location" to lanPeerUrl("/feed")))
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(bundle.resolver.preview(url("/redirect"))).error,
            )
            assertEquals(1, server.requestCount)
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun unconfirmedFirstHostResolvingLocalIsRefused() =
        runBlocking {
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(bundle.resolver.resolveUnconfirmed(lanPeerUrl("/feed"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun encodedLanAlternateIsNeverRequested() =
        runBlocking {
            lan.enqueue(mockResponse(body = rssBody(title = "LAN honeypot")))
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.target == "/page") {
                            mockResponse(
                                body =
                                    "<html><head><link rel='alternate' type='application/rss+xml' " +
                                        "href='http://%31%32%37.0.0.1:${lan.port}/action'></head></html>",
                            )
                        } else {
                            notFound(request)
                        }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }

    @Test fun fullwidthLanAlternateIsNeverRequested() =
        runBlocking {
            lan.enqueue(mockResponse(body = rssBody(title = "LAN honeypot")))
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.target == "/page") {
                            mockResponse(
                                body =
                                    "<html><head><link rel='alternate' type='application/rss+xml' " +
                                        "href='http://１２７.０.０.１:${lan.port}/action'></head></html>",
                            )
                        } else {
                            notFound(request)
                        }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(0, lan.requestCount)
            assertEquals(0, lanConnections.acceptedConnections)
        }
}
