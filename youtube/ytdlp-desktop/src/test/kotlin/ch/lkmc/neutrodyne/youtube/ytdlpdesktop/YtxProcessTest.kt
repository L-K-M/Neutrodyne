// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.engine.BundledEngineStore
import ch.lkmc.neutrodyne.youtube.engine.HostExit
import ch.lkmc.neutrodyne.youtube.engine.HostState
import ch.lkmc.neutrodyne.youtube.engine.YtDlpErrorMapper
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC3: the stdio child's failure modes map to the engine's transient/unavailable behaviour —
 * kill mid-call, a hang past deadline+grace, and an injected Python crash each surface as
 * `Transient`, the next call restarts the child, and an idle child exits on its own. The
 * clock and every timeout are injected so the suite runs in seconds, not minutes.
 */
class YtxProcessTest {
    private val roots = mutableListOf<File>()

    @AfterTest fun cleanup() {
        roots.forEach { it.deleteRecursively() }
        roots.clear()
    }

    private fun fixture(): StdioFixture = StdioFixture(tempRoot("proc").also { roots += it })

    @Test fun forcedShutdownTimeoutIsReportedWithoutThrowing() {
        val warnings = CopyOnWriteArrayList<String>()
        val process =
            object : FakeProcess(byteArrayOf()) {
                var killed = false
                var waitMs = 0L

                override fun destroyForcibly(): Process = apply { killed = true }

                override fun waitFor(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean {
                    waitMs = unit.toMillis(timeout)
                    return false
                }
            }
        val child =
            YtxChild(
                process,
                {},
                object : YtxChild.Listener {
                    override fun onMessage(message: JsonObject) = Unit

                    override fun onProtocolViolation(reason: String) = Unit

                    override fun onExit(code: Int) = Unit
                },
            )
        Log.install(LogSink { level, _, message -> if (level == LogLevel.WARN) warnings += message })
        try {
            val result = runCatching { child.destroyForcibly() }

            assertNull(result.exceptionOrNull(), "shutdown must let its callers finish transport bookkeeping")
            assertTrue(process.killed)
            assertEquals(2_000L, process.waitMs)
            assertTrue(warnings.any { it.contains("did not exit") && it.contains("2000") })
        } finally {
            Log.install()
        }
    }

    @Test fun interruptedForcedShutdownPreservesTheInterruptWithoutThrowing() {
        val child =
            YtxChild(
                object : FakeProcess(byteArrayOf()) {
                    override fun destroyForcibly(): Process = this

                    override fun waitFor(
                        timeout: Long,
                        unit: TimeUnit,
                    ): Boolean = throw InterruptedException("fixture")
                },
                {},
                object : YtxChild.Listener {
                    override fun onMessage(message: JsonObject) = Unit

                    override fun onProtocolViolation(reason: String) = Unit

                    override fun onExit(code: Int) = Unit
                },
            )
        try {
            val result = runCatching { child.destroyForcibly() }

            assertNull(result.exceptionOrNull())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun applicationScopeShutdownWaitsForTheReadyChildToExit() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport(idleStopMs = 60_000)
            var child: YtxChild? = null
            try {
                assertEquals(ECHO_RESULT, transport.callForTest("_echo", ECHOED, fixture.deadline(30_000)))
                child = assertNotNull(fixture.spawner.last)
                assertTrue(child.isAlive)

                fixture.scope.coroutineContext[Job]!!.cancelAndJoin()

                // ShellJourney removes the engine files as soon as the application scope joins.
                assertFalse(child.isAlive, "the child must stop before its data tree can be removed")
                assertEquals(HostExit.SHUTDOWN, transport.status.value.lastExit)
            } finally {
                transport.shutdown()
                child?.destroyForcibly()
                child?.awaitExit(10_000)
                fixture.scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }

    @Test fun applicationScopeShutdownWaitsForTheCompilingChildToExit() =
        runBlocking {
            val fixture = fixture()
            val store = BundledEngineStore(fixture.paths, 1, fixture.clock)
            store.prepareBundled()
            val transport =
                StdioYtxTransport(
                    fixture.spawner,
                    store,
                    fixture.settings,
                    arming = null,
                    debugHooks = true,
                    clock = fixture.clock,
                    scope = fixture.scope,
                    ping = fixture.ping,
                )
            var child: YtxChild? = null
            try {
                fixture.scope.async {
                    runCatching { transport.call(YtxMethod.PING, fixture.ping.toJson(), fixture.deadline(120_000)) }
                }
                withTimeout(30_000) { transport.status.first { it.pid != null } }
                child = assertNotNull(fixture.spawner.last)
                assertTrue(child.isAlive)

                fixture.scope.coroutineContext[Job]!!.cancelAndJoin()

                assertFalse(child.isAlive, "compile must stop before its library can be removed")
            } finally {
                transport.shutdown()
                child?.destroyForcibly()
                child?.awaitExit(10_000)
                fixture.scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }

    @Test fun applicationScopeShutdownReapsTheChildInsideItsIdleGracePeriod() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val spawner =
                SpySpawner(
                    YtxProcess(
                        { fixture.python },
                        fixture.paths,
                        spawn = { _, _, _ ->
                            ProcessBuilder(fixture.python.executable.absolutePath, "-I", "-c", SLOW_EOF_CHILD).start()
                        },
                    ),
                )
            val transport =
                StdioYtxTransport(
                    spawner,
                    fixture.store,
                    fixture.settings,
                    arming = null,
                    debugHooks = true,
                    clock = fixture.clock,
                    scope = fixture.scope,
                    ping = fixture.ping,
                    idleStopMs = 100,
                )
            var child: YtxChild? = null
            try {
                transport.prewarm(PrewarmReason.SCREEN)
                withTimeout(30_000) { transport.status.first { it.state == HostState.READY } }
                child = assertNotNull(spawner.last)
                withTimeout(10_000) { transport.status.first { it.lastExit == HostExit.IDLE_STOP } }
                assertTrue(child.isAlive, "the fixture deliberately ignores stdin EOF")

                fixture.scope.coroutineContext[Job]!!.cancelAndJoin()

                assertFalse(child.isAlive, "cancellation must retain the idle child's kill fallback")
            } finally {
                transport.shutdown()
                child?.destroyForcibly()
                child?.awaitExit(10_000)
                fixture.scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }

    @Test fun killedChildMidCallYieldsTransientAndNextCallRestarts() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport()
            // The supervisor scope keeps the call's failure out of runBlocking's parent Job.
            val call =
                fixture.scope.async {
                    transport.callForTest("_hang", EMPTY, fixture.deadline(60_000))
                }
            withTimeout(15_000) {
                while (transport.status.value.pid == null) delay(20)
            }
            val child = fixture.spawner.last
            assertNotNull(child)
            child.destroyForcibly()
            assertTransient(codeOf { call.await() }, TransientKind.ENGINE_UNAVAILABLE)

            // The next call spawns a fresh generation and answers normally.
            val echoed = transport.callForTest("_echo", ECHOED, fixture.deadline(30_000))
            assertEquals(ECHO_RESULT, echoed)
            assertEquals(2, fixture.spawner.starts)
        }

    @Test fun callHungPastDeadlinePlusGraceKillsChildAndYieldsTimeout() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport(hangGraceMs = 150)
            val code =
                codeOf {
                    withTimeout(30_000) {
                        transport.callForTest("_hang", EMPTY, fixture.deadline(300))
                    }
                }
            assertTransient(code, TransientKind.TIMEOUT)
            assertEquals(HostExit.HANG_KILL, transport.status.value.lastExit)
            withTimeout(10_000) { while (fixture.spawner.last?.isAlive == true) delay(20) }

            val echoed = transport.callForTest("_echo", ECHOED, fixture.deadline(30_000))
            assertEquals(ECHO_RESULT, echoed)
        }

    @Test fun injectedPythonCrashYieldsTransientAndRestarts() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport()
            val code =
                codeOf {
                    withTimeout(30_000) {
                        transport.callForTest("_crash", EMPTY, fixture.deadline(60_000))
                    }
                }
            assertTransient(code, TransientKind.ENGINE_UNAVAILABLE)
            assertEquals(HostExit.CRASH, transport.status.value.lastExit)

            val echoed = transport.callForTest("_echo", ECHOED, fixture.deadline(30_000))
            assertEquals(ECHO_RESULT, echoed)
            assertEquals(2, fixture.spawner.starts)
        }

    @Test fun idleChildExitsThreeMinutesAfterTheLastCall() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport(idleStopMs = 400)
            val echoed = transport.callForTest("_echo", ECHOED, fixture.deadline(30_000))
            assertEquals(ECHO_RESULT, echoed)
            val child = fixture.spawner.last
            assertNotNull(child)
            withTimeout(15_000) {
                while (transport.status.value.state != HostState.STOPPED) delay(50)
            }
            assertEquals(HostExit.IDLE_STOP, transport.status.value.lastExit)
            withTimeout(10_000) { while (child.isAlive) delay(20) }
            assertFalse(child.isAlive)
        }

