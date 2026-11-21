// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.server.ServerFeedFetcher.ServerFeedFetch
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ServerFeedFetcherTest {
    @Test
    fun conditionalAndRedirectHeaders() =
        runBlocking {
            val requests = mutableListOf<Request>()
            val redirect = "https://host.example/rss"
            val fetcher =
                fetcher { request ->
                    requests += request
                    when (request.url.host) {
                        "podcast.example" -> response(request, 302).header("Location", redirect).build()
                        else -> response(request, 200, "<rss/>").header("ETag", "final").build()
                    }
                }
            val changed =
                assertIs<ServerFeedFetch.Changed>(
                    fetcher.fetch("https://podcast.example/rss", "Basic example", "original", null),
                )
            assertEquals("https://host.example/rss", changed.finalUrl)
            assertEquals("<rss/>", changed.body.decodeToString())
            assertNull(changed.etag)
            assertNull(changed.permanentUrl)
            assertEquals("Basic example", requests.first().header("Authorization"))
            assertEquals("original", requests.first().header("If-None-Match"))
            assertNull(requests.last().header("Authorization"))
            assertNull(requests.last().header("If-None-Match"))
        }

    @Test
    fun onlyLeadingPermanentRedirectsMoveTheSubscription() =
        runBlocking {
            val publisher =
                fetcher { request ->
                    when (request.url.encodedPath) {
                        "/feed.xml" -> response(request, 301).header("Location", "/new").build()
                        "/new" -> response(request, 302).header("Location", "/regional").build()
                        else -> response(request, 200, "<rss/>").build()
                    }
                }
            val result = assertIs<ServerFeedFetch.Changed>(publisher.fetch(URL, null, null, null))
            assertEquals("https://podcast.example/new", result.permanentUrl)
            assertEquals("https://podcast.example/regional", result.finalUrl)
        }

    @Test
    fun emptyPollAndNonFeedResponses() =
        runBlocking {
            val conditional = fetcher { response(it, 304).build() }
            assertEquals(ServerFeedFetch.Unchanged, conditional.fetch(URL, null, "validator", null))
            assertNull(conditional.fetch(URL, null, null, null))
            val html = fetcher { response(it, 200, "<html/>").build() }
            assertNull(html.fetch(URL, null, null, null))
        }

    @Test
    fun localAndDowngradedRedirectsStopBeforeSecondRequest() =
        runBlocking {
            for (destination in listOf("http://public.example/feed", "https://127.0.0.1/", "https://[::1]/")) {
                var calls = 0
                val fetcher =
                    fetcher {
                        calls++
                        response(it, 307).header("Location", destination).build()
                    }
                assertNull(fetcher.fetch(URL, null, null, null))
                assertEquals(1, calls)
            }
        }

    @Test
    fun dnsRejectsMixedPublicAndLocalAnswers() {
        val dns =
            FeedDns(
                object : Dns {
                    override fun lookup(hostname: String) =
                        listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("169.254.1.1"))
                },
            )
        assertFailsWith<UnknownHostException> { dns.lookup("publisher.example") }
    }

    @Test
    fun dnsRejectsScopedLinkLocalAnswers() {
        val bytes = InetAddress.getByName("fe80::1").address
        val scoped = java.net.Inet6Address.getByAddress(null, bytes, 4)
        val dns =
            FeedDns(
                object : Dns {
                    override fun lookup(hostname: String) = listOf(scoped)
                },
            )
        assertFailsWith<UnknownHostException> { dns.lookup("publisher.example") }
    }

    private fun fetcher(handler: (Request) -> Response) =
        HttpServerFeedFetcher(OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build())

    private fun response(
        request: Request,
        code: Int,
        body: String = "",
    ) = Response
        .Builder()
        .protocol(
            Protocol.HTTP_1_1,
        ).request(request)
        .code(code)
        .message("")
        .body(body.toResponseBody())

    private companion object {
        const val URL = "https://podcast.example/feed.xml"
    }
}
