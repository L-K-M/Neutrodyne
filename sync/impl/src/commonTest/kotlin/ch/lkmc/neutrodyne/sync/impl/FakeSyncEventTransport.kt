// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A scripted [SyncEventTransport] for [SyncEventsClient] tests: every [SyncEventTransport.open]
 * is recorded and returns a session whose events the test feeds through [Session.emit],
 * [Session.end] or [Session.fail]. A session's event flow suspends until the test ends it, so
 * the supervisor's stream reads never complete on their own.
 */
internal class FakeSyncEventTransport {
    /** What the next `open` does. */
    sealed interface Outcome {
        /** A stream that stays open until the test ends or fails it. */
        data object Stream : Outcome

        /** `open` throws this — an HTTP status or a transport failure. */
        data class Reject(
            val failure: SyncTransportException,
        ) : Outcome
    }

    data class Open(
        val baseUrl: String,
        val token: String,
        val heartbeatSeconds: Int,
    )

    val opens = mutableListOf<Open>()
    val sessions = mutableListOf<Session>()
    private val outcomeLock = Mutex()
    private var next: Outcome = Outcome.Stream

    /** Queues the outcome the next `open` returns; defaults to a fresh [Session]. */
    suspend fun next(outcome: Outcome) {
        outcomeLock.withLock { next = outcome }
    }

    fun transport(): SyncEventTransport =
        object : SyncEventTransport {
            override suspend fun open(
                baseUrl: String,
                token: String,
                heartbeatSeconds: Int,
            ): SyncEventSession {
                opens += Open(baseUrl, token, heartbeatSeconds)
                return when (val o = outcomeLock.withLock { next }) {
                    is Outcome.Reject -> throw o.failure
                    Outcome.Stream -> Session().also { sessions += it }
                }
            }
        }

    /** A test-driven stream: feed events, then [end] it cleanly or [fail] it. */
    class Session : SyncEventSession {
        private sealed interface Signal {
            data class Event(
                val event: SyncStreamEvent,
            ) : Signal

            data object End : Signal
        }

        private val signal = Channel<Signal>(Channel.UNLIMITED)
        private val closeSignal = CompletableDeferred<Unit>()

        /** Every event this session emitted, in order. */
        val sent = mutableListOf<SyncStreamEvent>()

        override val events: Flow<SyncStreamEvent> =
            flow {
                // `close` delivers queued events first, then ends the flow; `fail`'s cause is
                // thrown after them — matching a server's flush-then-close ordering.
                for (s in signal) {
                    when (s) {
                        is Signal.Event -> emit(s.event)
                        Signal.End -> break
                    }
                }
            }

        /** Emits one event into the stream. */
        suspend fun emit(event: SyncStreamEvent) {
            sent += event
            signal.send(Signal.Event(event))
        }

        /** Ends the stream as if the server closed it (after the queued events). */
        fun end() {
            signal.trySend(Signal.End)
        }

        /** Fails the stream's reader with [failure] (after the queued events). */
        fun fail(failure: SyncTransportException) {
            signal.close(failure)
        }

        override suspend fun close() {
            closeSignal.complete(Unit)
            signal.close()
        }

        /** Resolves once the supervisor closed this session. */
        suspend fun awaitClosed() = closeSignal.await()
    }
}
