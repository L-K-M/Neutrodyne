// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import ch.lkmc.neutrodyne.core.common.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The proof-local loopback media fixture for MD3 AC2's packaged proof: a `ServerSocket`
 * bound to `127.0.0.1:0` at construction — so [port] is the actual bound ephemeral port,
 * never a pick-then-bind race — answering only `GET <mediaPath>` from the deterministic
 * [FixtureMediaBytes] composition.
 *
 * Deliberately raw `java.net`, java.base only: the fixture ships inside the packaged image's
 * `youtubeEngine` source set, so no server framework may come along. It is proof machinery,
 * not a graph binding — the caller constructs it, takes [mediaUrl]/[FixtureMediaBytes] for
 * the scenario rewrite and expected bytes, and [close]s it; nothing else can construct or
 * reach it. It accepts loopback connections only, serves exactly one in-memory body at one
 * path (no file access, no outbound connection), and never logs request targets — signed
 * media URLs stay out of the logs.
 *
 * HTTP semantics are the subset the playback `LookAheadLoader` and the download
 * `YouTubeTransferSource` both validate: `Range: bytes=start-end` (or open-ended
 * `bytes=start-`) → `206` with exact `Content-Range`/`Content-Length`/`Content-Type:
 * audio/mp4`; no `Range` → `200` with the full body. Anything else fails clearly:
 * malformed, suffix or multi-range values → `400`; a start at or beyond the body or an
 * end beyond the last byte → `416` (strict — no RFC clamping, so the recorded evidence
 * carries exactly the ranges the clients asked for); unknown paths → `404`; other methods →
 * `405`; oversize request heads → `400`. The head is bounded to [MAX_HEAD_BYTES] and
 * [MAX_HEADER_LINES]; each connection answers one request and closes.
 *
 * [requests] is the thread-safe evidence list — one [ServedRequest] per answered request
 * with its ordinal, verbatim `Range` header, status and bytes written — so the proof can
 * snapshot at a phase boundary instead of treating playback and download ranges as one
 * combined list. Concurrent playback/download `GET`s are served on bounded worker threads;
 * [close] stops accepting, force-closes owned sockets, and joins every owned thread inside
 * [SHUTDOWN_WAIT_MS].
 */
