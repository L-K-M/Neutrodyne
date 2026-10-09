// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.common.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Batches batched fetch-state writes (02 Refresh selection and fetch-state writes): up to
 * [MAX_BATCH] outcomes or every [MAX_DELAY_MS], whichever comes first, so a 300-feed refresh
 * invalidates open lists a few times instead of 300 times. A deadline job armed by the first
 * buffered outcome enforces the 5 s limit even when no further outcome arrives (a stalled run).
 *
 * All state is serialized on [mutex]: buffer access, draining and the write itself. `flush()` is
 * therefore a barrier — it returns only after every earlier write has committed — which is what
 * 03's user actions (Retry, Edit URL, Enter password, unsubscribe) rely on before writing. The
 * write runs under [NonCancellable] so a killed coroutine never loses a batch it already promised
 * (a process kill still can — harmless: those feeds stay due and refetch with conditional GET).
 *
 * [scope] owns the deadline job: the refresh run's scope in production, `backgroundScope` in
 * tests so the virtual scheduler drives the deadline. A write failure inside the deadline job
 * propagates into [scope] on purpose — it fails the run through structured concurrency rather
 * than completing a job whose write silently committed nothing.
 *
 * Deviation (02, recorded 2026-10-06): the spec places this class in `:core:data`; it lives in
 * `:core:database` because the M1a package may not edit `core/data`.
 */
class FetchStateBatcher internal constructor(
    private val write: suspend (List<PodcastFetchState>) -> Unit,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    constructor(
        dao: PodcastDao,
        clock: Clock,
        scope: CoroutineScope,
    ) : this(write = dao::updateFetchStates, clock = clock, scope = scope)

    private val mutex = Mutex()
    private val pending = ArrayList<PodcastFetchState>(MAX_BATCH)
    private var firstPendingElapsed = 0L
    private var deadlineJob: Job? = null

    /** Buffers one refresh outcome, flushing when the batch size or the deadline is reached. */
    suspend fun add(row: PodcastFetchState) {
        mutex.withLock {
            if (pending.isEmpty()) {
                // The deadline is measured on the monotonic clock: a backward wall-clock
                // adjustment (NTP, a manual change) must never strand the buffered outcomes.
                firstPendingElapsed = clock.elapsedRealtime()
                deadlineJob =
                    scope.launch {
                        delay(MAX_DELAY_MS)
                        mutex.withLock {
                            // The deadline is re-checked against [clock]: a test scheduler can
                            // skip virtual time while the caller is suspended on
                            // real-dispatcher work — flush only when the limit elapsed.
                            if (
                                pending.isNotEmpty() &&
                                clock.elapsedRealtime() - firstPendingElapsed >= MAX_DELAY_MS
                            ) {
                                flushLocked()
                            }
                        }
                    }
            }
            pending += row
            if (
                pending.size >= MAX_BATCH ||
                clock.elapsedRealtime() - firstPendingElapsed >= MAX_DELAY_MS
            ) {
                flushLocked()
            }
        }
    }

    /** Writes the remaining outcomes; 03 calls it before user writes and at the run's end. */
    suspend fun flush() {
        mutex.withLock { flushLocked() }
    }

    // Runs under [mutex]: the write happens while the buffer is still fully populated, so a
    // failed write keeps its rows for the next flush instead of losing them.
    private suspend fun flushLocked() {
        if (pending.isEmpty()) return
        val rows = pending.toList()
        withContext(NonCancellable) { write(rows) }
        pending.clear()
        deadlineJob?.cancel()
        deadlineJob = null
    }

    internal companion object {
        /** 02: batches of up to 20 outcomes. */
        const val MAX_BATCH = 20

        /** 02: …or every 5 s. */
        const val MAX_DELAY_MS = 5_000L
    }
}
