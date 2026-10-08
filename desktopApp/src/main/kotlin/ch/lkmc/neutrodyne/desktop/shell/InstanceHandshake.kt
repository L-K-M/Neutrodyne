// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The single-instance hand-off channel (11 Single instance and handshake): the owner serves on
 * `127.0.0.1` (port 0 → an ephemeral port) and writes `instance.port` / `instance.token`; a second
 * launch sends its inputs as one JSON line and exits.
 *
 * Rules implemented here: loopback only; the token is 32 random bytes, base64url, written
 * atomically with mode `0600` on POSIX; each connection carries one JSON line ≤ 64 KiB within
 * 2 s; the token compares in constant time; wrong token, oversize or timeout close without a
 * reply (logged at WARN); the client connects with a 1-s timeout, waits ≤ [replyTimeout] for the
 * answer and retries a refused or unanswered delivery [deliveryAttempts] times every [retryDelay]
 * (the owner binds within milliseconds of taking the lock, but may be busy starting). The owner
 * invokes [serve]'s callback in arrival order after replying, so the sender can exit at once.
 *
 * The `serve` loop is cancellable: `runInterruptible` turns coroutine cancellation into a closed
 * channel, and the `finally` removes `instance.port` / `instance.token` again. Per-connection
 * reads are bounded and cancellable the same way — `SO_TIMEOUT` does not apply to `SocketChannel`
 * reads, so the line budget is a coroutine timeout around an interruptible read (the interrupt
 * closes the channel). Server writes are cancellable but have no independent deadline. The
 * client uses a plain `Socket`, where `SO_TIMEOUT` does apply, re-armed to the remaining reply
 * budget before each read; its request write is not deadline-bounded.
 */
