// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Change notifications coalesce; revocation uses a separate close signal that cannot be dropped.
 * Registration precedes the credential recheck, so revocation during either step closes the stream.
 * A device runs one client process at a time, and a killed process's socket stays open behind a proxy
 * until a heartbeat write fails, so a device at the per-device cap closes its oldest stream to accept
 * the new one instead of answering 429.
 */
internal class EventBus(
    private val valid: suspend (AccountPrincipal) -> Boolean,
    private val maxPerAccount: Int = 10,
) : ServerEvents {
    private class AccountFlow {
        val changed =
            MutableSharedFlow<ServerEvent.Changed>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        // LinkedHashSet, so insertion order is open order and the first match is the oldest stream.
        val streams = mutableSetOf<EventStream>()
    }

    private val accounts = mutableMapOf<Long, AccountFlow>()
    private var stopped = false

    suspend fun open(principal: AccountPrincipal): EventStream {
        val stream =
            synchronized(this) {
                if (stopped) throw AuthThrottled(10)
                val account = accounts.getOrPut(principal.accountId) { AccountFlow() }
                val deviceId = principal.deviceId
                if (deviceId == null) {
                    if (account.streams.count { it.principal.deviceId == null } >= MAX_STREAMS_PER_DEVICE) {
                        throw AuthThrottled(10)
                    }
                } else {
                    // The old stream is a dead socket the proxy has not noticed yet, so evict this
                    // device's oldest stream(s) before the account cap check. close() calls remove(),
                    // which re-enters this monitor; the set is copied because remove() mutates it.
                    while (account.streams.count { it.principal.deviceId == deviceId } >= MAX_STREAMS_PER_DEVICE) {
                        account.streams
                            .toList()
                            .first { it.principal.deviceId == deviceId }
                            .close()
                    }
                }
                // An eviction that emptied the account removed its entry; register on the live one.
                val live = accounts.getOrPut(principal.accountId) { account }
                if (live.streams.size >= minOf(10, maxPerAccount)) throw AuthThrottled(10)
                EventStream(principal, live.changed) { removed -> remove(removed) }.also { live.streams += it }
            }
        try {
            if (!valid(principal)) unauthorized()
            return stream
        } catch (failure: Throwable) {
            stream.close()
            throw failure
        }
    }

    override fun subscribe(principal: AccountPrincipal): Flow<ServerEvent> =
        flow {
            open(principal).use { emitAll(it.events) }
        }

    @Synchronized override fun publish(event: ServerEvent) {
        val account = accounts[event.accountId] ?: return
        when (event) {
            is ServerEvent.Changed -> {
                account.changed.tryEmit(event)
            }

            is ServerEvent.Revoked -> {
                account.streams
                    .toList()
                    .filter { stream ->
                        (event.tokenHash == null || stream.principal.tokenHash == event.tokenHash) &&
                            (event.deviceId == null || stream.principal.deviceId == event.deviceId)
                    }.forEach { it.close() }
            }
        }
    }

    @Synchronized private fun remove(stream: EventStream) {
        val account = accounts[stream.principal.accountId] ?: return
        account.streams.remove(stream)
        if (account.streams.isEmpty()) accounts.remove(stream.principal.accountId)
    }

    @Synchronized override fun close() {
        stopped = true
        accounts.values.flatMap { it.streams }.forEach { it.close() }
    }

    @Synchronized internal fun streamCount(): Int = accounts.values.sumOf { it.streams.size }

    private companion object {
        const val MAX_STREAMS_PER_DEVICE = 2
    }
}

internal class EventStream(
    val principal: AccountPrincipal,
    changes: Flow<ServerEvent.Changed>,
    private val remove: (EventStream) -> Unit,
) : AutoCloseable {
    private val closed = CompletableDeferred<Unit>()
    val events: Flow<ServerEvent> =
        channelFlow {
            val forwarding = launch(start = CoroutineStart.UNDISPATCHED) { changes.collect { send(it) } }
            closed.await()
            forwarding.cancel()
        }.buffer(1, BufferOverflow.DROP_OLDEST)

    override fun close() {
        if (closed.complete(Unit)) remove(this)
    }
}
