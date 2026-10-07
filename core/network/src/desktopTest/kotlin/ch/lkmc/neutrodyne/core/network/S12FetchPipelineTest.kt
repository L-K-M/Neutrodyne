// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.Origin
import com.google.common.truth.Truth.assertThat
import io.ktor.client.plugins.sse.serverSentEventsSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * The S12 matrix (01 Spikes, 03 Fetch pipeline): Ktor's OkHttp engine over the island client as
 * `preconfigured`, exercised end-to-end against MockWebServer 5.5.0. Every case runs through the
 * real `OkHttpNeutrodyneHttpClients` factory so engine, interceptors and DNS chain are
 * production-shaped.
 */
class S12FetchPipelineTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val readBuffer = ByteArray(16 * 1024)

    @Test
    fun `conditional get sends validators and 304 reaches the caller`() =
        runBlocking {
            server.enqueue(mockResponse(code = 304, body = ""))
            val clients = newHttpClients()
            clients.use { c ->
                val response =
                    c.client(HttpClientKind.FEED).get(server.url("/feed.xml").toString()) {
                        header("If-None-Match", "\"etag-1\"")
                        header("If-Modified-Since", "Wed, 21 Oct 2015 07:28:00 GMT")
                    }
                assertThat(response.status).isEqualTo(HttpStatusCode.NotModified)
                response.bodyAsText()
            }
            val recorded = server.takeRequest()
            assertThat(recorded.headers["If-None-Match"]).isEqualTo("\"etag-1\"")
            assertThat(recorded.headers["If-Modified-Since"]).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT")
        }

    @Test
    fun `feed sees every redirect hop for the manual chain`() =
        runBlocking {
            server.enqueue(mockResponse(code = 301, body = "", "Location" to "/b"))
            server.enqueue(mockResponse(code = 308, body = "", "Location" to "/c"))
            server.enqueue(mockResponse(body = "final"))
            val clients = newHttpClients()
            clients.use { c ->
                val feed = c.client(HttpClientKind.FEED)
                val first = feed.get(server.url("/a").toString())
                assertThat(first.status).isEqualTo(HttpStatusCode.MovedPermanently)
                assertThat(first.headers["Location"]).isEqualTo("/b")

                val second = feed.get(server.url("/b").toString())
                assertThat(second.status).isEqualTo(HttpStatusCode.PermanentRedirect)
                assertThat(second.headers["Location"]).isEqualTo("/c")

                val third = feed.get(server.url("/c").toString())
                assertThat(third.status).isEqualTo(HttpStatusCode.OK)
                assertThat(third.bodyAsText()).isEqualTo("final")
            }
            assertThat(server.requestCount).isEqualTo(3)
        }

    @Test
    fun `status codes surface instead of throwing`() =
        runBlocking {
            server.enqueue(mockResponse(code = 404, body = "nope"))
            server.enqueue(mockResponse(code = 500, body = "boom"))
            val clients = newHttpClients()
            clients.use { c ->
                assertThat(c.client(HttpClientKind.FEED).get(server.url("/404").toString()).status)
                    .isEqualTo(HttpStatusCode.NotFound)
                assertThat(c.client(HttpClientKind.API).get(server.url("/500").toString()).status)
                    .isEqualTo(HttpStatusCode.InternalServerError)
            }
        }

    @Test
    fun `api follows redirects while feed does not`() =
        runBlocking {
            server.enqueue(mockResponse(code = 302, body = "", "Location" to "/landed"))
            server.enqueue(mockResponse(body = "landed"))
            server.enqueue(mockResponse(code = 302, body = "", "Location" to "/landed2"))
            val clients = newHttpClients()
            clients.use { c ->
                assertThat(c.client(HttpClientKind.API).get(server.url("/a").toString()).status)
                    .isEqualTo(HttpStatusCode.OK)
                assertThat(c.client(HttpClientKind.FEED).get(server.url("/b").toString()).status)
                    .isEqualTo(HttpStatusCode.Found)
            }
        }

    @Test
    fun `streaming sha256 over the response body`() =
        runBlocking {
            val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
            server.enqueue(
                mockwebserver3.MockResponse
                    .Builder()
                    .code(200)
                    .body(Buffer().write(payload))
                    .build(),
            )
            val clients = newHttpClients()
            clients.use { c ->
                c.client(HttpClientKind.FEED).prepareGet(server.url("/big").toString()).execute { response ->
                    val hashing = HashingSink.sha256(blackholeSink())
                    val buffered = hashing.buffer()
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val read = channel.readAvailable(readBuffer, 0, readBuffer.size)
                        if (read == -1) break
                        buffered.write(readBuffer, 0, read)
                    }
                    buffered.flush()
                    val expected = HashingSink.sha256(blackholeSink())
                    expected.buffer().also {
                        it.write(payload)
                        it.flush()
                    }
                    assertThat(hashing.hash.hex()).isEqualTo(expected.hash.hex())
                }
            }
        }

    @Test
    fun `the body can be capped mid-stream`() =
        runBlocking {
            // 03's 32 MB cap: read only up to the cap, then abandon the body. The point is that the
            // engine streams — the caller can stop without the client first buffering everything.
            val cap = 32L * 1024 * 1024
            server.enqueue(
                mockwebserver3.MockResponse
                    .Builder()
                    .code(200)
                    .body(Buffer().write(ByteArray(cap.toInt() + 1024 * 1024)))
                    .build(),
            )
            var read = 0L
            val clients = newHttpClients()
            clients.use { c ->
                c.client(HttpClientKind.FEED).prepareGet(server.url("/huge").toString()).execute { response ->
                    val channel = response.bodyAsChannel()
                    while (read <= cap) {
                        val n = channel.readAvailable(readBuffer, 0, readBuffer.size)
                        if (n == -1) break
                        read += n
                    }
                }
            }
            // Stopped at the cap, far short of the 33 MB body.
            assertThat(read).isGreaterThan(cap)
            assertThat(read).isLessThan(cap + 1024 * 1024)
        }

    @Test
    fun `cancelling a request closes the socket`() =
        runBlocking {
            // MockWebServer can't observe a reset, so this case uses a raw server: it answers, then
            // reads — a cancelled client tears the socket down and the server's read() hits EOF.
            val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val sawClose = CountDownLatch(1)
            val serverThread =
                thread {
                    try {
                        serverSocket.accept().use { socket ->
                            socket.soTimeout = 15_000
                            val input = socket.getInputStream()
                            val head = ByteArray(8192)
                            var used = 0
                            while (!String(head, 0, used).contains("\r\n\r\n")) {
                                val n = input.read(head, used, head.size - used)
                                if (n == -1) error("client closed before headers")
                                used += n
                            }
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".toByteArray())
                                flush()
                            }
                            try {
                                while (input.read() != -1) Unit
                            } catch (_: IOException) {
                                // A reset is fine — the socket is gone either way.
                            }
                            sawClose.countDown()
                        }
                    } finally {
                        serverSocket.close()
                    }
                }

            val clients = newHttpClients()
            try {
                val headersReceived = CompletableDeferred<Unit>()
                val job =
                    launch(Dispatchers.IO) {
                        clients
                            .client(HttpClientKind.FEED)
                            .prepareGet("http://127.0.0.1:${serverSocket.localPort}/")
                            .execute { response ->
                                // Headers arrived — the body stalls on a socket that never closes cleanly.
                                headersReceived.complete(Unit)
                                response.bodyAsChannel().awaitContent()
                            }
                    }
                withTimeout(10_000) { headersReceived.await() }
                job.cancelAndJoin()
                assertThat(sawClose.await(15, TimeUnit.SECONDS)).isTrue()
            } finally {
                clients.closeAll()
                serverSocket.close()
                serverThread.join(TimeUnit.SECONDS.toMillis(20))
            }
        }

    @Test
    fun `download sends range, if-range and identity encoding`() =
        runBlocking {
            val body = "0123456789abcdef"
            server.enqueue(
                mockResponse(
                    code = 206,
                    body = body.substring(0, 10),
                    "Content-Range" to "bytes 0-9/16",
                ),
            )
            val clients = newHttpClients()
            clients.use { c ->
                val response =
                    c.client(HttpClientKind.DOWNLOAD).get(server.url("/part").toString()) {
                        header("Range", "bytes=0-9")
                        header("If-Range", "\"etag-9\"")
                    }
                assertThat(response.status.value).isEqualTo(206)
                assertThat(response.bodyAsText()).isEqualTo("0123456789")
            }
            val recorded = server.takeRequest()
            assertThat(recorded.headers["Range"]).isEqualTo("bytes=0-9")
            assertThat(recorded.headers["If-Range"]).isEqualTo("\"etag-9\"")
            assertThat(recorded.headers["Accept-Encoding"]).isEqualTo("identity")
        }

    @Test
    fun `feed requests transparently decompress a gzip body`() =
        runBlocking {
            val text = "feed body that was gzipped by the server"
            val gzipped =
                ByteArrayOutputStream()
                    .also { out ->
                        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
                    }.toByteArray()
            server.enqueue(
                mockwebserver3.MockResponse
                    .Builder()
                    .code(200)
                    .headers(headersOf("Content-Encoding", "gzip"))
                    .body(Buffer().write(gzipped))
                    .build(),
            )
            val clients = newHttpClients()
            clients.use { c ->
                val response = c.client(HttpClientKind.FEED).get(server.url("/feed").toString())
                assertThat(response.bodyAsText()).isEqualTo(text)
            }
            // The island asked for gzip, and the caller never saw Content-Encoding.
            assertThat(server.takeRequest().headers["Accept-Encoding"]).isEqualTo("gzip")
        }

    @Test
    fun `sse streams events on the sync client`() =
        runBlocking {
            server.enqueue(
                mockwebserver3.MockResponse
                    .Builder()
                    .code(200)
                    .headers(headersOf("Content-Type", "text/event-stream"))
                    .body("data: first\n\n: heartbeat\n\ndata: second\n\n")
                    .build(),
            )
            val clients = newHttpClients()
            clients.use { c ->
                val session =
                    c
                        .client(HttpClientKind.SYNC)
                        .serverSentEventsSession(server.url("/events").toString())
                val events = withTimeout(10_000) { session.incoming.take(2).toList() }
                assertThat(events.map { it.data }).containsExactly("first", "second").inOrder()
            }
        }

    @Test
    fun `the auth interceptor reaches ktor requests on the same origin only`() =
        runBlocking {
            val second = MockWebServer()
            second.start()
            try {
                second.enqueue(mockResponse(body = "landed"))
                server.enqueue(mockResponse(code = 302, body = "", "Location" to second.url("/landed").toString()))
                val credentials =
                    CredentialLookup { origin ->
                        if (origin == Origin("http", "localhost", server.port)) "Basic feed-user" else null
                    }
                val clients = newHttpClients(newNetworkClients(credentials))
                clients.use { c ->
                    // API follows the redirect — the credential must not cross to the other origin.
                    c.client(HttpClientKind.API).get(server.url("/protected").toString())
                }
                assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Basic feed-user")
                assertThat(second.takeRequest().headers["Authorization"]).isNull()
            } finally {
                second.close()
            }
        }

    @Test
    fun `requests from different kinds share the connection pool`() =
        runBlocking {
            server.enqueue(mockResponse())
            server.enqueue(mockResponse())
            val clients = newHttpClients()
            clients.use { c ->
                c.client(HttpClientKind.FEED).get(server.url("/a").toString()).bodyAsText()
                c.client(HttpClientKind.API).get(server.url("/b").toString()).bodyAsText()
            }
            val first = server.takeRequest()
            val second = server.takeRequest()
            // Same socket = the derived clients really do share the island's connection pool.
            assertThat(first.connectionIndex).isEqualTo(second.connectionIndex)
        }

    private suspend fun NeutrodyneHttpClients.use(block: suspend (NeutrodyneHttpClients) -> Unit) {
        try {
            block(this)
        } finally {
            closeAll()
        }
    }
}