class InstanceHandshake(
    private val dirs: AppDirs,
    private val random: SecureRandom,
    private val versionName: String,
    private val connectTimeout: Duration = CONNECT_TIMEOUT,
    private val replyTimeout: Duration = REPLY_TIMEOUT,
    private val deliveryAttempts: Int = DELIVERY_ATTEMPTS,
    private val retryDelay: Duration = RETRY_DELAY,
) {
    private val portFile = dirs.state.resolve(PORT_FILE_NAME)
    private val tokenFile = dirs.state.resolve(TOKEN_FILE_NAME)

    /** The owner side: bind, publish port and token, then answer hand-offs until cancelled. */
    suspend fun serve(onHandoff: suspend (HandoffRequest) -> Unit) {
        Files.createDirectories(dirs.state)
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val port = (server.localAddress as InetSocketAddress).port
            val token = newToken()
            AtomicWrites.write(portFile, port.toString(), userOnly = true)
            AtomicWrites.write(tokenFile, token, userOnly = true)
            Log.i(TAG) { "serving hand-offs on 127.0.0.1:$port" }

            try {
                while (true) {
                    val socket = runInterruptible(Dispatchers.IO) { server.accept() }
                    try {
                        // The blocking reads, the reply and the callback run on IO, never on
                        // the caller's lane — connections stay sequential, order is kept.
                        withContext(Dispatchers.IO) { handle(socket, token, onHandoff) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One bad connection (or a crashing callback) never stops the server.
                        Log.w(TAG, e) { "hand-off connection failed" }
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            } finally {
                runCatching { Files.deleteIfExists(portFile) }
                runCatching { Files.deleteIfExists(tokenFile) }
            }
        }
    }

    private suspend fun handle(
        socket: SocketChannel,
        expectedToken: String,
        onHandoff: suspend (HandoffRequest) -> Unit,
    ) {
        // `SO_TIMEOUT` does not apply to SocketChannel.read, so the line deadline is a coroutine
        // timeout around an interruptible read: on expiry (or on serve() cancellation) the
        // interrupt closes the channel, which is already the row's "close without a reply".
        val line =
            withTimeoutOrNull(READ_TIMEOUT) {
                interruptible { runCatching { readLineCapped(socket) }.getOrNull() }
            }
        val request =
            line?.let {
                runCatching {
                    SHELL_JSON.decodeFromString(
                        HandoffRequest.serializer(),
                        it,
                    )
                }.getOrNull()
            }
        if (request == null) {
            Log.w(TAG) { "hand-off rejected (no line in time, malformed JSON or oversize); connection closed" }
            return
        }
        if (!MessageDigest.isEqual(
                request.token.toByteArray(Charsets.UTF_8),
                expectedToken.toByteArray(Charsets.UTF_8),
            )
        ) {
            Log.w(TAG) { "hand-off rejected (wrong token); connection closed" }
            return
        }

        // Reply first, then queue: the sender exits 0 as soon as the answer arrives. A failed
        // reply means the sender will retry — the input is not applied twice.
        val response = HandoffResponse(ok = true, pid = ProcessHandle.current().pid(), versionName = versionName)
        val sent =
            runCatching {
                interruptible {
                    writeLine(socket, SHELL_JSON.encodeToString(HandoffResponse.serializer(), response))
                }
            }.isSuccess
        if (!sent) {
            Log.w(TAG) { "hand-off reply failed; connection closed" }
            return
        }
        Log.i(TAG) { "hand-off received: ${request.args.size} input(s), activate=${request.activate}" }
        onHandoff(request)
    }

    /**
     * The second launch side (11's Client and Retry rows): one attempt connects with a 1-s
     * timeout, sends one line and waits ≤ [timeout]; a refused or unanswered attempt is retried
     * [deliveryAttempts] times every [retryDelay]. The lock is never broken.
     */
    fun send(
        request: HandoffRequest,
        timeout: Duration = replyTimeout,
    ): HandoffOutcome {
        allowSetForegroundOnWindows()
        repeat(deliveryAttempts) { attempt ->
            if (attemptOnce(request, timeout) == HandoffOutcome.Delivered) return HandoffOutcome.Delivered
            if (attempt < deliveryAttempts - 1) Thread.sleep(retryDelay.inWholeMilliseconds)
        }
        return HandoffOutcome.NoAnswer
    }

    private fun attemptOnce(
        request: HandoffRequest,
        timeout: Duration,
    ): HandoffOutcome {
        val port = readPort() ?: return HandoffOutcome.NoAnswer
        // Each attempt re-reads the owner's token: a delivery started while the owner was still
        // publishing its files picks the fresh token up on a later retry (11's Retry row).
        val effective = readToken()?.let { request.copy(token = it) } ?: request
        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    connectTimeout.inWholeMilliseconds.toInt(),
                )
                val deadlineNanos = System.nanoTime() + timeout.inWholeNanoseconds
                writeLine(
                    socket.outputStream,
                    SHELL_JSON.encodeToString(HandoffRequest.serializer(), effective),
                )
                val reply =
                    runCatching { readLineCapped(socket, deadlineNanos) }.getOrNull()
                        ?: return HandoffOutcome.NoAnswer
                val response =
                    runCatching {
                        SHELL_JSON.decodeFromString(HandoffResponse.serializer(), reply)
                    }.getOrNull() ?: return HandoffOutcome.NoAnswer
                return if (response.ok) HandoffOutcome.Delivered else HandoffOutcome.NoAnswer
            }
        } catch (_: IOException) {
            return HandoffOutcome.NoAnswer
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun readPort(): Int? = runCatching { Files.readString(portFile).trim().toIntOrNull() }.getOrNull()

    private fun readToken(): String? =
        runCatching { Files.readString(tokenFile).trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    /**
     * Server side: the caller bounds the whole read with its deadline — `SO_TIMEOUT` does not
     * apply to `SocketChannel` reads.
     */
    private fun readLineCapped(socket: SocketChannel): String? {
        val one = ByteBuffer.allocate(1)
        return readLineCapped {
            one.clear()
            if (socket.read(one) < 0) -1 else one.get(0).toInt() and 0xFF
        }
    }

    /**
     * Client side: `SO_TIMEOUT` bounds each blocking `read` on a plain [Socket], so re-arm it
     * with the remaining reply budget before every byte — the whole answer lands within the
     * timeout even if the owner drips bytes (11's Client row).
     */
    private fun readLineCapped(
        socket: Socket,
        deadlineNanos: Long,
    ): String? {
        val input = socket.inputStream
        return readLineCapped {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) {
                -1
            } else {
                socket.soTimeout = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                input.read()
            }
        }
    }

    /**
     * Reads one `\n`-terminated line through [readByte] (next byte 0–255, -1 at end of stream)
     * and returns it without the terminator, or `null` on EOF or breach of the size cap.
     */
    private fun readLineCapped(readByte: () -> Int): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < HandoffRequest.MAX_LINE_BYTES) {
            val byte = readByte()
            if (byte < 0) return null
            if (byte.toByte() == NEWLINE) return bytes.toString(Charsets.UTF_8).trimEnd(CARRIAGE)
            bytes.write(byte)
        }
        return null // oversize: the connection is closed without a reply
    }

    /**
     * Runs a blocking channel call on the current IO lane with coroutine cancellation turned
     * into a thread interrupt — the interrupt closes the channel, which is what unblocks the
     * call. The block also clears the consumed interrupt before the library unwinds.
     */
    private suspend fun <T> interruptible(block: () -> T): T =
        runInterruptible {
            try {
                block()
            } finally {
                Thread.interrupted()
            }
        }

    private fun writeLine(
        socket: SocketChannel,
        line: String,
    ) {
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.wrap(payload)
        while (buffer.hasRemaining()) socket.write(buffer)
    }

    private fun writeLine(
        output: OutputStream,
        line: String,
    ) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** ASFW_ANY: only the foreground process may hand the owner the right to come to the front. */
    private fun allowSetForegroundOnWindows() {
        if (AppDirs.DesktopOs.current() != AppDirs.DesktopOs.WINDOWS) return
        // JNA's platform User32 does not declare this entry point (checked 2026-10-06), so it is
        // declared here on our own tiny mapping — loaded only on Windows.
        runCatching {
            com.sun.jna.Native
                .load("user32", AsfwUser32::class.java)
                .AllowSetForegroundWindow(ASFW_ANY)
        }
    }

    private interface AsfwUser32 : com.sun.jna.win32.StdCallLibrary {
        // JNA binds by method name, so it must match the user32 export exactly.
        @Suppress("ktlint:standard:function-naming", "FunctionName")
        fun AllowSetForegroundWindow(dwProcessId: Int): Boolean
    }

    companion object {
        internal const val PORT_FILE_NAME = "instance.port"
        internal const val TOKEN_FILE_NAME = "instance.token"

        /** 32 bytes → 43 base64url characters: a 256-bit token (11 Security rules). */
        internal const val TOKEN_BYTES = 32

        private const val TAG = "Handshake"
        private const val NEWLINE = '\n'.code.toByte()
        private const val CARRIAGE = '\r'
        private const val ASFW_ANY = -1

        /** 11's timing rules: read one line within 2 s; the client waits ≤ 3 s per attempt. */
        internal val READ_TIMEOUT = 2.seconds
        internal val CONNECT_TIMEOUT = 1.seconds
        internal val REPLY_TIMEOUT = 3.seconds
        internal const val DELIVERY_ATTEMPTS = 10
        internal val RETRY_DELAY = 200.milliseconds

        /** The owner's token, read by a second launch to fill [HandoffRequest.token]. */
        fun readToken(dirs: AppDirs): String? =
            runCatching { Files.readString(dirs.state.resolve(TOKEN_FILE_NAME)).trim() }
                .getOrNull()
                ?.takeIf(String::isNotEmpty)
    }
}
