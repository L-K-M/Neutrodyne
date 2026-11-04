// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.lang.foreign.MemorySegment
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A positional, abortable AVIO source. Network and cache writes belong to nd-loader. */
class HttpByteSource internal constructor(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>,
    cache: SpanCache,
    cacheKey: String,
    expectedLength: Long? = null,
    bitRate: Long = DEFAULT_BIT_RATE,
) : ByteSource {
    private val resource = cache.open(cacheKey)
    private val loader = LookAheadLoader(client, url, headers, cache, resource, expectedLength, bitRate)
    private val closed = AtomicBoolean(false)
    override val length: Long? get() = resource.length

    override fun read(
        position: Long,
        dst: MemorySegment,
        maxBytes: Int,
    ): Int {
        require(position >= 0 && maxBytes > 0 && dst.byteSize() >= maxBytes)
        if (closed.get()) return -1
        val bytes = loader.read(position, maxBytes) ?: return if (closed.get()) -1 else 0
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, dst, 0, bytes.size.toLong())
        return bytes.size
    }

    internal fun setDurationEstimate(durationMs: Long) {
        val bytes = length ?: return
        if (bytes <= 0 || durationMs <= 0) return
        loader.setBitRate((bytes.toDouble() * 8_000 / durationMs).toLong().coerceAtLeast(8))
    }

    override fun abort() {
        if (closed.compareAndSet(false, true)) loader.close()
    }

    override fun close() {
        abort()
        loader.awaitClosed()
    }

    companion object {
        private const val DEFAULT_BIT_RATE = 128_000L

        /** Always use the island clients, preserving shared auth, DNS, pooling and User-Agent. */
        fun open(
            clients: NetworkClients,
            source: ResolvedSource,
            cache: SpanCache,
        ): HttpByteSource =
            when (source) {
                is ResolvedSource.Http -> {
                    HttpByteSource(clients.media, source.url, source.headers, cache, source.cacheKey)
                }

                is ResolvedSource.YouTube -> {
                    HttpByteSource(
                        clients[HttpClientKind.YOUTUBE],
                        source.url,
                        emptyMap(),
                        cache,
                        source.cacheKey,
                        source.contentLength,
                    )
                }

                is ResolvedSource.File -> {
                    error("a local file does not use HTTP")
                }
            }
    }
}

