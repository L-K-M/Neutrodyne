// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.common.Clock
import dev.zacsweers.metro.Inject

/** Player-thread confined. Recovery classification and foreground/network checks stay in adapters. */
@Inject
class RecoveryBudget(
    private val clock: Clock,
) {
    private val prepares = mutableMapOf<Long, MutableList<Long>>()
    private var consecutiveSkips = 0

    fun tryReprepare(episodeId: Long): Boolean {
        val now = clock.elapsedRealtime()
        // Prune all items so a long session does not retain one entry per old failure.
        prepares.values.forEach { times -> times.removeAll { now - it >= WINDOW_MS } }
        prepares.entries.removeAll { it.value.isEmpty() }
        val times = prepares.getOrPut(episodeId) { mutableListOf() }
        if (times.size >= MAX_REPREPARES) return false

        times += now
        return true
    }

    /** Five skips may execute; the next request must pause instead of spinning through the queue. */
    fun trySkip(): Boolean {
        if (consecutiveSkips >= MAX_SKIPS) return false

        consecutiveSkips++
        return true
    }

    /** Call only on actual advancing playback, never on arrival/prepare/buffering. */
    fun onPlaying() {
        consecutiveSkips = 0
    }

    fun clear() {
        prepares.clear()
        consecutiveSkips = 0
    }

    private companion object {
        const val WINDOW_MS = 2 * 60_000L
        const val MAX_REPREPARES = 3
        const val MAX_SKIPS = 5
    }
}
