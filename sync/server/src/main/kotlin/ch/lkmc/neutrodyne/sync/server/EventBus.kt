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
        val streams = mutableSetOf<EventStream>()
    }

    private val accounts = mutableMapOf<Long, AccountFlow>()
    private var stopped = false

    suspend fun open(principal: AccountPrincipal): EventStream {
        val stream =
            synchronized(this) {
                if (stopped) throw AuthThrottled(10)
                val account = accounts.getOrPut(principal.accountId) { AccountFlow() }
                if (account.streams.size >= minOf(10, maxPerAccount) ||
                    account.streams.count { it.principal.deviceId == principal.deviceId } >= 2
                ) {
                    throw AuthThrottled(10)
                }
                EventStream(principal, account.changed) { removed -> remove(removed) }.also { account.streams += it }
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
