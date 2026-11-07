// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the sync engine needs to know about the local player (10 Scheduling on Android). */
data class PlaybackSyncActive(
    val isPlaying: Boolean,
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
 * never stopped, seeked or skipped by sync (R7.5).
 */
interface PlaybackSyncPort {
    val active: StateFlow<PlaybackSyncActive>

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

/** The M1a binding: playback is not wired to sync until MS2. */
object NoPlaybackSync : PlaybackSyncPort {
    override val active = kotlinx.coroutines.flow.MutableStateFlow(PlaybackSyncActive(false))

    override val events: Flow<PlaybackSyncEvent> =
        kotlinx.coroutines.flow.flowOf()

    override suspend fun onRemoteMarkedPlayed(
        episodeId: Long,
        deviceName: String,
    ) = Unit
}