/** One loader per open source. A seek cancels the old call instead of waiting for its read timeout. */
internal class LookAheadLoader(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    private val cache: SpanCache,
    private val resource: SpanCache.Resource,
    expectedLength: Long?,
    bitRate: Long,
) : AutoCloseable {
    private val monitor = Object()
    private var stopped = false
    private var requested: Long? = null
    private var fetchStart = 0L
    private var cursor = 0L
    private var demand = 0L
    private var revision = 0L
    private var call: Call? = null
    private var failure: IOException? = null

    @Volatile private var aheadBytes =
        (bitRate.coerceAtLeast(8) / 8 * MAX_AHEAD_SECONDS).coerceIn(MIN_AHEAD_BYTES, MAX_AHEAD_BYTES)

    @Volatile private var lowWaterBytes = minOf(aheadBytes, bitRate.coerceAtLeast(8) / 8 * MIN_AHEAD_SECONDS)
    private val thread: Thread

    init {
        require(expectedLength == null || expectedLength >= 0)
        // YouTube's resolved clen is authoritative before a cached byte can be returned.
        if (expectedLength != null && resource.length != null && expectedLength != resource.length) {
            resource.invalidate()
        }
        if (expectedLength != null && resource.length == null) resource.setMetadata(expectedLength, resource.validator)
        thread =
            Thread({ run() }, "nd-loader").apply {
                isDaemon = true
                start()
            }
    }

    fun setBitRate(bitRate: Long) =
        synchronized(monitor) {
            require(bitRate > 0)
            aheadBytes =
                (bitRate.coerceAtMost(MAX_AHEAD_BYTES * 8) / 8 * MAX_AHEAD_SECONDS)
                    .coerceIn(MIN_AHEAD_BYTES, MAX_AHEAD_BYTES)
            lowWaterBytes = minOf(aheadBytes, bitRate.coerceAtMost(MAX_AHEAD_BYTES * 8) / 8 * MIN_AHEAD_SECONDS)
            monitor.notifyAll()
        }

    fun read(
        position: Long,
        count: Int,
    ): ByteArray? =
        synchronized(monitor) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READ_WAIT_SECONDS)
            demand = position
            val cached = resource.read(position, count)
            if (cached == null && (position < fetchStart || position - cursor > REOPEN_DISTANCE_BYTES)) {
                revision++
                call?.cancel()
                requested = position
            } else if (cached == null && requested == null && call == null) {
                requested = position
            }
            monitor.notifyAll()
            while (true) {
                if (stopped) return@synchronized null
                failure?.let { throw it }
                resource.read(position, count)?.let { return@synchronized it }
                if (resource.length?.let { position >= it } == true) return@synchronized null
                if (System.nanoTime() >= deadline) throw IOException("Media read made no progress for 30 seconds")
                monitor.wait(WAIT_SLICE_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    override fun close() =
        synchronized(monitor) {
            stopped = true
            call?.cancel()
            monitor.notifyAll()
        }

    fun awaitClosed() {
        thread.join(CLOSE_WAIT_MS)
        check(!thread.isAlive) { "media loader did not stop after cancellation" }
    }

    private fun run() {
        try {
            while (true) {
                val work =
                    synchronized(monitor) {
                        while (!stopped && requested == null) monitor.wait()
                        if (stopped) return
                        val position = requireNotNull(requested)
                        requested = null
                        cursor = position
                        fetchStart = position
                        position to revision
                    }
                load(work.first, work.second)
            }
        } catch (problem: IOException) {
            synchronized(monitor) {
                failure = problem
                monitor.notifyAll()
            }
        } catch (problem: Exception) {
            synchronized(monitor) {
                failure = IOException("Media loader failed", problem)
                monitor.notifyAll()
            }
        } finally {
            resource.close()
        }
    }

    private fun load(
        start: Long,
        version: Long,
    ) {
        var position = start
        var failures = 0
        while (true) {
            if (cancelled(version)) return
            val existing = resource.read(position, CHUNK_BYTES)
            if (existing != null) {
                position += existing.size
                synchronized(monitor) { cursor = position }
                if (!waitForRoom(position, version)) return
                continue
            }
            if (resource.length?.let { position >= it } == true) return
            try {
                fetch(position, version)
                return
            } catch (changed: ContentChangedException) {
                resource.invalidate()
                throw changed
            } catch (status: HttpSourceException) {
                throw status
            } catch (network: IOException) {
                if (cancelled(version)) return
                failures++
                if (failures > MAX_RETRIES) throw network
                synchronized(monitor) {
                    if (!stopped) monitor.wait(minOf((failures - 1) * 1_000L, MAX_RETRY_DELAY_MS).coerceAtLeast(1))
                    position = cursor
                }
            }
        }
    }

    private fun fetch(
        position: Long,
        version: Long,
    ) {
        val validator = resource.validator
        val request =
            Request
                .Builder()
                .url(url)
                .apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    header("Range", "bytes=$position-")
                    header("Accept-Encoding", "identity")
                    removeHeader("If-Range")
                    if (validator != null) header("If-Range", validator)
                }.build()
        val active = client.newCall(request)
        synchronized(monitor) {
            if (stopped || revision != version) return
            call = active
        }
        try {
            active.execute().use { response ->
                if (response.code == 416 && resource.length?.let { position >= it } == true) return
                if (response.code !in setOf(200, 206)) throw HttpSourceException(response.code)
                if (response.header("Content-Encoding")?.let { it != "identity" } == true) {
                    throw IOException("Media response is not identity encoded")
                }
                if (response.code == 200 && validator != null) {
                    throw ContentChangedException("Server replaced an If-Range representation")
                }
                val total = responseLength(response, position)
                if (total != null && resource.length != null && total != resource.length) {
                    throw ContentChangedException("Media total length changed")
                }
                val etag = response.header("ETag")
                if (validator?.startsWith("\"") == true && etag != null && etag != validator) {
                    throw ContentChangedException("Media ETag changed during range resume")
                }
                val nextValidator =
                    when {
                        etag == null -> {
                            if (response.code == 206) {
                                validator ?: response.header("Last-Modified")
                            } else {
                                response.header("Last-Modified")
                            }
                        }

                        etag.startsWith("\"") && etag.endsWith("\"") -> {
                            etag
                        }

                        else -> {
                            null
                        } // Weak ETags must never become If-Range validators.
                    }
                resource.setMetadata(total ?: resource.length, nextValidator)
                val body = response.body
                val input = body.byteStream()
                // A server may ignore Range without If-Range. Consume the prefix,
                // never label bytes from offset zero as bytes at the requested offset.
                var discard = if (response.code == 200) position else 0L
                val buffer = ByteArray(CHUNK_BYTES)
                var offset = position
                while (!cancelled(version)) {
                    if (discard == 0L && !waitForRoom(offset, version)) return
                    val count = minOf(buffer.size.toLong(), if (discard > 0) discard else buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, count)
                    if (read < 0) {
                        if (discard > 0 || resource.length?.let { offset < it } == true) {
                            throw IOException("Media body ended early")
                        }
                        if (resource.length == null) resource.setMetadata(offset, resource.validator)
                        return
                    }
                    if (read == 0) continue
                    if (discard > 0) {
                        discard -= read
                        continue
                    }
                    resource.write(offset, buffer.copyOf(read))
                    offset += read
                    synchronized(monitor) {
                        cursor = offset
                        monitor.notifyAll()
                    }
                    cache.evict()
                }
            }
        } finally {
            synchronized(monitor) { if (call === active) call = null }
        }
    }

    private fun responseLength(
        response: Response,
        position: Long,
    ): Long? {
        if (response.code == 200) return response.body.contentLength().takeIf { it >= 0 }
        val match =
            CONTENT_RANGE.matchEntire(response.header("Content-Range") ?: "")
                ?: throw IOException("Invalid media Content-Range")
        val start = match.groupValues[1].toLongOrNull() ?: throw IOException("Invalid media range start")
        val end = match.groupValues[2].toLongOrNull() ?: throw IOException("Invalid media range end")
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        if (start != position || end < start || (total != null && total <= end)) {
            throw IOException("Mismatched media range")
        }
        val bodyLength = response.body.contentLength()
        if (bodyLength >= 0 && bodyLength != end - start + 1) throw IOException("Mismatched media body length")
        return total
    }

    private fun waitForRoom(
        position: Long,
        version: Long,
    ): Boolean =
        synchronized(monitor) {
            val ceiling = if (resource.isPersistent) aheadBytes else minOf(aheadBytes, MEMORY_AHEAD_BYTES)
            if (position - demand >= ceiling) {
                while (!stopped && revision == version && position - demand > minOf(lowWaterBytes, ceiling / 2)) {
                    monitor.wait(WAIT_SLICE_MS)
                }
            }
            !stopped && revision == version
        }

    private fun cancelled(version: Long): Boolean = synchronized(monitor) { stopped || revision != version }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val REOPEN_DISTANCE_BYTES = 256 * 1024
        const val MIN_AHEAD_SECONDS = 60L
        const val MAX_AHEAD_SECONDS = 600L
        const val MIN_AHEAD_BYTES = 64L * 1024
        const val MEMORY_AHEAD_BYTES = 4L * 1024 * 1024
        const val MAX_AHEAD_BYTES = 32L * 1024 * 1024
        const val READ_WAIT_SECONDS = 30L
        const val WAIT_SLICE_MS = 1_000L
        const val CLOSE_WAIT_MS = 5_000L
        const val MAX_RETRIES = 8
        const val MAX_RETRY_DELAY_MS = 5_000L
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    }
}
