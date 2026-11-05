// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.settings.CloseBehaviour
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/**
 * 11's hidden-idle quit (the state diagram's `Hidden --> Quitting` row and PO decision 8):
 * with `QUIT_WHEN_IDLE`, a window hidden while busy quits once it has been idle for
 * [HIDDEN_IDLE_QUIT_MS] continuous milliseconds — the grace lets a media-key pause be undone.
 *
 * [clock] is the injected monotonic clock (`elapsedRealtime`), so a suspend/resume does not
 * stretch the deadline and tests drive it with a `TestClock`. The timer lives inside
 * `collectLatest`: any state change — an unhide, a `KEEP_RUNNING` switch, a busy edge —
 * cancels the wait, so the 10 minutes are always continuous. Never returns on its own.
 */
internal suspend fun hiddenIdleQuit(
    clock: Clock,
    hidden: Flow<Boolean>,
    behaviour: Flow<CloseBehaviour>,
    busy: Flow<Boolean>,
    onQuit: () -> Unit,
) {
    combine(hidden, behaviour, busy, ::IdleTriple)
        .collectLatest { state ->
            if (!state.armed) return@collectLatest
            val idleSince = clock.elapsedRealtime()
            while (clock.elapsedRealtime() - idleSince < HIDDEN_IDLE_QUIT_MS) {
                delay(HIDDEN_IDLE_QUIT_MS - (clock.elapsedRealtime() - idleSince))
            }
            onQuit()
        }
}

/** 10 continuous idle minutes (PO decision 8 in 11's open questions). */
internal const val HIDDEN_IDLE_QUIT_MS = 10 * 60 * 1_000L

private data class IdleTriple(
    val hidden: Boolean,
    val behaviour: CloseBehaviour,
    val busy: Boolean,
) {
    /** The quit timer runs only while hidden, idle and under `QUIT_WHEN_IDLE`. */
    val armed: Boolean
        get() = hidden && !busy && behaviour == CloseBehaviour.QUIT_WHEN_IDLE
}
