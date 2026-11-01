// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import ch.lkmc.neutrodyne.core.common.Clock

/** Only command entry points record user pauses. Focus, buffering and projection never do. */
internal class SmartResume(
    private val player: Player,
    private val clock: Clock,
    private val enabled: () -> Boolean,
    private val seek: (Long) -> Unit,
) : Player.Listener {
    private var pausedAt: Long? = null
    private var pausedEpisodeId: Long? = null

    fun userPaused() {
        if (!player.playWhenReady) return
        pausedAt = clock.elapsedRealtime()
        pausedEpisodeId = player.episodeIdOrNull()
    }

    fun resume() {
        val since = pausedAt ?: return
        pausedAt = null
        if (player.episodeIdOrNull() != pausedEpisodeId || !enabled() ||
            clock.elapsedRealtime() - since <= STALE_MS
        ) {
            return
        }
        val position = player.currentPosition
        if (position < 0) return
        seek((position - REWIND_MS).coerceAtLeast(0))
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) pausedAt = null
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if (player.episodeIdOrNull() != pausedEpisodeId) pausedAt = null
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) pausedAt = null
    }

    private companion object {
        const val STALE_MS = 5 * 60_000L
        const val REWIND_MS = 5_000L
    }
}
