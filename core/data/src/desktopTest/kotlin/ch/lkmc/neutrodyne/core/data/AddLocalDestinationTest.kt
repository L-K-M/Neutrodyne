// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

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
 * server whose `localhost` URLs stand in for LAN destinations, so `requestCount == 0` proves a
 * refused destination was never dialed.
 */
class AddLocalDestinationTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private val lan = MockWebServer()

    private val testDns =
        Dns { host ->
            if (host == TEST_HOST || host.endsWith(".$TEST_HOST")) {
                listOf(InetAddress.getByName("127.0.0.1"))
            } else {
                Dns.SYSTEM.lookup(host)
            }
        }

    private companion object {
        const val TEST_HOST = "page.test"
    }

    private val clock = TestClock()
    private val db = newDb(clock)
    private val root =
        kotlin.io.path
            .createTempDirectory("nd-local")
            .toFile()
    private lateinit var bundle: ResolverBundle

    @Before fun setup() {
        lan.start()
        bundle =
            newResolver(
                root,
                db,
                clock,
                networkClients = newNetworkClients(baseDns = testDns),
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
}
