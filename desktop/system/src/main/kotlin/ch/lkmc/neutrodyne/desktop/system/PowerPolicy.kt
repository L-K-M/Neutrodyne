// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 11 Power's policy, MD2 AC2's automatable part: a `Suspending` notice pauses playback and
 * returns only after the pause landed — the shared controller's paused publication follows
 * its event position save inside the serialized lane, so waiting for it puts the save ahead
 * of the OS freeze; the bound falls back to the ≤ 5-s periodic save when the notice loses
 * the race. After a `Resumed` notice **nothing resumes by itself** (06's start rule): the
 * runner pokes its lanes, the network monitor re-checks, and only the user's play starts
 * audio again.
 *
 * The idle-sleep inhibitor is held exactly while `isPlaying` (policy row 1): pause, end,
 * error, sleep-timer stop and quit all pass through `isPlaying = false`, and [close]
 * releases whatever is left.
 */
class PowerPolicy(
    private val scope: CoroutineScope,
    power: PowerMonitor,
    private val controller: PlaybackController,
    private val state: PlaybackStateSource,
    private val inhibitor: IdleSleepInhibitor,
) {
    private val events = power.events
    private val lastSettle = MutableStateFlow<Boolean?>(null)

    /**
     * The last `Suspending` notice's outcome: null while waiting (or never suspended), true
     * when the paused publication arrived — the position save before it has committed — and
     * false when the settle bound expired first (the ≤ 5-s periodic save bounds the loss).
     * 11's diagnostics list the last suspend and resume.
     */
    val suspendSettled: StateFlow<Boolean?> = lastSettle.asStateFlow()

    fun start() {
        events
            .onEach { event ->
                when (event) {
                    PowerEvent.Suspending -> suspendForSleep()
                    PowerEvent.Resumed -> Unit
                }
            }.launchIn(scope)

        state.nowPlaying
            .map { it?.isPlaying == true }
            .distinctUntilChanged()
            .onEach { playing ->
                if (playing) inhibitor.acquire(INHIBIT_REASON) else inhibitor.release()
            }.launchIn(scope)
    }

    private suspend fun suspendForSleep() {
        controller.pause()
        lastSettle.value =
            withTimeoutOrNull(PAUSE_SETTLE_MS) {
                state.nowPlaying.first { it == null || !it.isPlaying }
                true
            } ?: false
    }

    /** The shutdown half: whatever is held is released; nothing else runs here. */
    fun close() = inhibitor.release()

    private companion object {
        /**
         * Generous against a start transaction still suspending on storage, yet far below
         * what a freeze can wait: the periodic save bounds the residual loss either way.
         */
        const val PAUSE_SETTLE_MS = 2_000L

        const val INHIBIT_REASON = "Neutrodyne is playing"
    }
}
