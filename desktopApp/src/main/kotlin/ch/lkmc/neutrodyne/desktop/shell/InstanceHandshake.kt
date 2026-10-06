// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

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
 * channel, and the `finally` removes `instance.port` / `instance.token` again.
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
                        handle(socket, token, onHandoff)
                    } catch (e: IOException) {
                        // One bad connection never stops the server.
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
        socket.socket().soTimeout = READ_TIMEOUT.inWholeMilliseconds.toInt()
        val line = runCatching { readLineCapped(socket) }.getOrNull()
        val request = line?.let { runCatching { SHELL_JSON.decodeFromString(HandoffRequest.serializer(), it) }.getOrNull() }
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
        val sent = runCatching { writeLine(socket, SHELL_JSON.encodeToString(HandoffResponse.serializer(), response)) }.isSuccess
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
    fun send(request: HandoffRequest, timeout: Duration = replyTimeout): HandoffOutcome {
        allowSetForegroundOnWindows()
        repeat(deliveryAttempts) { attempt ->
            if (attemptOnce(request, timeout) == HandoffOutcome.Delivered) return HandoffOutcome.Delivered
            if (attempt < deliveryAttempts - 1) Thread.sleep(retryDelay.inWholeMilliseconds)
        }
        return HandoffOutcome.NoAnswer
    }

    private fun attemptOnce(request: HandoffRequest, timeout: Duration): HandoffOutcome {
        val port = readPort() ?: return HandoffOutcome.NoAnswer
        // Each attempt re-reads the owner's token: a delivery started while the owner was still
        // publishing its files picks the fresh token up on a later retry (11's Retry row).
        val effective = readToken()?.let { request.copy(token = it) } ?: request
        try {
            SocketChannel.open().use { socket ->
                socket.socket().connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    connectTimeout.inWholeMilliseconds.toInt(),
                )
                socket.socket().soTimeout = timeout.inWholeMilliseconds.toInt()
                writeLine(socket, SHELL_JSON.encodeToString(HandoffRequest.serializer(), effective))
                val reply = runCatching { readLineCapped(socket) }.getOrNull() ?: return HandoffOutcome.NoAnswer
                val response = runCatching {
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

    private fun readPort(): Int? =
        runCatching { Files.readString(portFile).trim().toIntOrNull() }.getOrNull()

    private fun readToken(): String? =
        runCatching { Files.readString(tokenFile).trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    /** Reads one `\n`-terminated line, or `null` on EOF, timeout or breach of the size cap. */
    private fun readLineCapped(socket: SocketChannel): String? {
        val bytes = ByteArrayOutputStream()
        val one = ByteBuffer.allocate(1)
        while (bytes.size() < HandoffRequest.MAX_LINE_BYTES) {
            one.clear()
            if (socket.read(one) < 0) return null
            val byte = one.get(0)
            if (byte == NEWLINE) return bytes.toString(Charsets.UTF_8).trimEnd(CARRIAGE)
            bytes.write(byte.toInt())
        }
        return null // oversize: the connection is closed without a reply
    }

    private fun writeLine(socket: SocketChannel, line: String) {
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.wrap(payload)
        while (buffer.hasRemaining()) socket.write(buffer)
    }

    /** ASFW_ANY: only the foreground process may hand the owner the right to come to the front. */
    private fun allowSetForegroundOnWindows() {
        if (AppDirs.DesktopOs.current() != AppDirs.DesktopOs.WINDOWS) return
        // JNA's platform User32 does not declare this entry point (checked 2026-10-06), so it is
        // declared here on our own tiny mapping — loaded only on Windows.
        runCatching {
            com.sun.jna.Native.load("user32", AsfwUser32::class.java).AllowSetForegroundWindow(ASFW_ANY)
        }
    }

    private interface AsfwUser32 : com.sun.jna.win32.StdCallLibrary {
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
