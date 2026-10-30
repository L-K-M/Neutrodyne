// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.SessionRevision
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import kotlinx.coroutines.CancellationException

/** Recording writer with caller-published reads; generation advances only after a successful write. */
class FakePlaySessionStore : PlaySessionStore {
    var queue = VirtualQueue(0, null, emptyList(), emptyList())
    var generation = 0L
    var failure: Throwable? = null
    val calls = mutableListOf<Call>()
    val requestedWindows = mutableListOf<Int>()

    enum class Operation { START, TRANSITION, BACK, END, CLEAR }

    data class Call(
        val operation: Operation,
        val now: Long,
        val start: SessionStart? = null,
        val departure: Departure? = null,
        val arrivalId: Long? = null,
        val origin: QueueOrigin? = null,
        val expected: SessionRevision? = null,
    )

    override suspend fun virtualQueue(k: Int): VirtualQueue {
        require(k >= 0)
        requestedWindows += k
        failure?.let { throw it }
        return queue.copy(contextTail = queue.contextTail.take(k))
    }

    private fun commit(call: Call): Long {
        calls += call
        failure?.let { throw it }
        generation = maxOf(generation, queue.generation) + 1
        return generation
    }

    /** Attempts record like committed writes; a stale revision returns null without bumping. */
    private fun fenced(
        call: Call,
        expected: SessionRevision,
    ): Long? {
        calls += call
        failure?.let { throw it }
        val current = maxOf(generation, queue.generation)
        if (expected.currentEpisodeId != queue.current?.episodeId || expected.generation != current) {
            return null
        }
        generation = current + 1
        return generation
    }

    override suspend fun commitStart(
        start: SessionStart,
        now: Long,
    ): Long =
        commit(
            Call(
                Operation.START,
                now,
                start = start,
            ),
        )

    override suspend fun onTransition(
        departure: Departure?,
        arrivedEpisodeId: Long,
        arrivedOrigin: QueueOrigin,
        expected: SessionRevision,
        now: Long,
    ): Long? =
        fenced(
            Call(
                Operation.TRANSITION,
                now,
                departure = departure,
                arrivalId = arrivedEpisodeId,
                origin = arrivedOrigin,
                expected = expected,
            ),
            expected,
        )

    override suspend fun goBack(
        previous: QueueItem,
        departure: Departure,
        now: Long,
    ): Long =
        commit(
            Call(
                Operation.BACK,
                now,
                departure = departure,
                arrivalId = previous.episodeId,
                origin = previous.origin,
            ),
        )

    override suspend fun endSession(
        departure: Departure?,
        expected: SessionRevision,
        now: Long,
    ): Long? =
        fenced(
            Call(
                Operation.END,
                now,
                departure = departure,
                expected = expected,
            ),
            expected,
        )

    override suspend fun clearCurrent(
        departure: Departure?,
        now: Long,
        allowed: () -> Boolean,
    ): Long {
        if (!allowed()) throw CancellationException("Playback dismissal superseded")
        return commit(
            Call(
                Operation.CLEAR,
                now,
                departure = departure,
            ),
        )
    }
}
