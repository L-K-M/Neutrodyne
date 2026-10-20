// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import dev.zacsweers.metro.Inject

/** Only departure/end events can finish an item. A pause never changes played state. */
enum class PlaybackExit { AUTO, END, END_OF_ITEM_PAUSE, SEEK, NEW_START, ERROR_SKIP, MARKED_PLAYED, PAUSE, DISMISS }

@Inject
class PlayedRule {
    fun duration(
        playerMs: Long?,
        measuredMs: Long?,
        feedMs: Long?,
    ): Long? = sequenceOf(playerMs, measuredMs, feedMs).firstOrNull { it != null && it > 0 }

    fun isNearEnd(
        positionMs: Long,
        durationMs: Long?,
    ): Boolean {
        if (durationMs == null || durationMs <= 0) return false

        // Divide first to avoid overflow for corrupt or very large duration hints.
        val threshold = maxOf(MIN_FINISH_MS, durationMs / 100 * 3 + durationMs % 100 * 3 / 100)
        return positionMs >= (durationMs - threshold).coerceAtLeast(0)
    }

    fun shouldMarkPlayed(
        exit: PlaybackExit,
        positionMs: Long,
        durationMs: Long?,
        hasPlayed: Boolean,
    ): Boolean =
        when (exit) {
            PlaybackExit.AUTO, PlaybackExit.END, PlaybackExit.END_OF_ITEM_PAUSE -> true
            PlaybackExit.SEEK, PlaybackExit.NEW_START -> hasPlayed && isNearEnd(positionMs, durationMs)
            PlaybackExit.ERROR_SKIP, PlaybackExit.MARKED_PLAYED, PlaybackExit.PAUSE, PlaybackExit.DISMISS -> false
        }

    private companion object {
        const val MIN_FINISH_MS = 30_000L
    }
}
