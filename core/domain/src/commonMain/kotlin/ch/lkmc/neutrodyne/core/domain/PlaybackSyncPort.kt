// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the sync engine needs to know about the local player (10 Scheduling on Android).
 * [episodeId] names the loaded current item, `null` while nothing is loaded: the applier
 * reports a remote played mark only when it hits the loaded item (10 Remote played marks 2).
 */
data class PlaybackSyncActive(
    val isPlaying: Boolean,
    val episodeId: Long? = null,
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
     * keeps running and position writes for the item stop while it stays current. The applier
     * calls this inside the applying transaction, before projecting the mark, so the freeze is
     * already set when the commit lands.
     */
    suspend fun onRemoteMarkedPlayed(
        episodeId: Long,
        deviceName: String,
    )

    /**
     * The applying transaction that reported the mark rolled back or was cancelled: the
     * projection it announced never committed, so the freeze it installed lifts. A mark whose
     * commit did land is never reverted.
     */
    suspend fun onRemoteMarkReverted(episodeId: Long)
}

/** Tests and graphs without playback: no loaded item, no boundaries, marks are no-ops. */
object NoPlaybackSync : PlaybackSyncPort {
    override val active = kotlinx.coroutines.flow.MutableStateFlow(PlaybackSyncActive(false))

    override val events: Flow<PlaybackSyncEvent> =
        kotlinx.coroutines.flow.flowOf()

    override suspend fun onRemoteMarkedPlayed(
        episodeId: Long,
        deviceName: String,
    ) = Unit

    override suspend fun onRemoteMarkReverted(episodeId: Long) = Unit
}