internal class LoopbackMediaFixture(
    val media: FixtureMediaBytes,
    val mediaPath: String = MEDIA_PATH,
) : Closeable {
    /** One answered request, in arrival order: the phase-boundary evidence list. */
    data class ServedRequest(
        val ordinal: Long,
        val method: String?,
        val path: String?,
        val range: String?,
        val status: Int,
        val bytesWritten: Long,
        val bodySha256: String?,
    )

    private val server = ServerSocket()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ConcurrentHashMap.newKeySet<Thread>()
    private val workerPermits = Semaphore(MAX_WORKERS)
    private val records = mutableListOf<ServedRequest>()
    private val recordLock = Any()

    @Volatile
    private var closed = false
    private val acceptThread: Thread

    /** The actual bound ephemeral port — taken from the bound listener, not chosen up front. */
    val port: Int

    /** `http://127.0.0.1:<port><mediaPath>` — the only URL this fixture answers. */
    val mediaUrl: String

    init {
        server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), ACCEPT_BACKLOG)
        port = (server.localSocketAddress as InetSocketAddress).port
        mediaUrl = "http://$LOOPBACK_HOST:$port$mediaPath"
        acceptThread =
            Thread({ acceptLoop() }, "nd-media-fixture-$port-accept").apply {
                isDaemon = true
                start()
            }
        Log.i(TAG) { "media fixture on $LOOPBACK_HOST:$port serving ${media.size} bytes at $mediaPath" }
    }

    /** A snapshot of the answered requests so far; later requests never mutate it. */
    fun requests(): List<ServedRequest> = synchronized(recordLock) { records.toList() }

    /**
     * After the caller releases playback, wait for its handlers to finish before counting
     * completed requests. Pausing alone can leave a response body in flight. Later traffic
     * still belongs after this boundary and must pass the download's exact range gate.
     */
    fun awaitRequestBoundary(timeoutMs: Long = BOUNDARY_WAIT_MS): Int {
        require(timeoutMs > 0)
        val started = System.nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val quietPeriod = TimeUnit.MILLISECONDS.toNanos(BOUNDARY_QUIET_MS)
        var quietSince: Long? = null
        while (true) {
            check(!closed) { "the media fixture closed before its request boundary" }
            val now = System.nanoTime()
            val idle = workers.isEmpty() && clients.isEmpty() && workerPermits.availablePermits() == MAX_WORKERS
            if (idle) {
                val since = quietSince
                if (since == null) quietSince = now
                if (since != null && now - since >= quietPeriod) return requests().size
            } else {
                quietSince = null
            }
            if (now - started >= budget) {
                throw IOException("media fixture requests did not settle before the phase boundary")
            }
            Thread.sleep(BOUNDARY_POLL_MS)
        }
    }

    /** Owned threads still alive — `0` after a settled [close], checked by tests. */
    fun liveThreadCount(): Int = workers.count { it.isAlive } + if (acceptThread.isAlive) 1 else 0

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        for (socket in clients) runCatching { socket.close() }
        acceptThread.join(JOIN_WAIT_MS)
        val deadline = System.nanoTime() + SHUTDOWN_WAIT_MS * 1_000_000L
        for (worker in workers) {
            // join(0) waits forever; a spent budget simply stops waiting for that worker.
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining > 0) worker.join(remaining)
        }
        for (worker in workers) if (worker.isAlive) worker.interrupt()
        Log.i(TAG) { "media fixture closed ($port)" }
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket =
                try {
                    server.accept()
                } catch (failure: IOException) {
                    break // listener closed or unbindable — the loop is over either way
                }
            if (closed) {
                runCatching { socket.close() }
                break
            }
            if (!workerPermits.tryAcquire()) {
                runCatching { refuse(socket) }
                continue
            }
            val worker =
                Thread(
                    {
                        try {
                            serve(socket)
                        } finally {
                            clients.remove(socket)
                            workers.remove(Thread.currentThread())
                            workerPermits.release()
                        }
                    },
                    "nd-media-fixture-$port-conn",
                )
            worker.isDaemon = true
            workers.add(worker)
            worker.start()
        }
    }

    private fun serve(socket: Socket) {
        clients.add(socket)
        try {
            socket.soTimeout = HEAD_TIMEOUT_MS
            // Linger on close so an early answer (a 400 on an oversize head) is still
            // delivered while the kernel drains the request bytes we never read.
            socket.setSoLinger(true, LINGER_SECONDS)
            when (val head = readHead(socket)) {
                is Head.Complete -> handle(socket, head.bytes)
                Head.Oversize -> writeEmpty(socket.outputStream, STATUS_BAD_REQUEST)
                Head.EndOfStream -> Unit // nothing arrived; nothing to answer
            }
        } catch (timeout: SocketTimeoutException) {
            // A stalled head read ends the connection without a reply.
        } catch (failure: IOException) {
            // The peer left mid-request or mid-body; the record already kept what it could.
        } catch (failure: Exception) {
            Log.w(TAG, failure) { "media fixture connection failed" }
        } finally {
            drainQuietly(socket)
            runCatching { socket.close() }
        }
    }

    /**
     * Half-closes then swallows the request's unread tail, bounded by [DRAIN_CAP_BYTES] and
     * [DRAIN_BUDGET_MS]: closing with unread received bytes makes TCP send RST, which can
     * discard an early answer (a `400` on an oversize head) before the peer reads it. The
     * `FIN` after `shutdownOutput` delivers our last bytes cleanly instead.
     */
    private fun drainQuietly(socket: Socket) {
        runCatching { socket.shutdownOutput() }
        try {
            val deadline = System.nanoTime() + DRAIN_BUDGET_MS * 1_000_000L
            val buffer = ByteArray(DRAIN_BUFFER_BYTES)
            var drained = 0
            while (drained < DRAIN_CAP_BYTES) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val read = socket.inputStream.read(buffer, 0, minOf(buffer.size, DRAIN_CAP_BYTES - drained))
                if (read < 0) break
                drained += read
            }
        } catch (ignored: IOException) {
            // Peer closed or stalled — draining is best-effort hygiene only.
        }
    }

    /**
     * Reads one request head, bounded: a complete head ends at the first `\r\n\r\n` within
     * [MAX_HEAD_BYTES]; anything larger is [Head.Oversize], a clean EOF at zero bytes is
     * [Head.EndOfStream]. `SO_TIMEOUT` caps the wait.
     */
    private fun readHead(socket: Socket): Head {
        val input = socket.inputStream
        val out = ByteArrayOutputStream()
        var tail = 0
        while (out.size() < MAX_HEAD_BYTES) {
            val byte = input.read()
            if (byte < 0) return if (out.size() == 0) Head.EndOfStream else Head.Oversize
            out.write(byte)
            tail = (tail shl 8) or byte
            if (out.size() >= HEAD_END_BYTES && tail == HEAD_END_MARKER) return Head.Complete(out.toByteArray())
        }
        return Head.Oversize
    }

    private fun handle(
        socket: Socket,
        headBytes: ByteArray,
    ) {
        var method: String? = null
        var path: String? = null
        var range: String? = null
        var status = STATUS_BAD_REQUEST
        var bytesWritten = 0L
        var bodySha256: String? = null
        try {
            val lines = String(headBytes, Charsets.US_ASCII).split(CRLF).dropLast(2)
            val parts = lines.firstOrNull()?.split(' ') ?: emptyList()
            val out = socket.outputStream
            when {
                lines.isEmpty() || lines.size > MAX_HEADER_LINES + 1 || parts.size != 3 -> {
                    status = writeEmpty(out, STATUS_BAD_REQUEST)
                }

                parts[2] != HTTP_10 && parts[2] != HTTP_11 -> {
                    status = writeEmpty(out, STATUS_BAD_REQUEST)
                }

                parts[0] != METHOD_GET -> {
                    method = parts[0]
                    status = writeEmpty(out, STATUS_METHOD_NOT_ALLOWED, listOf(HEADER_ALLOW to METHOD_GET))
                }

                else -> {
                    method = parts[0]
                    path = parts[1].substringBefore('?')
                    val headers = parseHeaders(lines.drop(1))
                    when {
                        !parts[1].startsWith("/") -> {
                            status = writeEmpty(out, STATUS_BAD_REQUEST)
                        }

                        headers == null -> {
                            status = writeEmpty(out, STATUS_BAD_REQUEST)
                        }

                        path != mediaPath -> {
                            status = writeEmpty(out, STATUS_NOT_FOUND)
                        }

                        else -> {
                            val ranges = headers.filter { it.first == HEADER_RANGE }
                            if (ranges.size > 1) {
                                status = writeEmpty(out, STATUS_BAD_REQUEST)
                            } else {
                                range = ranges.firstOrNull()?.second
                                val answer = answerFor(range)
                                when (answer) {
                                    is Answer.Full -> {
                                        status = STATUS_OK
                                        writeHead(
                                            out,
                                            STATUS_OK,
                                            listOf(
                                                HEADER_ACCEPT_RANGES to "bytes",
                                                HEADER_CONTENT_TYPE to CONTENT_TYPE_AUDIO_MP4,
                                            ),
                                            media.size,
                                        )
                                        val digest = MessageDigest.getInstance("SHA-256")
                                        bytesWritten = media.copyRangeTo(0, media.size - 1, out, digest)
                                        bodySha256 = hex(digest)
                                    }

                                    is Answer.Satisfiable -> {
                                        status = STATUS_PARTIAL_CONTENT
                                        writeHead(
                                            out,
                                            STATUS_PARTIAL_CONTENT,
                                            listOf(
                                                HEADER_ACCEPT_RANGES to "bytes",
                                                HEADER_CONTENT_TYPE to CONTENT_TYPE_AUDIO_MP4,
                                                HEADER_CONTENT_RANGE to
                                                    "bytes ${answer.start}-${answer.end}/${media.size}",
                                            ),
                                            answer.end - answer.start + 1,
                                        )
                                        val digest = MessageDigest.getInstance("SHA-256")
                                        bytesWritten = media.copyRangeTo(answer.start, answer.end, out, digest)
                                        bodySha256 = hex(digest)
                                    }

                                    is Answer.Malformed -> {
                                        status = writeEmpty(out, STATUS_BAD_REQUEST)
                                    }

                                    is Answer.Unsatisfiable -> {
                                        status =
                                            writeEmpty(
                                                out,
                                                STATUS_RANGE_NOT_SATISFIABLE,
                                                listOf(HEADER_CONTENT_RANGE to "bytes */${media.size}"),
                                            )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (failure: IOException) {
            // Mid-write failure: the record keeps the status and the confirmed byte count.
        }
        synchronized(recordLock) {
            records += ServedRequest(records.size.toLong(), method, path, range, status, bytesWritten, bodySha256)
        }
    }

    /**
     * The strict range contract: `bytes=<start>-<end>` or open-ended `bytes=<start>-`,
     * nothing else — no suffix ranges, no multi-ranges, no other units, and no RFC
     * end-clamping so out-of-bounds ends are honest `416`s, not silent truncation.
     */
    private fun answerFor(range: String?): Answer {
        if (range == null) return Answer.Full
        val match = RANGE_PATTERN.matchEntire(range.trim()) ?: return Answer.Malformed
        val start = match.groupValues[1].toLongOrNull() ?: return Answer.Malformed
        val end =
            if (match.groupValues[2].isEmpty()) {
                media.size - 1
            } else {
                match.groupValues[2].toLongOrNull() ?: return Answer.Malformed
            }
        // A start at or beyond the body can never be satisfiable — `416` before the
        // start>end shape check so `bytes=<huge>-` is unsatisfiable, not malformed.
        if (start >= media.size) return Answer.Unsatisfiable
        if (start > end) return Answer.Malformed
        if (end >= media.size) return Answer.Unsatisfiable
        return Answer.Satisfiable(start, end)
    }

    /** Lower-cased header name → trimmed value; `null` when any line is not `name: value`. */
    private fun parseHeaders(lines: List<String>): List<Pair<String, String>>? =
        lines
            .map { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return null
                line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }

    /** A full header block ending `Content-Length: 0` + `Connection: close`; returns [status]. */
    private fun writeEmpty(
        out: OutputStream,
        status: Int,
        headers: List<Pair<String, String>> = emptyList(),
    ): Int {
        writeHead(out, status, headers, 0)
        return status
    }

    private fun writeHead(
        out: OutputStream,
        status: Int,
        headers: List<Pair<String, String>>,
        contentLength: Long,
    ) {
        val text =
            buildString {
                append("HTTP/1.1 ")
                    .append(status)
                    .append(' ')
                    .append(REASONS.getValue(status))
                    .append(CRLF)
                for ((name, value) in headers) append(name).append(": ").append(value).append(CRLF)
                append("Content-Length: ").append(contentLength).append(CRLF)
                append("Connection: close").append(CRLF).append(CRLF)
            }
        out.write(text.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /** A bounded-queue refusal: headers only, then the socket closes. */
    private fun refuse(socket: Socket) {
        try {
            writeEmpty(socket.outputStream, STATUS_SERVICE_UNAVAILABLE)
        } finally {
            runCatching { socket.close() }
        }
    }

    private sealed interface Head {
        data class Complete(
            val bytes: ByteArray,
        ) : Head

        data object Oversize : Head

        data object EndOfStream : Head
    }

    private sealed interface Answer {
        data object Full : Answer

        data class Satisfiable(
            val start: Long,
            val end: Long,
        ) : Answer

        data object Malformed : Answer

        data object Unsatisfiable : Answer
    }

    companion object {
        /** The only path the fixture serves — the recorded scenario's `/audio`. */
        const val MEDIA_PATH = "/audio"

        private const val TAG = "MediaFixture"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val ACCEPT_BACKLOG = 16
        private const val MAX_WORKERS = 16
        private const val HEAD_TIMEOUT_MS = 15_000
        private const val LINGER_SECONDS = 5
        private const val DRAIN_CAP_BYTES = 1024 * 1024
        private const val DRAIN_BUDGET_MS = 1_000L
        private const val DRAIN_BUFFER_BYTES = 8 * 1024
        private const val JOIN_WAIT_MS = 2_000L
        private const val SHUTDOWN_WAIT_MS = 5_000L
        private const val BOUNDARY_WAIT_MS = 10_000L // includes the response linger and drain budgets
        private const val BOUNDARY_QUIET_MS = 100L
        private const val BOUNDARY_POLL_MS = 10L

        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val MAX_HEADER_LINES = 64
        private const val HEAD_END_BYTES = 4
        private const val HEAD_END_MARKER = 0x0D0A0D0A
        private const val CRLF = "\r\n"
        private const val HTTP_10 = "HTTP/1.0"
        private const val HTTP_11 = "HTTP/1.1"
        private const val METHOD_GET = "GET"

        private const val STATUS_OK = 200
        private const val STATUS_PARTIAL_CONTENT = 206
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_METHOD_NOT_ALLOWED = 405
        private const val STATUS_RANGE_NOT_SATISFIABLE = 416
        private const val STATUS_SERVICE_UNAVAILABLE = 503
        private val REASONS =
            mapOf(
                STATUS_OK to "OK",
                STATUS_PARTIAL_CONTENT to "Partial Content",
                STATUS_BAD_REQUEST to "Bad Request",
                STATUS_NOT_FOUND to "Not Found",
                STATUS_METHOD_NOT_ALLOWED to "Method Not Allowed",
                STATUS_RANGE_NOT_SATISFIABLE to "Range Not Satisfiable",
                STATUS_SERVICE_UNAVAILABLE to "Service Unavailable",
            )

        private const val HEADER_ALLOW = "Allow"
        private const val HEADER_ACCEPT_RANGES = "Accept-Ranges"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val HEADER_CONTENT_RANGE = "Content-Range"
        private const val HEADER_RANGE = "range"
        private const val CONTENT_TYPE_AUDIO_MP4 = "audio/mp4"
        private val RANGE_PATTERN = Regex("bytes=(\\d+)-(\\d*)")

        private fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }
    }
}
