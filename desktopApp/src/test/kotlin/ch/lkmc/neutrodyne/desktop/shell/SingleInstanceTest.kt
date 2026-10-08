// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Two in-process instances against one temporary `AppDirs` (11 Single instance and handshake;
 * the M0b cases of `SingleInstanceTest`): the second launch delivers its arguments to the owner
 * and exits, a wrong token or an oversize line is rejected without a reply, and an owner that
 * never answers means retries and then failure.
 */
class SingleInstanceTest {
    private val dirs = tempAppDirs("nd-single-instance").also { Files.createDirectories(it.state) }
    private val portFile = dirs.state.resolve(InstanceHandshake.PORT_FILE_NAME)
    private val tokenFile = dirs.state.resolve(InstanceHandshake.TOKEN_FILE_NAME)

    /** Small timeouts so the failure paths finish in milliseconds, not seconds. */
    private fun fastHandshake(
        attempts: Int = 2,
        retryDelayMs: Long = 10,
        replyTimeoutMs: Long = 300,
    ): InstanceHandshake =
        InstanceHandshake(
            dirs = dirs,
            random = SecureRandom(),
            versionName = VERSION_NAME,
            connectTimeout = 300.milliseconds,
            replyTimeout = replyTimeoutMs.milliseconds,
            deliveryAttempts = attempts,
            retryDelay = retryDelayMs.milliseconds,
        )

    @Test
    fun `a second launch delivers its arguments to the owner and exits`() =
        runBlocking {
            // Stale files from an earlier owner are overwritten when this owner serves.
            Files.writeString(portFile, "1")
            Files.writeString(tokenFile, "stale")

            val owner = SingleInstanceLock(dirs)
            assertThat(owner.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)

            val received = Channel<HandoffRequest>(Channel.UNLIMITED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val serveJob = scope.launch { fastHandshake().serve { received.send(it) } }

            val second = SingleInstanceLock(dirs)
            assertThat(second.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.HeldByOther)

            val client = fastHandshake(attempts = 100, retryDelayMs = 50, replyTimeoutMs = 1_000)
            val request =
                HandoffRequest.fromLaunchArgs(
                    token = InstanceHandshake.readToken(dirs) ?: "",
                    args = listOf("feed:https://example.org/feed", "/home/tester/import.opml"),
                    cwd = "/home/tester",
                )
            assertThat(client.send(request)).isEqualTo(HandoffOutcome.Delivered)

            val delivered = withTimeout(5.seconds) { received.receive() }
            assertThat(delivered.args)
                .containsExactly("feed:https://example.org/feed", "/home/tester/import.opml")
                .inOrder()
            assertThat(delivered.cwd).isEqualTo("/home/tester")
            assertThat(delivered.activate).isTrue()

            // Stopping the server removes its files, and closing the lock frees it again.
            serveJob.cancelAndJoin()
            assertThat(Files.exists(portFile)).isFalse()
            assertThat(Files.exists(tokenFile)).isFalse()
            owner.close()
            val third = SingleInstanceLock(dirs)
            assertThat(third.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)
            third.close()
            scope.cancel()
        }

    @Test
    fun `an owner still publishing its files is retried until it answers`() =
        runBlocking {
            val owner = SingleInstanceLock(dirs)
            assertThat(owner.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)

            val received = Channel<HandoffRequest>(Channel.UNLIMITED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val serveJob =
                scope.launch {
                    delay(OWNER_START_DELAY_MS)
                    fastHandshake().serve { received.send(it) }
                }

            val client = fastHandshake(attempts = 200, retryDelayMs = 25, replyTimeoutMs = 1_000)
            val request =
                HandoffRequest.fromLaunchArgs(
                    token = "",
                    args = listOf("neutrodyne://open/queue"),
                    cwd = "/home/tester",
                )
            val outcome = client.send(request)
            assertThat(outcome).isEqualTo(HandoffOutcome.Delivered)
            withTimeout(5.seconds) { received.receive() }

            serveJob.cancelAndJoin()
            owner.close()
            scope.cancel()
        }

    @Test
    fun `a wrong token is rejected without a reply`() =
        runBlocking {
            val owner = SingleInstanceLock(dirs)
            assertThat(owner.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)

            val received = Channel<HandoffRequest>(Channel.UNLIMITED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val serveJob = scope.launch { fastHandshake().serve { received.send(it) } }
            val port = awaitPortFile()

            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS.toInt())
                socket.soTimeout = READ_TIMEOUT_MS.toInt()
                val request = HandoffRequest(token = "definitely-not-the-token", args = listOf("feed:x"), cwd = "/w")
                socket.getOutputStream().write(
                    (SHELL_JSON.encodeToString(HandoffRequest.serializer(), request) + "\n").toByteArray(),
                )
                // Closed without a reply: EOF or a reset write, never a response line.
                val read = runCatching { socket.getInputStream().read() }
                assertThat(read.getOrDefault(-1)).isEqualTo(-1)
            }
            assertThat(withTimeoutOrNull(500.milliseconds) { received.receive() }).isNull()

            serveJob.cancelAndJoin()
            owner.close()
            scope.cancel()
        }

    @Test
    fun `an oversize line is rejected without a reply`() =
        runBlocking {
            val owner = SingleInstanceLock(dirs)
            assertThat(owner.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)

            val received = Channel<HandoffRequest>(Channel.UNLIMITED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val serveJob = scope.launch { fastHandshake().serve { received.send(it) } }
            val port = awaitPortFile()

            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS.toInt())
                socket.soTimeout = READ_TIMEOUT_MS.toInt()
                // Larger than 11's 64 KiB line cap; the token is irrelevant — the size alone rejects.
                val filler = "x".repeat(HandoffRequest.MAX_LINE_BYTES + 1024)
                runCatching {
                    socket.getOutputStream().write((filler + "\n").toByteArray())
                }
                val read = runCatching { socket.getInputStream().read() }
                assertThat(read.getOrDefault(-1)).isEqualTo(-1)
            }
            assertThat(withTimeoutOrNull(500.milliseconds) { received.receive() }).isNull()

            serveJob.cancelAndJoin()
            owner.close()
            scope.cancel()
        }