    @Test fun malformedChildLineIsAProtocolViolationNotADispatch() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport()
            val code =
                codeOf {
                    withTimeout(30_000) {
                        transport.callForTest(
                            "_emit_raw",
                            buildJsonObject { put("raw", "this is not a protocol frame") },
                            fixture.deadline(60_000),
                        )
                    }
                }
            assertTransient(code, TransientKind.ENGINE_UNAVAILABLE)
            assertEquals(HostExit.PROTOCOL_VIOLATION, transport.status.value.lastExit)

            val echoed = transport.callForTest("_echo", ECHOED, fixture.deadline(30_000))
            assertEquals(ECHO_RESULT, echoed)
        }

    @Test fun missingPythonReportsUnusableHostNotACrash() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport =
                StdioYtxTransport(
                    object : YtxSpawner {
                        override fun start(
                            libDir: File,
                            engineVersion: String,
                            replayDir: File?,
                            debugHooks: Boolean,
                            listener: YtxChild.Listener,
                        ): YtxChild = throw PythonUnavailableException()
                    },
                    fixture.store,
                    fixture.settings,
                    arming = null,
                    debugHooks = true,
                    clock = fixture.clock,
                    scope = fixture.scope,
                    ping = fixture.ping,
                    idleStopMs = 400,
                )
            val code = codeOf { transport.callForTest("_echo", ECHOED, fixture.deadline(10_000)) }
            assertEquals("ENGINE_UNAVAILABLE", code)
            assertEquals(HostState.UNUSABLE, transport.status.value.state)
        }

    /** `YtxChild` reads: an overlong line is a violation, never a dispatch. */
    @Test fun oversizedLineReportsProtocolViolation() {
        val violations = CountDownLatch(1)
        val exits = CountDownLatch(1)
        val oversized = ByteArray(300 * 1024) { 'x'.code.toByte() }
        YtxChild(
            FakeProcess(oversized + '\n'.code.toByte()),
            {},
            object : YtxChild.Listener {
                override fun onMessage(message: JsonObject) = Unit

                override fun onProtocolViolation(reason: String) = violations.countDown()

                override fun onExit(code: Int) = exits.countDown()
            },
        )
        assertTrue(violations.await(10, TimeUnit.SECONDS))
        assertTrue(exits.await(10, TimeUnit.SECONDS))
    }

    @Test fun malformedLineReportsProtocolViolation() {
        val violations = CountDownLatch(1)
        YtxChild(
            // The newline makes it a complete frame; without it EOF reads as a dead writer.
            FakeProcess("{\"t\":\"ok\" unterminated\n".toByteArray()),
            {},
            object : YtxChild.Listener {
                override fun onMessage(message: JsonObject) = Unit

                override fun onProtocolViolation(reason: String) = violations.countDown()

                override fun onExit(code: Int) = Unit
            },
        )
        assertTrue(violations.await(10, TimeUnit.SECONDS))
    }

    @Test fun cancelledCallFreesItsSlotForTheNextCall() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport()
            val hanging =
                fixture.scope.async {
                    transport.callForTest("_hang", EMPTY, fixture.deadline(60_000))
                }
            withTimeout(15_000) {
                while (transport.status.value.running == 0) delay(20)
            }
            hanging.cancel()
            assertIs<CancellationException>(runCatching { hanging.await() }.exceptionOrNull())

            // The shim's cancel event ends _hang; the late ok frees the slot on this generation.
            val echoed = transport.callForTest("_echo", ECHOED, fixture.deadline(30_000))
            assertEquals(ECHO_RESULT, echoed)
            assertEquals(1, fixture.spawner.starts)
            withTimeout(15_000) {
                while (transport.status.value.running != 0) delay(20)
            }
        }

    @Test fun admissionBoundsConcurrentCallsToTheWorkerPool() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport()
            val calls =
                List(5) {
                    fixture.scope.async {
                        runCatching {
                            transport.callForTest("_hang", EMPTY, fixture.deadline(60_000))
                        }
                    }
                }
            // Calls queue before the worker pool fills. Observe the complete admission state,
            // rather than treating the first queued call as proof that four calls are running.
            val admitted =
                withTimeout(15_000) {
                    transport.status.first { it.running == 4 && it.queued == 1 }
                }
            assertEquals(4, admitted.running)
            assertEquals(1, admitted.queued)
            transport.shutdown()
            calls.forEach { assertIs<YtxCallException>(it.await().exceptionOrNull()) }
        }

    @Test fun prewarmStartsTheHostWithoutACall() =
        runBlocking {
            val fixture = fixture()
            fixture.serve(SharedEngine.lib)
            val transport = fixture.transport()
            transport.prewarm(PrewarmReason.SCREEN)
            withTimeout(30_000) {
                while (transport.status.value.state != HostState.READY) delay(50)
            }
            assertEquals(1, fixture.spawner.starts)
            transport.shutdown()
        }

    @Test fun stderrLinesAreDrainedAndRedacted() {
        val captured = CopyOnWriteArrayList<String>()
        val drained = CountDownLatch(1)
        YtxChild(
            FakeProcess(
                stdout = byteArrayOf(),
                stderr = "fetch https://media.example.invalid/video?sig=secret failed".toByteArray(),
            ),
            {
                captured += it
                drained.countDown()
            },
            object : YtxChild.Listener {
                override fun onMessage(message: JsonObject) = Unit

                override fun onProtocolViolation(reason: String) = Unit

                override fun onExit(code: Int) = Unit
            },
        )
        assertTrue(drained.await(10, TimeUnit.SECONDS))
        assertEquals("fetch <url> failed", captured.first())
    }

    /** The wire code a failed call threw — `assertFailsWith` cannot take a suspend block. */
    private suspend fun codeOf(block: suspend () -> String): String =
        try {
            block()
            error("call unexpectedly succeeded")
        } catch (failure: YtxCallException) {
            failure.code
        }

    private fun assertTransient(
        code: String,
        kind: TransientKind,
    ) {
        val result = YtDlpErrorMapper(RealClock).map(code, VIDEO)
        assertIs<ResolveResult.Transient>(result)
        assertEquals(kind, result.kind)
    }

    private companion object {
        val SLOW_EOF_CHILD =
            """
            import json, sys, time
            print(json.dumps({'t':'hello','state':'ready'}), flush=True)
            for line in sys.stdin:
                frame = json.loads(line)
                print(json.dumps({'t':'ok','id':frame['id'],'r':{'ready':True}}), flush=True)
            time.sleep(60)
            """.trimIndent()

        const val VIDEO = "aaaaaaaaaaA"
        val EMPTY = JsonObject(emptyMap())
        val ECHOED = buildJsonObject { put("probe", "ok") }
        const val ECHO_RESULT = "{\"probe\":\"ok\"}"
    }
}

/** A canned stdout/stderr stream standing in for a dead or misbehaving child's output. */
private open class FakeProcess(
    stdout: ByteArray,
    stderr: ByteArray = byteArrayOf(),
) : Process() {
    private val input = ByteArrayInputStream(stdout)
    private val output = ByteArrayOutputStream()
    private val error = ByteArrayInputStream(stderr)

    override fun getOutputStream() = output

    override fun getInputStream() = input

    override fun getErrorStream() = error

    override fun waitFor() = 0

    override fun exitValue() = 0

    override fun destroy() = Unit

    override fun isAlive() = true
}
