// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Process
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
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
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.BinderPayloads
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxCallback
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxEngine
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.YtxService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Owns one binding and process generation. Cancelled calls retain their hang watchdog until reply. */
class BinderYtxTransport internal constructor(
    private val connection: YtxConnection,
    private val store: EngineStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val ping: YtxControlRequest,
    private val settings: EngineSettings,
) : YtxTransport {
    constructor(
        context: Context,
        store: EngineStore,
        identity: CompileMarker,
        clock: Clock,
        scope: CoroutineScope,
        ping: YtxControlRequest,
        settings: EngineSettings,
    ) : this(AndroidYtxConnection(context.applicationContext, store, identity), store, clock, scope, ping, settings)

    private class Pending(
        val result: CompletableDeferred<String> = CompletableDeferred(),
        var watchdog: Job? = null,
    )

    private val lock = Any()
    private val slots = Semaphore(WORKERS)
    private val mutableStatus = MutableStateFlow(HostStatus(HostState.STOPPED, HostKind.CHAQUOPY, WORKERS))
    override val status = mutableStatus.asStateFlow()
    private var generation = 0L
    private var nextId = 1L
    private var starting: Deferred<Result<IYtxEngine>>? = null
    private var service: IYtxEngine? = null
    private val pending = mutableMapOf<Long, Pending>()
    private var idle: Job? = null

    override suspend fun call(
        method: YtxMethod,
        json: String,
        deadline: YtxDeadline,
    ): String {
        settings.awaitLoaded()
        if (!settings.state.value.enabled) fail("ENGINE_UNAVAILABLE")
        if (!BinderPayloads.fits(json, YtxLimits.PAYLOAD_BYTES)) fail("EXTRACTION")
        val remaining = deadline.elapsedRealtimeMs - clock.elapsedRealtime()
        if (remaining <= 0) fail("TIMEOUT")
        if (method != YtxMethod.PING && store.preparation.value != EnginePreparation.COMPILED) {
            fail("ENGINE_UNAVAILABLE")
        }
        // Caller cancellation releases admission only after the host acknowledges or is killed.
        // Otherwise rapid cancelled searches could queue past the host's two physical workers.
        var ownsSlot = false
        try {
            val admitted =
                withTimeoutOrNull(remaining) {
                    slots.acquire()
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
                            delay(budget + YtxLimits.HANG_GRACE_MS)
                            synchronized(lock) {
                                if (pending[id] === call) stop(HostExit.HANG_KILL, id)
                            }
                        }
                    updateRunning()
                    Triple(id, call, start())
                }
            ownsSlot = false
            try {
                val endpoint = host.await().getOrThrow()
                synchronized(lock) {
                    if (pending[id] === call) {
                        endpoint.call(id, method.wireName, json, deadline.elapsedRealtimeMs, callback)
                    }
                }
                return call.result.await()
            } catch (_: CancellationException) {
                cancel(id)
                // Startup can be cancelled by host death while this caller remains active.
                // Preserve caller cancellation and otherwise return the host's structured error.
                currentCoroutineContext().ensureActive()
                finish(id, null, "ENGINE_UNAVAILABLE")
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
        synchronized(lock) { runCatching { service?.cancel(callId) } }
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

    private fun start(): Deferred<Result<IYtxEngine>> {
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
                        withTimeout(if (compiling) YtxLimits.COMPILE_MS else YtxLimits.START_MS) {
                            if (store.hostLibDir() == null) fail("ENGINE_UNAVAILABLE")
                            val endpoint =
                                connection.bind { synchronized(lock) { if (epoch == generation) stop(HostExit.CRASH) } }
                            synchronized(lock) {
                                if (epoch != generation) fail("ENGINE_UNAVAILABLE")
                                service = endpoint
                                val pid =
                                    Json
                                        .parseToJsonElement(endpoint.status())
                                        .jsonObject["pid"]
                                        ?.jsonPrimitive
                                        ?.longOrNull
                                mutableStatus.value = mutableStatus.value.copy(pid = pid)
                            }
                            val ready = CompletableDeferred<String>()
                            endpoint.call(
                                START_CALL_ID,
                                "ping",
                                ping.toJson(),
                                clock.elapsedRealtime() + YtxLimits.COMPILE_MS,
                                object : IYtxCallback.Stub() {
                                    override fun onResult(
                                        callId: Long,
                                        resultJson: String,
                                    ) {
                                        ready.complete(resultJson)
                                    }

                                    override fun onError(
                                        callId: Long,
                                        code: String,
                                        message: String?,
                                    ) {
                                        ready.completeExceptionally(YtxCallException(code))
                                    }
                                },
                            )
                            if (Json
                                    .parseToJsonElement(ready.await())
                                    .jsonObject["ready"]
                                    ?.jsonPrimitive
                                    ?.content != "true"
                            ) {
                                fail("ENGINE_UNAVAILABLE")
                            }
                            store.loadStatus()
                            synchronized(lock) {
                                if (epoch != generation) fail("ENGINE_UNAVAILABLE")
                                mutableStatus.value = mutableStatus.value.copy(state = HostState.READY)
                                scheduleIdle()
                            }
                            endpoint
                        },
                    )
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

    private val callback =
        object : IYtxCallback.Stub() {
            override fun onResult(
                callId: Long,
                resultJson: String,
            ) {
                if (synchronized(lock) { callId !in pending }) return
                if (!BinderPayloads.fits(resultJson, YtxLimits.RESULT_BYTES)) {
                    synchronized(lock) { stop(HostExit.PROTOCOL_VIOLATION) }
                } else {
                    finish(callId, resultJson, null)
                }
            }

            override fun onError(
                callId: Long,
                code: String,
                message: String?,
            ) {
                finish(callId, null, code)
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
                queued = (pending.size - WORKERS).coerceAtLeast(0),
                inFlight = pending.keys.toList(),
            )
    }

    private fun scheduleIdle() {
        if (pending.isNotEmpty()) return
        idle?.cancel()
        idle =
            scope.launch {
                delay(YtxLimits.IDLE_STOP_MS)
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
        service = null
        connection.stop(mutableStatus.value.pid)
        val interrupted = pending.toMap()
        pending.clear()
        mutableStatus.value = HostStatus(HostState.STOPPED, HostKind.CHAQUOPY, WORKERS, lastExit = exit)
        interrupted.forEach { (id, call) ->
            call.watchdog?.cancel()
            call.result.completeExceptionally(
                YtxCallException(if (id == timedId) "TIMEOUT" else "ENGINE_UNAVAILABLE"),
            )
            slots.release()
        }
    }

    private companion object {
        const val WORKERS = 2
        const val START_CALL_ID = -1L

        fun fail(code: String): Nothing = throw YtxCallException(code)
    }
}

internal interface YtxConnection {
    suspend fun bind(died: () -> Unit): IYtxEngine

    fun stop(pid: Long?)
}

private class AndroidYtxConnection(
    private val context: Context,
    private val store: EngineStore,
    private val identity: CompileMarker,
) : YtxConnection {
    private var binding: ServiceConnection? = null

    override suspend fun bind(died: () -> Unit): IYtxEngine {
        if (!Process.is64Bit() || (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64" && Build.VERSION.SDK_INT < 28)) {
            throw YtxCallException("ENGINE_UNAVAILABLE")
        }
        val result = CompletableDeferred<IYtxEngine>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    try {
                        binder.linkToDeath({ died() }, 0)
                        result.complete(IYtxEngine.Stub.asInterface(binder))
                    } catch (_: Exception) {
                        result.completeExceptionally(YtxCallException("ENGINE_UNAVAILABLE"))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    died()
                }

                override fun onBindingDied(name: ComponentName) {
                    died()
                }

                override fun onNullBinding(name: ComponentName) {
                    result.completeExceptionally(YtxCallException("ENGINE_UNAVAILABLE"))
                }
            }
        binding = connection
        val intent =
            Intent(context, YtxService::class.java)
                .setAction(YtxService.ENGINE_BIND)
                .putExtra(YtxService.LIB_DIR, store.hostLibDir().toString())
                .putExtra(YtxService.APP_VERSION, identity.appVersionCode)
                .putExtra(YtxService.ENGINE_VERSION, identity.engineVersion)
        YtxTestHooks.attach(intent)
        if (!context.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE,
            )
        ) {
            throw YtxCallException("ENGINE_UNAVAILABLE")
        }
        return result.await()
    }

    override fun stop(pid: Long?) {
        binding?.let { runCatching { context.unbindService(it) } }
        binding = null
        val processId =
            pid ?: context
                .getSystemService(ActivityManager::class.java)
                .runningAppProcesses
                ?.firstOrNull { it.processName == "${context.packageName}:ytx" }
                ?.pid
                ?.toLong()
        if (processId != null && processId != Process.myPid().toLong()) Process.killProcess(processId.toInt())
    }
}
