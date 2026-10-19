// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

/** Pause and flush the active item before its podcast's cascade, without creating a player. */
fun interface PlaybackBeforeUnsubscribe {
    suspend fun pause(podcastIds: List<Long>)

    /** Platforms without playback have no active item to protect. */
    object None : PlaybackBeforeUnsubscribe {
        override suspend fun pause(podcastIds: List<Long>) = Unit
    }
}
