// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.model.NetError
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
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 03 implementation note 2026-11-02: the add pipeline's automatic fetches never reach a local
 * destination — a shared page cannot turn alternates, candidate redirects or its own 302 into a
 * LAN request — while a *typed* LAN URL still fetches (PLAN's LAN rule is Android-only).
 * [server] answers the fake public [TEST_HOST] through the stub DNS below; [lan] is a second
 * server bound to `127.0.0.2` at [server]'s port, standing in for a LAN destination — so a
 * public host spelled [LAN_HOST] that resolves there simulates a DNS-rebound LAN peer, and
 * `lan.requestCount == 0` proves a refused destination was never dialed.
 */
class AddLocalDestinationTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private val lan = MockWebServer()

    private val testDns =
        Dns { host ->
            if (host == LAN_HOST || host.endsWith(".$LAN_HOST")) {
                listOf(InetAddress.getByName(LAN_ADDR))
            } else if (host == TEST_HOST || host.endsWith(".$TEST_HOST")) {
                listOf(InetAddress.getByName("127.0.0.1"))
            } else {
                Dns.SYSTEM.lookup(host)
            }
        }

    private companion object {
        const val TEST_HOST = "page.test"
        const val LAN_HOST = "lan.test"
        const val LAN_ADDR = "127.0.0.2"
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
        lan.start(InetAddress.getByName(LAN_ADDR), server.port)
        bundle =
            newResolver(
                root,
                db,
                clock,
                networkClients =
                    newNetworkClients(
                        baseDns = testDns,
                        // Only the LAN recorder's address is "local" here: `server`'s stand-in
                        // host must stay reachable for the typed/probe paths that still fetch.
                        isLocalPeer = { peer -> peer.address?.hostAddress == LAN_ADDR },
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
    private fun lanPeerUrl(path: String) = lan.url(path).newBuilder().host(LAN_HOST).build().toString()

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

                            "/candidate.xml" -> mockResponse(302, "", "Location" to lanPeerUrl("/"))

                            else -> notFound(request)
                        }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(0, lan.requestCount)
        }

    @Test fun directoryPreviewResolvingToLanPeerIsRefused() =
        runBlocking {
            lan.enqueue(mockResponse(body = rssBody(title = "LAN honeypot")))
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<Outcome.Failure>(bundle.resolver.preview(lanPeerUrl("/feed"))).error,
            )
            assertEquals(0, lan.requestCount)
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
                                        "href='http://%31%32%37.0.0.2:${server.port}/action'></head></html>",
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
                                        "href='http://１２７.０.０.２:${server.port}/action'></head></html>",
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
        }
}
