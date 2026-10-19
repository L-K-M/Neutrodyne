// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/** Called before cascading episode removal, merges or restore Replace. Implemented by downloads;
 * callers await durable deletion journaling and stop acknowledgement before touching rows.
 * Failure propagates and aborts the destructive operation. No user-dismissal tombstones.
 * Merge journals loser paths and invalidates its index before ID-based ownership is reassigned. */
interface DownloadLifecycle {
    suspend fun beforeEpisodeRemoval(episodeIds: List<Long>)

    suspend fun beforeEpisodeMerge(
        loserEpisodeId: Long,
        winnerEpisodeId: Long,
    )

    suspend fun beforeLibraryReplace()
}

/** Playback reports a missing local source; downloads rechecks before removing its index entry. */
fun interface MissingLocalMediaReporter {
    suspend fun reportFileMissing(episodeId: Long)
}
