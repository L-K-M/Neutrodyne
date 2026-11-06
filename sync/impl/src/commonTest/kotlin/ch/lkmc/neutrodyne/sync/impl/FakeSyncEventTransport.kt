// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scripted [SyncEventTransport] for the supervisor and controller tests: [open] records its
 * arguments and returns the next scripted session (or throws the next scripted failure); an
 * unscripted open returns an idle stream that never emits.
 */
class FakeSyncEventTransport : SyncEventTransport {
    data class Open(
        val url: String,
        val token: String,
        val heartbeatSeconds: Int,
    )

    val opens = mutableListOf<Open>()
    private val scripted = ArrayDeque<suspend () -> SyncEventSession>()

    /** The next [open] returns [session]. */
    fun thenOpen(session: SyncEventSession) {
        scripted += { session }
    }

    /** The next [open] throws [failure]. */
    fun thenFail(failure: Throwable) {
        scripted += { throw failure }
    }

    /** From here on every [open] throws [failure]. */
    fun failAll(failure: Throwable) {
        scripted.clear()
        scripted += { throw failure }
        sticky = failure
    }

    private var sticky: Throwable? = null

    override suspend fun open(
        baseUrl: String,
        token: String,
        heartbeatSeconds: Int,
    ): SyncEventSession {
        opens += Open(baseUrl, token, heartbeatSeconds)
        scripted.removeFirstOrNull()?.let { return it() }
        sticky?.let { throw it }
        return FakeSyncEventSession(kotlinx.coroutines.flow.emptyFlow())
    }
}

/**
 * A scripted stream: emits [flow]'s items until it completes (a clean end) or fails. [closed]
 * completes when the supervisor cancels or closes the session.
 */
class FakeSyncEventSession(
    override val events: Flow<SyncStreamEvent>,
) : SyncEventSession {
    val closed = CompletableDeferred<Unit>()
    val isClosed get() = closed.isCompleted

    override suspend fun close() {
        closed.complete(Unit)
    }
}

/** An open stream that never emits and never ends — for "the stream stays live" assertions. */
fun idleEventSession(): FakeSyncEventSession = FakeSyncEventSession(flow { awaitCancellation() })
