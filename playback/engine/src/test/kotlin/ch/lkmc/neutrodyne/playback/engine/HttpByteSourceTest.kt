// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Rule
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpByteSourceTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @Test
    fun resumesACutWithRangeAndStrongIfRange() =
        withCache { cache ->
            val bytes = ByteArray(128 * 1024) { (it % 127).toByte() }
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 0-${bytes.lastIndex}/${bytes.size}")
                    .addHeader("ETag", "\"v1\"")
                    .body(Buffer().write(bytes, 0, 32768))
                    .setHeader("Content-Length", bytes.size)
                    .onResponseEnd(SocketEffect.CloseSocket())
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 32768-${bytes.lastIndex}/${bytes.size}")
                    .addHeader("ETag", "\"v1\"")
                    .body(Buffer().write(bytes, 32768, bytes.size - 32768))
                    .build(),
            )
            source(cache).use { input -> assertContentEquals(bytes, readAll(input, bytes.size)) }
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("bytes=0-", first.headers["Range"])
            assertEquals("identity", first.headers["Accept-Encoding"])
            assertEquals("bytes=32768-", second.headers["Range"])
            assertEquals("\"v1\"", second.headers["If-Range"])
        }

    @Test
    fun losingCachedSpansBehindAnActiveFetchRestartsTheRange() =
        withCache { cache ->
            val bytes = ByteArray(128 * 1024) { (it % 127).toByte() }
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(Buffer().write(bytes))
                    .throttleBody(64 * 1024, 10, TimeUnit.SECONDS)
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 10-${bytes.lastIndex}/${bytes.size}")
                    .body(Buffer().write(bytes, 10, bytes.size - 10))
                    .build(),
            )
            val slowClient = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
            source(cache, slowClient).use { input ->
                assertEquals(1, input.read(0, MemorySegment.ofArray(ByteArray(1)), 1))
                server.takeRequest()
                cache.open("yt:test:140").use { resource ->
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (resource.read(65_535, 1) == null && System.nanoTime() < deadline) Thread.sleep(5)
                    assertNotNull(resource.read(65_535, 1), "the loader never cached its first chunk")
                    // A disk failure drops persistent spans and switches to memory.
                    // Losing the already fetched prefix must trigger a fresh range.
                    resource.invalidate()
                    val finished = CountDownLatch(1)
                    val output = ByteArray(8)
                    var read = 0
                    var failure: Throwable? = null
                    val reader =
                        Thread {
                            try {
                                read = input.read(10, MemorySegment.ofArray(output), output.size)
                            } catch (problem: Throwable) {
                                failure = problem
                            } finally {
                                finished.countDown()
                            }
                        }
                    reader.start()
                    try {
                        val request =
                            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS), "cache miss did not restart HTTP")
                        assertEquals("bytes=10-", request.headers["Range"])
                        assertTrue(finished.await(2, TimeUnit.SECONDS))
                        failure?.let { throw it }
                        assertEquals(output.size, read)
                        assertContentEquals(bytes.copyOfRange(10, 18), output)
                    } finally {
                        input.abort()
                        reader.join(5_000)
                    }
                }
            }
        }

    @Test
    fun ignoredRangeConsumesThePrefix() =
        withCache { cache ->
            server.enqueue(MockResponse.Builder().body("0123456789").build())
            source(cache).use { input ->
                val result = ByteArray(4)
                assertEquals(4, input.read(6, MemorySegment.ofArray(result), 4))
                assertContentEquals("6789".toByteArray(), result)
            }
        }

    @Test
    fun changedTotalAndIfRangeReplacementAreExplicit() =
        withCache { cache ->
            cache.open("yt:test:140").use { it.setMetadata(10, "\"v1\"") }
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 0-10/11")
                    .body("01234567890")
                    .build(),
            )
            source(cache).use { input -> assertFailsWith<ContentChangedException> { readAll(input, 1) } }
            cache.open("yt:test:140").use { it.setMetadata(10, "\"v1\"") }
            server.enqueue(MockResponse.Builder().body("0123456789").build())
            source(cache).use { input -> assertFailsWith<ContentChangedException> { readAll(input, 1) } }
        }

    @Test
    fun partialResponseWithoutAnEtagRetainsTheValidatedIfRange() =
        withCache { cache ->
            cache.open("yt:test:140").use {
                it.setMetadata(6, "\"v1\"")
                it.write(0, "012".toByteArray())
            }
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 3-5/6")
                    .body("345")
                    .build(),
            )
            source(cache).use { input ->
                val bytes = ByteArray(3)
                assertEquals(3, input.read(3, MemorySegment.ofArray(bytes), 3))
                assertContentEquals("345".toByteArray(), bytes)
            }
            assertEquals("\"v1\"", server.takeRequest().headers["If-Range"])
            cache.open("yt:test:140").use { assertEquals("\"v1\"", it.validator) }
        }

    @Test
    fun changedYoutubeClenDiscardsCachedBytesBeforeAnyRead() =
        withCache { cache ->
            cache.open("yt:test:140").use {
                it.setMetadata(3, "\"old\"")
                it.write(0, "old".toByteArray())
            }
            server.enqueue(MockResponse.Builder().body("new!").build())
            HttpByteSource(client, server.url("/").toString(), emptyMap(), cache, "yt:test:140", 4).use {
                assertContentEquals("new!".toByteArray(), readAll(it, 4))
            }
            assertEquals(null, server.takeRequest().headers["If-Range"])
        }

    @Test
    fun weakEtagDoesNotFallBackToLastModified() =
        withCache { cache ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .body("abc")
                    .addHeader("ETag", "W/\"v1\"")
                    .addHeader("Last-Modified", "Wed, 04 Nov 2026 10:00:00 GMT")
                    .build(),
            )
            source(cache).use { assertContentEquals("abc".toByteArray(), readAll(it, 3)) }
            cache.open("yt:test:140").use { assertEquals(null, it.validator) }
        }

    @Test
    fun abortReleasesAReadWaitingForHeaders() =
        withCache { cache ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .headersDelay(10, TimeUnit.SECONDS)
                    .body("abc")
                    .build(),
            )
            source(cache).use { input ->
                val finished = CountDownLatch(1)
                var count = 42
                val reader =
                    Thread {
                        try {
                            count = input.read(0, MemorySegment.ofArray(ByteArray(3)), 3)
                        } finally {
                            finished.countDown()
                        }
                    }
                reader.start()
                assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
                input.abort()
                assertTrue(finished.await(1, TimeUnit.SECONDS))
                reader.join()
                assertEquals(-1, count)
            }
        }

    @Test
    fun forbiddenStatusIsAvailableToTheResolver() =
        withCache { cache ->
            server.enqueue(MockResponse.Builder().code(403).build())
            source(cache).use { input ->
                assertEquals(403, assertFailsWith<HttpSourceException> { readAll(input, 1) }.status)
            }
        }

    private fun source(
        cache: SpanCache,
        httpClient: OkHttpClient = client,
    ) = HttpByteSource(httpClient, server.url("/media").toString(), emptyMap(), cache, "yt:test:140")

    private fun readAll(
        input: ByteSource,
        size: Int,
    ): ByteArray {
        val result = ByteArray(size)
        var position = 0
        while (position < size) {
            val destination = MemorySegment.ofArray(result).asSlice(position.toLong())
            val read = input.read(position.toLong(), destination, size - position)
            check(read > 0) { "unexpected end or abort" }
            position += read
        }
        return result
    }

    private fun withCache(block: (SpanCache) -> Unit) {
        val directory = Files.createTempDirectory("http-source")
        try {
            block(SpanCache(directory))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
