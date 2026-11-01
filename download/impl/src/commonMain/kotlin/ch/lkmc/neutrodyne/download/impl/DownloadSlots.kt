// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.SourceKind
import io.ktor.http.Url
import kotlinx.coroutines.sync.Semaphore

/** Admission is serialized by the engine's claimMutex; leases can be released by any child. */
internal class DownloadSlots {
    private val global = Semaphore(GLOBAL_LIMIT)
    private val automatic = Semaphore(AUTO_LIMIT)
    private val youtube = Semaphore(YOUTUBE_LIMIT)
    private val hosts = mutableMapOf<String, Semaphore>()

    fun canStart(row: DownloadEntity): Boolean = permits(row).all { it.availablePermits > 0 }

    fun acquire(row: DownloadEntity): Lease {
        val permits = permits(row)
        check(permits.all { it.availablePermits > 0 })
        permits.forEach { check(it.tryAcquire()) }
        return Lease(permits)
    }

    private fun permits(row: DownloadEntity): List<Semaphore> =
        buildList {
            add(global)
            if (row.lane == DownloadLane.AUTO) add(automatic)
            if (row.sourceKind == SourceKind.YOUTUBE) {
                add(youtube)
            } else {
                // The original enclosure host owns the limit, regardless of CDN redirects.
                val host = runCatching { Url(row.sourceRef).host.lowercase() }.getOrDefault("")
                add(hosts.getOrPut(host) { Semaphore(HOST_LIMIT) })
            }
        }

    internal class Lease(
        private val permits: List<Semaphore>,
    ) {
        fun release() = permits.forEach { it.release() }
    }

    private companion object {
        const val GLOBAL_LIMIT = 3
        const val HOST_LIMIT = 2
        const val AUTO_LIMIT = 2
        const val YOUTUBE_LIMIT = 1
    }
}
