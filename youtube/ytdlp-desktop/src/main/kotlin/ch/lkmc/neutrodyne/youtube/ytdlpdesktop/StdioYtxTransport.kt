// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.engine.EngineCompileMarkers
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.HostExit
import ch.lkmc.neutrodyne.youtube.engine.HostKind
import ch.lkmc.neutrodyne.youtube.engine.HostState
import ch.lkmc.neutrodyne.youtube.engine.HostStatus
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.engine.YtxTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The desktop `YtxTransport` (11 "Desktop YouTube engine host"): the same semantics as
 * `BinderYtxTransport` — call-id allocation, bounded admission, absolute monotonic deadlines
 * that include queue and start time, per-id cancellation, late-reply ignore, the deadline+5 s
 * hang kill, crash/termination mapping and the 3-minute idle stop — over the documented
 * JSON-lines wire instead of Binder.
 *
 * One [YtxChild] serves all admitted calls; four slots mirror the shim's worker pool. A dead,
 * hung or misbehaving child kills the whole generation: the timed-out call reports TIMEOUT,
 * every other interrupted call ENGINE_UNAVAILABLE.
 */
class StdioYtxTransport(
    private val spawner: YtxSpawner,
    private val store: EngineStore,
    private val settings: EngineSettings,
    private val arming: StdioReplayArming?,
    private val debugHooks: Boolean,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val ping: YtxControlRequest,
    private val idleStopMs: Long = YtxLimits.IDLE_STOP_MS,
    private val startTimeoutMs: Long = YtxLimits.START_MS,
    private val compileTimeoutMs: Long = YtxLimits.COMPILE_MS,
    private val hangGraceMs: Long = YtxLimits.HANG_GRACE_MS,
) : YtxTransport {
    private class Pending(
        val result: CompletableDeferred<String> = CompletableDeferred(),
        var watchdog: Job? = null,
    )

    private val lock = Any()
    private val slots = Semaphore(WORKERS)
    private val mutableStatus = MutableStateFlow(HostStatus(HostState.STOPPED, HostKind.STDIO, WORKERS))
    override val status = mutableStatus.asStateFlow()
    private var generation = 0L
    private var nextId = 1L
    private var starting: Deferred<Result<YtxChild>>? = null
    private var child: YtxChild? = null
    private var helloChannel: Channel<JsonObject>? = null
    private var startReply: CompletableDeferred<JsonElement>? = null
    private val pending = mutableMapOf<Long, Pending>()
    private var waiting = 0
    private var idle: Job? = null

    init {
        // A child can still be compiling when the graph stops. Make its OS-level exit part
        // of the scope join, before ShellJourney removes the engine's temporary files.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { shutdown() }
            }
        }
    }

    override suspend fun call(
        method: YtxMethod,
        json: String,
        deadline: YtxDeadline,
    ): String {
        settings.awaitLoaded()
        if (!settings.state.value.enabled) fail("ENGINE_UNAVAILABLE")
        if (json.toByteArray(Charsets.UTF_8).size > YtxLimits.PAYLOAD_BYTES) fail("EXTRACTION")
        val payload =
            try {
                Json.parseToJsonElement(json).jsonObject
            } catch (_: Exception) {
                fail("EXTRACTION")
            }
        val remaining = deadline.elapsedRealtimeMs - clock.elapsedRealtime()
        if (remaining <= 0) fail("TIMEOUT")
        if (method != YtxMethod.PING && store.preparation.value != EnginePreparation.COMPILED) {
            fail("ENGINE_UNAVAILABLE")
        }
        return dispatch(method.wireName, payload, deadline)
    }

    /**
     * Module-test seam for `--debug-hooks` methods (`_hang`, `_crash`, `_echo`, `_emit_raw`):
     * the same admission/deadline/watchdog path as [call], without the API enum or the
     * preparation gate — a debug method must be injectable while the child is still proving
     * itself. Never used by production bindings.
     */
    internal suspend fun callForTest(
        wireName: String,
        payload: JsonObject,
        deadline: YtxDeadline,
    ): String {
        val remaining = deadline.elapsedRealtimeMs - clock.elapsedRealtime()
        if (remaining <= 0) fail("TIMEOUT")
        return dispatch(wireName, payload, deadline)
    }

    private suspend fun dispatch(
        wireName: String,
        payload: JsonObject,
        deadline: YtxDeadline,
    ): String {
        // Caller cancellation releases admission only after the host acknowledges or is killed.
        // Otherwise rapid cancelled searches could queue past the host's worker pool.
        var ownsSlot = false
        try {
            val remaining = deadline.elapsedRealtimeMs - clock.elapsedRealtime()
            val admitted =
                withTimeoutOrNull(remaining) {
                    synchronized(lock) {
                        waiting++
                        updateRunning()
                    }
                    try {
                        slots.acquire()
                    } finally {
                        synchronized(lock) {
                            waiting--
                            updateRunning()
                        }
                    }
                    ownsSlot = true
                    true
                }
            if (admitted != true || deadline.elapsedRealtimeMs <= clock.elapsedRealtime()) fail("TIMEOUT")
            if (!settings.state.value.enabled) fail("ENGINE_UNAVAILABLE")
            val (id, call, host) =
                synchronized(lock) {
                    idle?.cancel()
                    val id = nextId++
                    val call = Pending()
                    pending[id] = call
                    call.watchdog =
                        scope.launch {
                            val budget = (deadline.elapsedRealtimeMs - clock.elapsedRealtime()).coerceAtLeast(0)
                            delay(budget + hangGraceMs)
                            synchronized(lock) {
                                if (pending[id] === call) stop(HostExit.HANG_KILL, id)
                            }
                        }
                    updateRunning()
                    Triple(id, call, start())
                }
            ownsSlot = false
            try {
                val target = host.await().getOrThrow()
                synchronized(lock) {
                    if (pending[id] === call) {
                        val sent = target.send(callFrame(id, wireName, payload, wireDeadline(deadline)))
                        if (!sent) {
                            // A dead stdin means the process is gone; count it as a crash.
                            if (pending[id] === call) stop(HostExit.CRASH)
                        }
                    }
                }
                return call.result.await()
            } catch (_: CancellationException) {
                cancel(id)
                // Free the slot before checking who cancelled: the frame may never have
                // been sent, in which case no reply arrives to release it.
                finish(id, null, "ENGINE_UNAVAILABLE")
                // Startup can be cancelled by host death while this caller remains active.
                // Preserve caller cancellation and otherwise return the host's structured error.
                currentCoroutineContext().ensureActive()
                return call.result.await()
            } catch (_: Exception) {
                finish(id, null, "ENGINE_UNAVAILABLE")
                return call.result.await()
            }
        } finally {
            if (ownsSlot) slots.release()
        }
    }

    override fun cancel(callId: Long) {
        val frame =
            buildJsonObject {
                put("t", "cancel")
                put("id", callId)
            }
        synchronized(lock) { runCatching { child?.send(frame) } }
    }

    override fun prewarm(reason: PrewarmReason) {
        synchronized(lock) {
            idle?.cancel()
            val host = start()
            scope.launch {
                runCatching { host.await() }
                synchronized(lock) { scheduleIdle() }
            }
        }
    }

    override fun shutdown() = synchronized(lock) { stop(HostExit.SHUTDOWN) }

    private fun start(): Deferred<Result<YtxChild>> {
        starting?.let { return it }
        val epoch = generation
        val compiling = store.preparation.value != EnginePreparation.COMPILED
        mutableStatus.value =
            mutableStatus.value.copy(state = if (compiling) HostState.COMPILING else HostState.STARTING)
        return scope
            .async(start = CoroutineStart.LAZY) {
                try {
                    settings.awaitLoaded()
                    if (!settings.state.value.enabled) fail("ENGINE_UNAVAILABLE")
                    Result.success(
                        run {
                            val budget = if (compiling) compileTimeoutMs else startTimeoutMs
                            val prepared = store.hostPrepared() ?: fail("ENGINE_UNAVAILABLE")
                            val hellos = Channel<JsonObject>(Channel.UNLIMITED)
                            val spawned =
                                spawner.start(
                                    prepared.libDir.toFile(),
                                    prepared.marker.engineVersion,
                                    arming?.replayDir()?.toFile(),
                                    debugHooks,
                                    listener(epoch),
                                )
                            synchronized(lock) {
                                if (epoch != generation) {
                                    spawned.destroyForcibly()
                                    fail("ENGINE_UNAVAILABLE")
                                }
                                child = spawned
                                helloChannel = hellos
                                mutableStatus.value = mutableStatus.value.copy(pid = spawned.pid)
                            }
                            var hello = withTimeout(budget) { hellos.receive() }
                            val announcedCompile = hello.string("state") == "compiling"
                            if (announcedCompile) {
                                mutableStatus.value = mutableStatus.value.copy(state = HostState.COMPILING)
                                // The child owns compile progress; the budget promotion covers it.
                                hello = withTimeout(compileTimeoutMs) { hellos.receive() }
                            }
                            if (hello.string("state") != "ready") fail("ENGINE_UNAVAILABLE")
                            synchronized(lock) {
                                helloChannel = null
                                mutableStatus.value =
                                    mutableStatus.value.copy(
                                        ejsVersion = hello.string("ejs"),
                                        shimApi = hello["shimApi"]?.jsonPrimitive?.longOrNull?.toInt(),
                                        python = hello.string("python"),
                                        jsChallenges = hello["jsc"]?.jsonPrimitive?.booleanOrNull == true,
                                    )
                            }
                            if (announcedCompile) EngineCompileMarkers.write(prepared)
                            val reply = CompletableDeferred<JsonElement>()
                            synchronized(lock) {
                                if (epoch != generation) fail("ENGINE_UNAVAILABLE")
                                startReply = reply
                            }
                            if (!spawned.send(
                                    callFrame(
                                        START_CALL_ID,
                                        "ping",
                                        Json.parseToJsonElement(ping.toJson()).jsonObject,
                                        wireDeadline(
                                            YtxDeadline(clock.elapsedRealtime() + compileTimeoutMs),
                                        ),
                                    ),
                                )
                            ) {
                                fail("ENGINE_UNAVAILABLE")
                            }
                            val pong =
                                withTimeout(startTimeoutMs) { reply.await() }.jsonObject
                            if (pong["ready"]?.jsonPrimitive?.booleanOrNull != true) {
                                fail("ENGINE_UNAVAILABLE")
                            }
                            store.loadStatus()
                            synchronized(lock) {
                                if (epoch != generation) fail("ENGINE_UNAVAILABLE")
                                startReply = null
                                mutableStatus.value =
                                    mutableStatus.value.copy(
                                        state = HostState.READY,
                                        engineVersion = prepared.marker.engineVersion,
                                    )
                                scheduleIdle()
                            }
                            spawned
                        },
                    )
                } catch (unusable: PythonUnavailableException) {
                    synchronized(lock) {
                        if (epoch == generation) {
                            starting = null
                            mutableStatus.value = HostStatus(HostState.UNUSABLE, HostKind.STDIO, WORKERS)
                        }
                    }
                    Result.failure(YtxCallException("ENGINE_UNAVAILABLE"))
                } catch (failure: Exception) {
                    synchronized(lock) {
                        if (epoch == generation) {
                            starting = null
                            stop(HostExit.START_FAILED)
                        }
                    }
                    Result.failure(YtxCallException("ENGINE_UNAVAILABLE"))
                }
            }.also {
                starting = it
                it.start()
            }
    }

    /** The reader-thread half of one generation: frames route by `t`, exits by generation. */
    private fun listener(epoch: Long) =
        object : YtxChild.Listener {
            override fun onMessage(message: JsonObject) {
                when (message.string("t")) {
                    "hello" -> {
                        val accepted =
                            synchronized(lock) { helloChannel?.trySend(message)?.isSuccess == true }
                        if (!accepted) violation(epoch)
                    }

                    "ok" -> {
                        val id = message["id"]?.jsonPrimitive?.longOrNull
                        val result = message["r"]
                        if (id == null || result == null) return violation(epoch)
                        if (id == START_CALL_ID) {
                            synchronized(lock) { startReply?.complete(result) }
                            return
                        }
                        val text = result.toString()
                        if (text.toByteArray(Charsets.UTF_8).size > YtxLimits.RESULT_BYTES) {
                            violation(epoch)
                        } else {
                            finish(id, text, null)
                        }
                    }

                    "err" -> {
                        val id = message["id"]?.jsonPrimitive?.longOrNull ?: return violation(epoch)
                        val code = message.string("code") ?: "EXTRACTION"
                        if (id == START_CALL_ID) {
                            synchronized(lock) { startReply?.completeExceptionally(YtxCallException(code)) }
                        } else {
                            finish(id, null, code)
                        }
                    }

                    "status" -> {
                        // Diagnostics only; the transport already tracks running/queued itself.
                    }

                    else -> {
                        // Unknown kinds are forward-compatible (jsc, later shimApi additions).
                    }
                }
            }

            override fun onProtocolViolation(reason: String) {
                violation(epoch)
            }

            override fun onExit(code: Int) {
                synchronized(lock) {
                    if (epoch == generation) stop(HostExit.CRASH)
                }
            }

            private fun violation(epoch: Long) {
                synchronized(lock) {
                    if (epoch == generation) stop(HostExit.PROTOCOL_VIOLATION)
                }
            }
        }

    private fun finish(
        id: Long,
        result: String?,
        code: String?,
    ) = synchronized(lock) {
        val call = pending.remove(id) ?: return@synchronized
        call.watchdog?.cancel()
        if (result != null) {
            call.result.complete(result)
        } else {
            call.result.completeExceptionally(YtxCallException(code ?: "EXTRACTION"))
        }
        updateRunning()
        scheduleIdle()
        slots.release()
    }

    private fun updateRunning() {
        mutableStatus.value =
            mutableStatus.value.copy(
                running = pending.size.coerceAtMost(WORKERS),
                queued = waiting,
                inFlight = pending.keys.toList(),
            )
    }

    private fun scheduleIdle() {
        if (pending.isNotEmpty()) return
        idle?.cancel()
        idle =
            scope.launch {
                delay(idleStopMs)
                synchronized(lock) { if (pending.isEmpty()) stop(HostExit.IDLE_STOP) }
            }
    }

    private fun stop(
        exit: HostExit,
        timedId: Long? = null,
    ) {
        generation++
        idle?.cancel()
        starting?.cancel()
        starting = null
        val dead = child
        child = null
        helloChannel = null
        startReply?.cancel()
        startReply = null
        if (dead != null) {
            if (exit == HostExit.IDLE_STOP) {
                // EOF is the documented exit path; destroyForcibly is only the 2 s fallback.
                dead.closeStdin()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        delay(IDLE_GRACE_MS)
                    } finally {
                        // This child is already detached from `child`; scope cancellation
                        // must also reap it rather than cancel its only kill fallback.
                        dead.destroyForcibly()
                    }
                }
            } else {
                dead.destroyForcibly()
            }
        }
        mutableStatus.value = HostStatus(HostState.STOPPED, HostKind.STDIO, WORKERS, lastExit = exit)
        val interrupted = pending.toMap()
        pending.clear()
        interrupted.forEach { (id, call) ->
            call.watchdog?.cancel()
            call.result.completeExceptionally(
                YtxCallException(if (id == timedId) "TIMEOUT" else "ENGINE_UNAVAILABLE"),
            )
            slots.release()
        }
    }

    private fun wireDeadline(deadline: YtxDeadline): Long =
        // The wire deadline is epoch-ms (11 "Stdio protocol"); the shim re-roots it on receipt.
        System.currentTimeMillis() + (deadline.elapsedRealtimeMs - clock.elapsedRealtime())

    private fun callFrame(
        id: Long,
        method: String,
        payload: JsonObject,
        deadlineMs: Long,
    ): JsonObject =
        buildJsonObject {
            put("t", "call")
            put("id", id)
            put("m", method)
            put("p", payload)
            put("deadline", deadlineMs)
        }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        const val WORKERS = 4
        const val START_CALL_ID = -1L
        private const val IDLE_GRACE_MS = 2_000L

        fun fail(code: String): Nothing = throw YtxCallException(code)
    }
}
