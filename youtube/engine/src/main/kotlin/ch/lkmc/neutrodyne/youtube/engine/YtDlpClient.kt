// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

enum class YtxPriority { PLAYBACK, INTERACTIVE, BACKGROUND }

/** Admission owns no host IDs: transport cancellation owns the assigned call and its sockets. */
class YtDlpClient(
    private val transport: YtxTransport,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private class Waiter(
        val priority: YtxPriority,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
        var admitted: Boolean = false,
    )

    private val lock = Any()
    private val queue = mutableListOf<Waiter>()
    private var running = 0

    init {
        scope.launch { transport.status.collect { synchronized(lock) { drain() }.release() } }
    }

    val isReady: Boolean get() = transport.status.value.state == HostState.READY

    /** Composite callers reserve health once inside this block, after admission, and complete in
     * finally. Nested wire calls reuse this permit and immutable absolute deadline.
     */
    suspend fun <T : Any> operation(
        priority: YtxPriority,
        deadline: YtxDeadline,
        block: suspend Call.() -> T,
    ): T {
        val now = clock.elapsedRealtime()
        if (deadline.elapsedRealtimeMs <= now) throw YtxCallException("TIMEOUT")
        val remaining = deadline.elapsedRealtimeMs - now
        val waiter = Waiter(priority)
        try {
            return withTimeoutOrNull(remaining) {
                synchronized(lock) {
                    queue += waiter
                    drain()
                }.release()
                waiter.ready.await()
                val operation = Call(deadline)
                operation.checkDeadline()
                val result = operation.block()
                operation.checkDeadline()
                result
            } ?: throw YtxCallException("TIMEOUT")
        } finally {
            synchronized(lock) {
                queue.remove(waiter)
                if (waiter.admitted) running--
                drain()
            }.release()
        }
    }

    suspend fun call(
        method: YtxMethod,
        payload: String,
        deadline: YtxDeadline,
        priority: YtxPriority = priorityFor(method),
    ): String = operation(priority, deadline) { call(method, payload) }

    inner class Call internal constructor(
        private val deadline: YtxDeadline,
    ) {
        /** Local mapping also consumes the operation budget. Check before committing cache/health. */
        suspend fun checkDeadline() {
            currentCoroutineContext().ensureActive()
            if (clock.elapsedRealtime() >= deadline.elapsedRealtimeMs) throw YtxCallException("TIMEOUT")
        }

        suspend fun call(
            method: YtxMethod,
            payload: String,
        ): String {
            checkText(payload, YtxLimits.PAYLOAD_BYTES)
            checkDeadline()
            val result =
                try {
                    transport.call(method, payload, deadline)
                } catch (failure: YtxCallException) {
                    // A late error must not reach stateful classification (for example UNAVAILABLE).
                    checkDeadline()
                    throw failure
                }
            checkDeadline()
            checkText(result, YtxLimits.RESULT_BYTES)
            return result
        }
    }

    private fun drain(): List<Waiter> {
        val admitted = mutableListOf<Waiter>()
        while (running < transport.status.value.workers && queue.isNotEmpty()) {
            val next = queue.minBy { it.priority.ordinal }
            queue.remove(next)
            next.admitted = true
            running++
            admitted += next
        }
        return admitted
    }

    // An Unconfined waiter can run user code immediately; never resume it under the monitor.
    private fun List<Waiter>.release() {
        forEach { it.ready.complete(Unit) }
    }

    companion object {
        fun priorityFor(method: YtxMethod): YtxPriority =
            when (method) {
                YtxMethod.RESOLVE -> YtxPriority.PLAYBACK
                YtxMethod.LOOKUP, YtxMethod.SEARCH_OPEN, YtxMethod.SEARCH_NEXT -> YtxPriority.INTERACTIVE
                else -> YtxPriority.BACKGROUND
            }

        /** Bound before recursive deserialization. No exception retains the untrusted input. */
        fun <T> decode(
            text: String,
            serializer: DeserializationStrategy<T>,
        ): T {
            checkText(text, YtxLimits.RESULT_BYTES)
            try {
                return Json.decodeFromString(serializer, text)
            } catch (_: Exception) {
                throw YtxCallException("EXTRACTION")
            }
        }

        private fun checkText(
            text: String,
            limit: Int,
        ) {
            if (text.length > limit ||
                text.toByteArray(Charsets.UTF_8).size > limit
            ) {
                throw YtxCallException("EXTRACTION")
            }
            var depth = 0
            var quoted = false
            var escaped = false
            for (char in text) {
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> quoted = false
                    }
                } else {
                    when (char) {
                        '"' -> {
                            quoted = true
                        }

                        '{', '[' -> {
                            depth++
                            if (depth > 32) throw YtxCallException("EXTRACTION")
                        }

                        '}', ']' -> {
                            depth--
                            if (depth < 0) throw YtxCallException("EXTRACTION")
                        }
                    }
                }
            }
            if (depth != 0 || quoted) throw YtxCallException("EXTRACTION")
        }
    }
}
