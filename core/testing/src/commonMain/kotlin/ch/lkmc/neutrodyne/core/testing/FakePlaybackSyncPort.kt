// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.PlaybackSyncActive
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mutable [PlaybackSyncPort] for tests: [setPlaying] flips the active state, [emit] fires a
 * boundary event synchronously, and remote played marks are recorded, not acted on.
 */
class FakePlaybackSyncPort(
    playing: Boolean = false,
    private val episodeId: Long? = null,
) : PlaybackSyncPort {
    private val _active = MutableStateFlow(PlaybackSyncActive(playing, episodeId))
    override val active: StateFlow<PlaybackSyncActive> = _active.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackSyncEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackSyncEvent> = _events

    /** `[episodeId, deviceName]` pairs reported through [onRemoteMarkedPlayed]. */
    val remotePlayed = mutableListOf<Pair<Long, String>>()

    /** Episode ids whose rolled-back transaction reverted through [onRemoteMarkReverted]. */
    val remoteReverted = mutableListOf<Long>()

    fun setPlaying(playing: Boolean) {
        _active.value = PlaybackSyncActive(playing, _active.value.episodeId)
    }

    fun setActive(
        episodeId: Long?,
        isPlaying: Boolean,
    ) {
        _active.value = PlaybackSyncActive(isPlaying, episodeId)
    }

    suspend fun emit(event: PlaybackSyncEvent) {
        _events.emit(event)
    }

    override suspend fun onRemoteMarkedPlayed(
        episodeId: Long,
        deviceName: String,
    ) {
        remotePlayed += episodeId to deviceName
    }

    override suspend fun onRemoteMarkReverted(episodeId: Long) {
        remoteReverted += episodeId
    }
}
