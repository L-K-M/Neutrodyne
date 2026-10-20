// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Timer state is service-local; only the sheet's last minute choice is persisted. */
sealed interface SleepTimerMode {
    data object Off : SleepTimerMode

    data class Minutes(
        val minutes: Int,
    ) : SleepTimerMode

    data object EndOfEpisode : SleepTimerMode

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 240

        fun isValidMinutes(minutes: Int): Boolean = minutes in MIN_MINUTES..MAX_MINUTES
    }
}

sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /** Listening time, independent of speed/seeks. Buffering holds the current fade factor. */
    data class Running(
        val remainingMs: Long,
        val totalMs: Long,
        val counting: Boolean,
        val fading: Boolean,
    ) : SleepTimerState

    data class EndOfEpisode(
        val episodeId: Long,
    ) : SleepTimerState
}
