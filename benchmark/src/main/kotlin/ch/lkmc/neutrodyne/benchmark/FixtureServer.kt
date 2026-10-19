// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.benchmark

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Loopback lives in the test APK, so am kill cannot kill the fixture or its Range log. */
internal class FixtureServer(
    private val journey: Journey,
) : AutoCloseable {
    enum class Journey { PROCESS_DEATH, SMOKE }

    val media = wave(if (journey == Journey.PROCESS_DEATH) E10_SECONDS else SMOKE_SECONDS)
    val sha256 =
        MessageDigest.getInstance("SHA-256").digest(media).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    val requests = CopyOnWriteArrayList<Request>()
    private val server = MockWebServer()
    val mediaUrl get() = server.url("/episode.wav").toString()
    val feedUrl get() = server.url("/feed.xml").toString()

    data class Request(
        val path: String,
        val range: String?,
        val ifRange: String?,
    )

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests +=
                        Request(
                            request.url.encodedPath,
                            request.headers["Range"],
                            request.headers["If-Range"],
                        )
                    return when (request.url.encodedPath) {
                        "/feed.xml" -> {
                            MockResponse
                                .Builder()
                                .addHeader("Content-Type", "application/rss+xml")
                                .body(feed())
                                .build()
                        }

                        "/episode.wav" -> {
                            enclosure(request)
                        }

                        else -> {
                            MockResponse.Builder().code(404).build()
                        }
                    }
                }
            }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun enclosure(request: RecordedRequest): MockResponse {
        val ifRange = request.headers["If-Range"]
        val range = request.headers["Range"]?.takeIf { ifRange == null || ifRange == ETAG }
        val bounds = range?.let { RANGE.matchEntire(it)?.groupValues }
        val offset = bounds?.get(1)?.toIntOrNull()
        val end = (bounds?.get(2)?.toIntOrNull() ?: media.lastIndex).coerceAtMost(media.lastIndex)
        if (range != null && (offset == null || offset !in media.indices || end < offset)) {
            return MockResponse
                .Builder()
                .code(416)
                .addHeader("Content-Range", "bytes */${media.size}")
                .build()
        }
        val start = offset ?: 0
        val response =
            MockResponse
                .Builder()
                .code(if (range == null) 200 else 206)
                .addHeader("Content-Type", "audio/wav")
                .addHeader("Accept-Ranges", "bytes")
                .addHeader("ETag", ETAG)
                .body(Buffer().write(media, start, end - start + 1))
        if (range != null) response.addHeader("Content-Range", "bytes $start-$end/${media.size}")
        if (journey == Journey.PROCESS_DEATH) response.throttleBody(THROTTLE_BYTES, 250, TimeUnit.MILLISECONDS)
        return response.build()
    }

    private fun feed(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>$PODCAST</title>
        <link>https://example.invalid/</link><description>Generated system fixture</description>
        <item><guid isPermaLink="false">w10-smoke</guid><title>$EPISODE</title>
        <pubDate>Mon, 19 Oct 2026 00:00:00 GMT</pubDate>
        <enclosure url="$mediaUrl" type="audio/wav" length="${media.size}" />
        <description>Generated PCM audio</description></item></channel></rss>
        """.trimIndent()

    override fun close() = server.close()

    companion object {
        const val ETAG = "\"w10-strong-v1\""
        const val PODCAST = "W10 release fixture"
        const val EPISODE = "W10 smoke episode"
        private const val E10_SECONDS = 120
        private const val SMOKE_SECONDS = 30
        private const val SAMPLE_RATE = 22_050
        private const val BYTES_PER_SAMPLE = 2
        private const val WAVE_HEADER_BYTES = 44
        private const val THROTTLE_BYTES = 32_768L
        private val RANGE = Regex("bytes=([0-9]+)-([0-9]*)")

        /** Generated PCM, no copyrighted audio and no checked-in multi-megabyte asset. */
        private fun wave(seconds: Int): ByteArray {
            val dataSize = seconds * SAMPLE_RATE * BYTES_PER_SAMPLE
            return ByteBuffer
                .allocate(WAVE_HEADER_BYTES + dataSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt(dataSize + 36)
                    put("WAVEfmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(SAMPLE_RATE)
                    putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
                    putShort(BYTES_PER_SAMPLE.toShort())
                    putShort(16)
                    put("data".toByteArray())
                    putInt(dataSize)
                    // Quiet square wave gives the player a valid, measurable audio timeline.
                    repeat(dataSize / BYTES_PER_SAMPLE) { putShort(if (it % 100 < 50) 256 else -256) }
                }.array()
        }
    }
}