    @Test
    fun `an idle connection is dropped at the read deadline and a later hand-off still arrives`() =
        runBlocking {
            val owner = SingleInstanceLock(dirs)
            assertThat(owner.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)

            val received = Channel<HandoffRequest>(Channel.UNLIMITED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val serveJob = scope.launch { fastHandshake().serve { received.send(it) } }
            val port = awaitPortFile()

            // The reported reproduction: a peer that connects and sends nothing. SO_TIMEOUT does
            // not apply to SocketChannel.read, so only a real deadline frees the sequential
            // server for the next connection.
            val idle = Socket()
            idle.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                CONNECT_TIMEOUT_MS.toInt(),
            )

            val client = fastHandshake(attempts = 400, retryDelayMs = 15, replyTimeoutMs = 250)
            val request =
                HandoffRequest.fromLaunchArgs(
                    token = "", // send() re-reads instance.token on every attempt
                    args = listOf("feed:x"),
                    cwd = "/w",
                )
            // send() is blocking; run it on its own thread with a hard bound so a regression is a
            // failure here, not a hang.
            val sender = Executors.newSingleThreadExecutor()
            val outcome =
                try {
                    sender
                        .submit(Callable { client.send(request) })
                        .get(IDLE_PEER_BOUND_MS, TimeUnit.MILLISECONDS)
                } finally {
                    sender.shutdownNow()
                }
            assertThat(outcome).isEqualTo(HandoffOutcome.Delivered)
            assertThat(withTimeout(5.seconds) { received.receive() }.args).containsExactly("feed:x")

            // The idle peer was dropped without a reply (11's "timeout → close" rule): a real EOF,
            // not a local read timeout.
            idle.soTimeout = IDLE_PEER_BOUND_MS.toInt()
            assertThat(idle.inputStream.read()).isEqualTo(-1)
            idle.close()

            serveJob.cancelAndJoin()
            owner.close()
            scope.cancel()
        }

