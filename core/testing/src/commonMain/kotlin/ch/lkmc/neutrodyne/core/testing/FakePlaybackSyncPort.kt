// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.ActivePlayback
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mutable [PlaybackSyncPort] for tests: [setPlaying] flips the active state (nothing is loaded
 * while [episodeId] is null), [emit] fires a boundary event synchronously, and remote played
 * marks are recorded, not acted on.
 */
class FakePlaybackSyncPort(
    playing: Boolean = false,
    episodeId: Long? = null,
) : PlaybackSyncPort {
    private val _active = MutableStateFlow(activeOf(playing, episodeId, 0L))
    override val active: StateFlow<ActivePlayback?> = _active.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackSyncEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackSyncEvent> = _events

    /** `[episodeId, deviceName]` pairs reported through [onRemoteMarkedPlayed]. */
    val remotePlayed = mutableListOf<Pair<Long, String>>()

    fun setPlaying(
        playing: Boolean,
        episodeId: Long? = _active.value?.episodeId,
    ) {
        _active.value = activeOf(playing, episodeId, _active.value?.sinceMs ?: 0L)
    }

    fun load(
        episodeId: Long?,
        playing: Boolean = _active.value?.isPlaying ?: false,
        sinceMs: Long = _active.value?.sinceMs ?: 0L,
    ) {
        _active.value = activeOf(playing, episodeId, sinceMs)
    }

    fun emit(event: PlaybackSyncEvent) {
        _events.tryEmit(event)
    }

    override suspend fun onRemoteMarkedPlayed(
        episodeId: Long,
        deviceName: String,
    ) {
        remotePlayed += episodeId to deviceName
    }

    private fun activeOf(
        playing: Boolean,
        episodeId: Long?,
        sinceMs: Long,
    ): ActivePlayback? = episodeId?.let { ActivePlayback(it, playing, sinceMs) }
}
