// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState

/** Elapsed listening-time effects, independent of speed and seeks. */
class SleepTimerCore {
    sealed interface Effect {
        data class Fade(
            val factor: Float,
        ) : Effect

        data class ArmEndOfItem(
            val armed: Boolean,
        ) : Effect

        data object Pause : Effect

        data object Fired : Effect
    }

    var state: SleepTimerState = SleepTimerState.Off
        private set
    private var advancing = false
    private var factor = 1f

    fun set(
        mode: SleepTimerMode,
        episodeId: Long?,
    ): List<Effect> {
        if (mode is SleepTimerMode.Minutes) require(SleepTimerMode.isValidMinutes(mode.minutes))
        val effects = mutableListOf<Effect>(Effect.Fade(1f))
        factor = 1f
        state =
            when (mode) {
                SleepTimerMode.Off -> {
                    SleepTimerState.Off
                }

                is SleepTimerMode.Minutes -> {
                    val total = mode.minutes * MINUTE_MS
                    SleepTimerState.Running(total, total, advancing, false)
                }

                SleepTimerMode.EndOfEpisode -> {
                    episodeId?.let { SleepTimerState.EndOfEpisode(it) } ?: SleepTimerState.Off
                }
            }
        effects += Effect.ArmEndOfItem(state is SleepTimerState.EndOfEpisode)
        return effects
    }

    fun extend(minutes: Int): List<Effect> {
        require(SleepTimerMode.isValidMinutes(minutes))
        val running = state as? SleepTimerState.Running ?: return emptyList()
        val remaining = (running.remainingMs + minutes * MINUTE_MS).coerceAtMost(MAX_MS)
        state = running.copy(remainingMs = remaining, totalMs = remaining, fading = false)
        factor = 1f
        return listOf(Effect.Fade(1f))
    }

    fun advancing(value: Boolean): List<Effect> {
        advancing = value
        val running = state as? SleepTimerState.Running ?: return emptyList()
        state = running.copy(counting = value)
        return if (value) fade() else emptyList()
    }

    /** Buffering only changes advancement; an explicit pause also restores normal volume. */
    fun paused(): List<Effect> {
        advancing(false)
        factor = 1f
        return listOf(Effect.Fade(1f))
    }

    fun tick(elapsedMs: Long): List<Effect> {
        require(elapsedMs >= 0)
        val running = state as? SleepTimerState.Running ?: return emptyList()
        if (!advancing) return emptyList()
        val remaining = (running.remainingMs - elapsedMs).coerceAtLeast(0)
        state = running.copy(remainingMs = remaining, fading = remaining <= FADE_MS)
        if (remaining == 0L) {
            state = SleepTimerState.Off
            factor = 1f
            return listOf(Effect.Fade(0f), Effect.Pause, Effect.Fade(1f), Effect.Fired)
        }
        return fade()
    }

    fun currentChanged(episodeId: Long?): List<Effect> {
        if (state !is SleepTimerState.EndOfEpisode) return emptyList()
        if (episodeId == null) return set(SleepTimerMode.Off, null)
        state = SleepTimerState.EndOfEpisode(episodeId)
        return emptyList()
    }

    fun endOfItem(): List<Effect> {
        if (state !is SleepTimerState.EndOfEpisode) return emptyList()
        return listOf(Effect.Pause) + set(SleepTimerMode.Off, null) + Effect.Fired
    }

    private fun fade(): List<Effect> {
        val running = state as? SleepTimerState.Running ?: return emptyList()
        val steps = ((FADE_MS - running.remainingMs).coerceAtLeast(0) / STEP_MS).toInt()
        val next = 1f - steps.toFloat() / FADE_STEPS
        if (next == factor) return emptyList()
        factor = next
        return listOf(Effect.Fade(next))
    }

    companion object {
        const val STEP_MS = 500L
        private const val MINUTE_MS = 60_000L
        private const val MAX_MS = SleepTimerMode.MAX_MINUTES * MINUTE_MS
        private const val FADE_MS = 10_000L
        private const val FADE_STEPS = 20
    }
}