    @Test
    fun `a partial line is dropped at the read deadline without a reply`() =
        runBlocking {
            val owner = SingleInstanceLock(dirs)
            assertThat(owner.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val serveJob = scope.launch { fastHandshake().serve { } }
            val port = awaitPortFile()

            val peer = Socket()
            peer.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                CONNECT_TIMEOUT_MS.toInt(),
            )
            peer.soTimeout = PARTIAL_BOUND_MS.toInt()
            // Half a request line, then silence: the deadline closes it without a reply.
            peer.outputStream.write("{\"v\":1,\"tok".toByteArray())
            peer.outputStream.flush()

            assertThat(peer.inputStream.read()).isEqualTo(-1)
            peer.close()

            serveJob.cancelAndJoin()
            owner.close()
            scope.cancel()
        }

    @Test
    fun `shutdown cancels the server while a peer is connected and idle`() =
        runBlocking {
            val owner = SingleInstanceLock(dirs)
            assertThat(owner.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val serveJob = scope.launch { fastHandshake().serve { } }
            val port = awaitPortFile()

            val idle = Socket()
            idle.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                CONNECT_TIMEOUT_MS.toInt(),
            )
            // Let the accepted connection settle into its blocking read before the cancel.
            delay(300)

            val stopped =
                withTimeoutOrNull(SHUTDOWN_BOUND_MS.milliseconds) {
                    serveJob.cancelAndJoin()
                    true
                }
            assertThat(stopped).isNotNull()
            assertThat(Files.exists(portFile)).isFalse()
            assertThat(Files.exists(tokenFile)).isFalse()

            idle.close()
            owner.close()
            scope.cancel()
        }

    @Test
    fun `a listener that never answers fails the delivery instead of hanging the client`() {
        // A stale instance.port may point at a process that accepts but never replies; send() must
        // still observe the reply timeout and come back with NoAnswer (11's Client row).
        val silent = ServerSocket(0)
        val accepted = AtomicReference<Socket>()
        thread(isDaemon = true) { runCatching { accepted.set(silent.accept()) } }

        Files.writeString(portFile, silent.localPort.toString())
        Files.writeString(tokenFile, "token")
        val client = fastHandshake(attempts = 3, retryDelayMs = 10, replyTimeoutMs = 300)
        val request = HandoffRequest(token = "token", args = listOf("feed:x"), cwd = "/w")

        val sender = Executors.newSingleThreadExecutor()
        val outcome =
            try {
                sender
                    .submit(Callable { client.send(request) })
                    .get(SILENT_LISTENER_BOUND_MS, TimeUnit.MILLISECONDS)
            } finally {
                sender.shutdownNow()
                runCatching { accepted.get()?.close() }
                silent.close()
            }
        assertThat(outcome).isEqualTo(HandoffOutcome.NoAnswer)
    }

