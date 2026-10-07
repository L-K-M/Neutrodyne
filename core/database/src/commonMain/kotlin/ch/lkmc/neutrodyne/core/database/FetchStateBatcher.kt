// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.common.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Batches batched fetch-state writes (02 Refresh selection and fetch-state writes): up to
 * [MAX_BATCH] outcomes or every [MAX_DELAY_MS], whichever comes first, so a 300-feed refresh
 * invalidates open lists a few times instead of 300 times. The deadline is checked on [add] — the
 * refresh run calls it per outcome, so no background timer is needed; the run's end flush uses
 * [NonCancellable] so a killed coroutine never loses a batch it already promised (a process kill
 * still can — harmless: those feeds stay due and refetch with conditional GET).
 *
 * Deviation (02, recorded 2026-10-06): the spec places this class in `:core:data`; it lives in
 * `:core:database` because the M1a package may not edit `core/data`.
 */
class FetchStateBatcher(
    private val dao: PodcastDao,
    private val clock: Clock,
) {
    private val pending = ArrayList<PodcastFetchState>(MAX_BATCH)
    private var firstPendingAt = 0L

    /** Buffers one refresh outcome, flushing when the batch size or the deadline is reached. */
    suspend fun add(row: PodcastFetchState) {
        if (pending.isEmpty()) firstPendingAt = clock.now()
        pending += row
        if (pending.size >= MAX_BATCH || clock.now() - firstPendingAt >= MAX_DELAY_MS) flush()
    }

    /** Writes the remaining outcomes; 03 calls it before user writes and at the run's end. */
    suspend fun flush() {
        if (pending.isEmpty()) return
        val rows = pending.toList()
        pending.clear()
        withContext(NonCancellable) { dao.updateFetchStates(rows) }
    }

    internal companion object {
        /** 02: batches of up to 20 outcomes. */
        const val MAX_BATCH = 20

        /** 02: …or every 5 s. */
        const val MAX_DELAY_MS = 5_000L
    }
}
