// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [LoopbackMediaFixture] over real loopback HTTP (MD3 AC2): the exact `206`/
 * `Content-Range`/`Content-Length`/`Content-Type: audio/mp4` contract the playback
 * `LookAheadLoader` and the download `YouTubeTransferSource` validate, the strict
 * `400`/`416`/`404`/`405` refusals, bounded heads, concurrent range `GET`s, the per-request
 * evidence records, and a clean shutdown with no orphan sockets or threads.
 */
class LoopbackMediaFixtureTest {
    private fun stubHead(): ByteArray =
        // The recorded synthetic m4a's first box: a complete 28-byte ftyp box — the small
        // head stub unit contracts use while the proof supplies the real m4a_lc_2s.m4a.
        HexFormat.of().parseHex("0000001c667479704d344120000002004d34412069736f6d69736f32")

    private fun fixture(): LoopbackMediaFixture = LoopbackMediaFixture(FixtureMediaBytes(stubHead(), TOTAL_BYTES))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class RawResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun contentLength(): Long = headers.getValue("content-length").toLong()

        fun contentRange(): String? = headers["content-range"]
    }

    /** A raw exchange: sends [raw], then reads until the fixture's `Connection: close`. */
    private fun send(
        port: Int,
        raw: String,
    ): RawResponse {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.outputStream.write(raw.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            val all = socket.inputStream.readBytes()
            var split = -1
            for (index in 0..all.size - 4) {
                if (all[index] == CR && all[index + 1] == LF && all[index + 2] == CR && all[index + 3] == LF) {
                    split = index
                    break
                }
            }
            check(split >= 0) { "the fixture answered no header block: ${all.size} bytes" }
            val lines = String(all, 0, split, Charsets.US_ASCII).split("\r\n")
            val statusLine = lines[0].split(' ', limit = 3)
            val headers =
                lines
                    .drop(1)
                    .associate { line ->
                        val colon = line.indexOf(':')
                        line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                    }
            return RawResponse(statusLine[1].toInt(), headers, all.copyOfRange(split + 4, all.size))
        }
    }

    private fun request(
        port: Int,
        method: String = "GET",
        target: String = "/audio",
        range: String? = null,
        extraHeaders: List<String> = emptyList(),
        version: String = "HTTP/1.1",
    ): RawResponse =
        send(
            port,
            buildString {
                append("$method $target $version\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                if (range != null) append("Range: $range\r\n")
                for (header in extraHeaders) append(header).append("\r\n")
                append("\r\n")
            },
        )

    @Test
    fun `serves the first ten MiB range with exact 206 headers and bytes`() {
        fixture().use { fixture ->
            val response = request(fixture.port, range = "bytes=0-10485759")
            assertThat(response.status).isEqualTo(206)
            assertThat(response.contentRange()).isEqualTo("bytes 0-10485759/$TOTAL_BYTES")
            assertThat(response.contentLength()).isEqualTo(FIRST_CHUNK_BYTES)
            assertThat(response.headers["content-type"]).isEqualTo("audio/mp4")
            assertThat(response.headers["accept-ranges"]).isEqualTo("bytes")
            val expected = fixture.media.readRange(0, FIRST_CHUNK_BYTES.toInt())
            assertThat(response.body).isEqualTo(expected)
            assertThat(sha256(response.body)).isEqualTo(sha256(expected))
        }
    }

    @Test
    fun `serves the second ten MiB range with exact 206 headers and bytes`() {
        fixture().use { fixture ->
            val response = request(fixture.port, range = "bytes=10485760-20971519")
            assertThat(response.status).isEqualTo(206)
            assertThat(response.contentRange()).isEqualTo("bytes 10485760-20971519/$TOTAL_BYTES")
            assertThat(response.contentLength()).isEqualTo(FIRST_CHUNK_BYTES)
            assertThat(response.body).isEqualTo(fixture.media.readRange(10485760, FIRST_CHUNK_BYTES.toInt()))
        }
    }

    @Test
    fun `serves the open-ended tail range with exact 206 headers and bytes`() {
        fixture().use { fixture ->
            val response = request(fixture.port, range = "bytes=20971520-")
            assertThat(response.status).isEqualTo(206)
            assertThat(response.contentRange()).isEqualTo("bytes 20971520-22020218/$TOTAL_BYTES")
            assertThat(response.contentLength()).isEqualTo(TAIL_BYTES)
            assertThat(response.body).isEqualTo(fixture.media.readRange(20971520, TAIL_BYTES.toInt()))
        }
    }

    @Test
    fun `the three downloaded ranges assemble to the expected whole-file digest`() {
        fixture().use { fixture ->
            val ranges = listOf("bytes=0-10485759", "bytes=10485760-20971519", "bytes=20971520-")
            val assembled =
                ranges.fold(ByteArrayOutputStream()) { acc, range ->
                    val response = request(fixture.port, range = range)
                    assertThat(response.status).isEqualTo(206)
                    acc.apply { write(response.body) }
                }
            assertThat(sha256(assembled.toByteArray())).isEqualTo(fixture.media.sha256Hex)
            // Per-request body digests are recorded evidence too.
            val served = fixture.requests()
            assertThat(served.map { it.range }).containsExactlyElementsIn(ranges).inOrder()
            for (record in served) assertThat(record.bodySha256).isNotNull()
        }
    }

    @Test
    fun `serves the full body on an un-ranged get`() {
        fixture().use { fixture ->
            val response = request(fixture.port)
            assertThat(response.status).isEqualTo(200)
            assertThat(response.contentRange()).isNull()
            assertThat(response.contentLength()).isEqualTo(TOTAL_BYTES)
            assertThat(response.headers["content-type"]).isEqualTo("audio/mp4")
            assertThat(sha256(response.body)).isEqualTo(fixture.media.sha256Hex)
        }
    }

    @Test
    fun `routes the rewritten url including its signed query`() {
        fixture().use { fixture ->
            // The rewrite keeps the recorded query (itag/clen/sig); the fixture must route
            // on the path and never let the query reach records or logs.
            val response =
                request(
                    fixture.port,
                    target = "/audio?itag=140&clen=$TOTAL_BYTES&lmt=123&ip=0.0.0.0&expire=4102444800&sig=X",
                    range = "bytes=0-7",
                )
            assertThat(response.status).isEqualTo(206)
            assertThat(response.body).isEqualTo(fixture.media.readRange(0, 8))
            val served = fixture.requests().single()
            assertThat(served.path).isEqualTo("/audio")
        }
    }

    @Test
    fun `rejects malformed suffix and multiple ranges with 400`() {
        fixture().use { fixture ->
            for (
            bad in
            listOf(
                "bytes=abc",
                "bytes=5-2",
                "bytes=-100",
                "bytes=0-1,2-3",
                "items=0-1",
                "bytes =0-1",
                "bytes=",
                "",
                "bytes=0-a",
                "bytes=99999999999999999999-",
            )
            ) {
                assertThat(request(fixture.port, range = bad).status).isEqualTo(400)
            }
            assertThat(
                request(
                    fixture.port,
                    extraHeaders = listOf("Range: bytes=0-1", "Range: bytes=2-3"),
                ).status,
            ).isEqualTo(400)
        }
    }

    @Test
    fun `answers 416 with the total for out-of-bounds ranges`() {
        fixture().use { fixture ->
            for (range in listOf(
                "bytes=$TOTAL_BYTES-",
                "bytes=99999999-",
                "bytes=0-$TOTAL_BYTES",
                "bytes=20971520-99999999",
            )) {
                val response = request(fixture.port, range = range)
                assertThat(response.status).isEqualTo(416)
                assertThat(response.contentRange()).isEqualTo("bytes */$TOTAL_BYTES")
                assertThat(response.contentLength()).isEqualTo(0)
            }
        }
    }

    @Test
    fun `rejects unknown routes and other methods`() {
        fixture().use { fixture ->
            for (target in listOf("/", "/audio/../secret", "/etc/passwd", "/audio/extra")) {
                assertThat(request(fixture.port, target = target).status).isEqualTo(404)
            }
            // An origin-form target without the leading slash is malformed, not a miss.
            assertThat(request(fixture.port, target = "audio").status).isEqualTo(400)
            for (method in listOf("POST", "PUT", "HEAD", "DELETE")) {
                val response = request(fixture.port, method = method)
                assertThat(response.status).isEqualTo(405)
                assertThat(response.headers["allow"]).isEqualTo("GET")
            }
            // Absolute-form targets and bad versions are malformed, not routable.
            assertThat(request(fixture.port, target = "http://127.0.0.1/audio").status).isEqualTo(400)
            assertThat(request(fixture.port, version = "HTTP/2.0").status).isEqualTo(400)
            assertThat(send(fixture.port, "GET  /audio HTTP/1.1\r\n\r\n").status).isEqualTo(400)
        }
    }

    @Test
    fun `bounds the request head`() {
        fixture().use { fixture ->
            // 128 header lines exceed the 64-line cap even inside the byte cap.
            val manyHeaders = (1..128).map { "X-H$it: v" }
            assertThat(request(fixture.port, extraHeaders = manyHeaders).status).isEqualTo(400)
            // A single line and a header block over the byte cap both fail as oversize.
            val huge = "X-Pad: " + "p".repeat(40 * 1024)
            assertThat(request(fixture.port, extraHeaders = listOf(huge)).status).isEqualTo(400)
            assertThat(send(fixture.port, "GET /" + "a".repeat(40 * 1024) + " HTTP/1.1\r\n\r\n").status)
                .isEqualTo(400)
        }
    }

    @Test
    fun `serves concurrent range requests correctly`() {
        fixture().use { fixture ->
            val threads = 8
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)
            val ranges = listOf("bytes=0-1048575", "bytes=1048576-2097151", "bytes=2097152-3145727", "bytes=104-999")
            repeat(threads) { index ->
                Thread {
                    runCatching {
                        start.await()
                        for (range in ranges) {
                            val response = request(fixture.port, range = range)
                            if (response.status != 206) {
                                errors += "thread $index $range -> ${response.status}"
                                continue
                            }
                            val (from, to) = range.removePrefix("bytes=").split('-').map { it.toLong() }
                            val expected = fixture.media.readRange(from, (to - from + 1).toInt())
                            if (!response.body.contentEquals(expected)) {
                                errors +=
                                    "thread $index $range bytes differ"
                            }
                        }
                    }.onFailure { errors += "thread $index failed: $it" }
                    done.countDown()
                }.apply { isDaemon = true }
                    .start()
            }
            start.countDown()
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue()
            assertThat(errors).isEmpty()
            val served = fixture.requests()
            assertThat(served).hasSize(threads * ranges.size)
            assertThat(served.map { it.status }.distinct()).containsExactly(206)
            assertThat(served.map { it.ordinal }.toSet()).hasSize(threads * ranges.size)
        }
    }

    @Test
    fun `records ordinal range status and bytes written per request`() {
        fixture().use { fixture ->
            request(fixture.port, range = "bytes=0-10485759")
            request(fixture.port, range = "bytes=0-10485759") // a retry repeats the range
            request(fixture.port, range = "bytes=$TOTAL_BYTES-") // unsatisfiable
            request(fixture.port, range = "junk") // malformed
            request(fixture.port, target = "/nope")

            val served = fixture.requests()
            assertThat(served.map { it.ordinal }).containsExactly(0L, 1L, 2L, 3L, 4L).inOrder()
            assertThat(served.map { it.method }.distinct()).containsExactly("GET")
            assertThat(served.map { it.status }).containsExactly(206, 206, 416, 400, 404).inOrder()
            assertThat(served[0].range).isEqualTo("bytes=0-10485759")
            assertThat(served[0].bytesWritten).isEqualTo(FIRST_CHUNK_BYTES)
            assertThat(served[0].bodySha256).isEqualTo(sha256(fixture.media.readRange(0, FIRST_CHUNK_BYTES.toInt())))
            assertThat(served[2].range).isEqualTo("bytes=$TOTAL_BYTES-")
            assertThat(served[2].bytesWritten).isEqualTo(0)
            assertThat(served[3].range).isEqualTo("junk")
            assertThat(served[4].path).isEqualTo("/nope")
            assertThat(served[4].range).isNull()

            // The proof's phase boundary: a snapshot never sees later requests.
            val before = fixture.requests()
            request(fixture.port, range = "bytes=1-2")
            assertThat(before).hasSize(served.size)
            assertThat(fixture.requests()).hasSize(served.size + 1)
        }
    }

    @Test
    fun requestBoundaryWaitsForPlaybackBodyToFinish() {
        fixture().use { fixture ->
            val playback =
                Socket().apply {
                    receiveBufferSize = 1024
                    soTimeout = SOCKET_TIMEOUT_MS
                    connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), fixture.port))
                }
            try {
                playback.outputStream.write(
                    "GET /audio HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                playback.outputStream.flush()
                var end = 0
                while (end != 0x0D0A0D0A) {
                    val next = playback.inputStream.read()
                    check(next >= 0) { "the playback response ended before its headers" }
                    end = (end shl 8) or next
                }
                // The receive window cannot hold the 22 MB body. Keep the reader paused
                // while the fixture is still serving it, just like the packaged playback.
                try {
                    fixture.awaitRequestBoundary(100)
                    error("the request boundary returned while playback was still reading")
                } catch (expected: IOException) {
                    assertThat(expected.message).contains("did not settle")
                }
            } finally {
                playback.close()
            }
            val boundary = fixture.awaitRequestBoundary(5_000)
            assertThat(boundary).isEqualTo(1)
            request(fixture.port, range = "bytes=0-0")
            fixture.awaitRequestBoundary(5_000)
            assertThat(fixture.requests().drop(boundary).map { it.range }).containsExactly("bytes=0-0")
        }
    }

    @Test
    fun `close stops accepting and releases owned sockets and threads`() {
        val fixture = fixture()
        val port = fixture.port
        request(port, range = "bytes=0-7") // one live exchange before shutdown
        fixture.close()
        assertThat(fixture.liveThreadCount()).isEqualTo(0)
        assertThat(runCatching { request(port) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        // close is idempotent.
        fixture.close()
        assertThat(fixture.liveThreadCount()).isEqualTo(0)
    }

    @Test
    fun `a connect without a request leaves no record`() {
        fixture().use { fixture ->
            Socket(InetAddress.getByName("127.0.0.1"), fixture.port).use { it.soTimeout = 500 }
            Thread.sleep(100)
            assertThat(fixture.requests()).isEmpty()
        }
    }

    private companion object {
        private const val TOTAL_BYTES = 21L * 1024 * 1024 + 123 // 22020219
        private const val FIRST_CHUNK_BYTES = 10L * 1024 * 1024 // 10485760
        private const val TAIL_BYTES = TOTAL_BYTES - 2 * FIRST_CHUNK_BYTES // 1048699
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
    }
}