    @Test
    fun `a throwing hand-off callback never stops the serve loop`() =
        runBlocking {
            val owner = SingleInstanceLock(dirs)
            assertThat(owner.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)

            var calls = 0
            val received = Channel<HandoffRequest>(Channel.UNLIMITED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val serveJob =
                scope.launch {
                    fastHandshake().serve {
                        calls++
                        if (calls == 1) throw RuntimeException("the hand-off callback blew up")
                        received.send(it)
                    }
                }

            awaitPortFile()
            val client = fastHandshake(attempts = 50, retryDelayMs = 20, replyTimeoutMs = 1_000)
            val request =
                HandoffRequest.fromLaunchArgs(
                    token = InstanceHandshake.readToken(dirs) ?: "",
                    args = listOf("feed:a"),
                    cwd = "/w",
                )
            // The reply is sent before the callback runs, so the crashing call still answers.
            assertThat(client.send(request)).isEqualTo(HandoffOutcome.Delivered)
            assertThat(client.send(request)).isEqualTo(HandoffOutcome.Delivered)
            assertThat(withTimeout(5.seconds) { received.receive() }.args).containsExactly("feed:a")

            serveJob.cancelAndJoin()
            owner.close()
            scope.cancel()
        }

    @Test
    fun `the hand-off callback does not run on the caller's thread`() =
        runBlocking {
            val owner = SingleInstanceLock(dirs)
            assertThat(owner.tryAcquire()).isEqualTo(SingleInstanceLock.Acquire.Acquired)

            // A single-threaded caller lane, like the UI's: blocking hand-off work must
            // never occupy it.
            val lane =
                Executors.newSingleThreadExecutor { task ->
                    Thread(task, "caller-lane").apply { isDaemon = true }
                }
            val callerThread = lane.submit(Callable { Thread.currentThread() }).get()
            var callbackThread: Thread? = null
            val received = Channel<HandoffRequest>(Channel.UNLIMITED)
            val scope = CoroutineScope(SupervisorJob() + lane.asCoroutineDispatcher())
            val serveJob =
                scope.launch {
                    fastHandshake().serve {
                        callbackThread = Thread.currentThread()
                        received.send(it)
                    }
                }

            awaitPortFile()
            val client = fastHandshake(attempts = 50, retryDelayMs = 20, replyTimeoutMs = 1_000)
            val request =
                HandoffRequest.fromLaunchArgs(
                    token = InstanceHandshake.readToken(dirs) ?: "",
                    args = listOf("feed:a"),
                    cwd = "/w",
                )
            assertThat(client.send(request)).isEqualTo(HandoffOutcome.Delivered)
            withTimeout(5.seconds) { received.receive() }
            assertThat(callbackThread).isNotSameInstanceAs(callerThread)

            serveJob.cancelAndJoin()
            owner.close()
            scope.cancel()
            lane.shutdown()
        }

    @Test
    fun `a stale port file means retries and then failure`() {
        val deadPort = ServerSocket(0).use { it.localPort }
        Files.writeString(portFile, deadPort.toString())
        Files.writeString(tokenFile, "token")
        val client = fastHandshake(attempts = 3, retryDelayMs = 5, replyTimeoutMs = 200)
        val outcome = client.send(HandoffRequest(token = "token", args = listOf("feed:x"), cwd = "/w"))
        assertThat(outcome).isEqualTo(HandoffOutcome.NoAnswer)
    }

    @Test
    fun `no port file at all also means failure`() {
        val client = fastHandshake(attempts = 2, retryDelayMs = 5)
        val outcome = client.send(HandoffRequest(token = "", args = emptyList(), cwd = "/w"))
        assertThat(outcome).isEqualTo(HandoffOutcome.NoAnswer)
    }

    @Test
    fun `fromLaunchArgs caps inputs to 20 x 4 KiB and the line to 64 KiB`() {
        val many = HandoffRequest.fromLaunchArgs(token = "t", args = (1..30).map { "arg-$it" }, cwd = "/w")
        assertThat(many.args).hasSize(HandoffRequest.MAX_ARGS)

        val longInput = HandoffRequest.fromLaunchArgs(token = "t", args = listOf("x".repeat(10_000)), cwd = "/w")
        assertThat(longInput.args.single()).hasLength(HandoffRequest.MAX_ARG_CHARS)

        // Twenty 4-KiB inputs would exceed the 64-KiB line cap: inputs drop from the end until it fits.
        val fatArgs = (1..20).map { "y".repeat(HandoffRequest.MAX_ARG_CHARS) }
        val fat = HandoffRequest.fromLaunchArgs(token = "t", args = fatArgs, cwd = "/w")
        val serialised = SHELL_JSON.encodeToString(HandoffRequest.serializer(), fat).toByteArray().size
        assertThat(serialised + 1).isAtMost(HandoffRequest.MAX_LINE_BYTES)
        assertThat(fat.args.size).isLessThan(HandoffRequest.MAX_ARGS)
    }

    /** The owner binds within milliseconds of taking the lock; the test still polls briefly. */
    private fun awaitPortFile(): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            // The stale file this test wrote first carries port 1; only a real port counts.
            val port = runCatching { Files.readString(portFile).trim().toIntOrNull() }.getOrNull()
            if (port != null && port > 1) return port
            Thread.sleep(20)
        }
        throw AssertionError("the owner never wrote $portFile")
    }

    private companion object {
        const val VERSION_NAME = "0.1.0-test"
        const val OWNER_START_DELAY_MS = 300L
        const val CONNECT_TIMEOUT_MS = 2_000L
        const val READ_TIMEOUT_MS = 1_500L
        const val IDLE_PEER_BOUND_MS = 10_000L
        const val PARTIAL_BOUND_MS = 10_000L
        const val SHUTDOWN_BOUND_MS = 5_000L
        const val SILENT_LISTENER_BOUND_MS = 5_000L
    }
}
