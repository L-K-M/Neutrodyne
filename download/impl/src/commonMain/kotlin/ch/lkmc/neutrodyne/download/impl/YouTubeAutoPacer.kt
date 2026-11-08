// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One per DownloadComponents. Only admitted AUTO starts enter the rolling monotonic window. */
internal class YouTubeAutoPacer(
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val starts = ArrayDeque<Long>()

    suspend fun admit(): Long? =
        mutex.withLock {
            val now = clock.elapsedRealtime()
            while (starts.firstOrNull()?.let { now - it >= HOUR_MS } == true) starts.removeFirst()
            if (starts.size >= 20) return@withLock clock.now() + (HOUR_MS - (now - starts.first())).coerceAtLeast(1)
            starts.addLast(now)
            null
        }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
