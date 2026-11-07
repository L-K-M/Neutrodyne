// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The loaded current item, playing or paused (06 Sync interplay: `ActivePlayback`); `null` in
 * [PlaybackSyncPort.active] while nothing is loaded.
 */
data class ActivePlayback(
    val episodeId: Long,
    val isPlaying: Boolean,
    /** The wall clock of [episodeId] becoming current (`currentSince`), as the adapter owns it. */
    val sinceMs: Long,
)

/** A player boundary that triggers a round (10 Scheduling on Android). */
sealed interface PlaybackSyncEvent {
    data object Paused : PlaybackSyncEvent

    data object Stopped : PlaybackSyncEvent

    data class Transition(
        val episodeId: Long,
    ) : PlaybackSyncEvent
}

/**
 * The playback side of 10 Client sync engine: the scheduler observes [active] and [events], and
 * the applier reports a winning remote played mark through [onRemoteMarkedPlayed]. The player is
 * never stopped, seeked or skipped by sync (R7.5). Each event is emitted only after the matching
 * position save or transition transaction has committed.
 */
interface PlaybackSyncPort {
    val active: StateFlow<ActivePlayback?>

    val events: Flow<PlaybackSyncEvent>

    /**
     * A winning remote `played = true` makes [episodeId] effectively played. The loaded player
     * keeps running and position writes for the item stop while it stays current.
     */
    suspend fun onRemoteMarkedPlayed(
        episodeId: Long,
        deviceName: String,
    )
}

/** The dormant binding: no player reports to sync (06's `PlaybackSyncBridge`, 11's controller). */
object NoPlaybackSync : PlaybackSyncPort {
    override val active = kotlinx.coroutines.flow.MutableStateFlow<ActivePlayback?>(null)

    override val events: Flow<PlaybackSyncEvent> =
        kotlinx.coroutines.flow.flowOf()

    override suspend fun onRemoteMarkedPlayed(
        episodeId: Long,
        deviceName: String,
    ) = Unit
}
