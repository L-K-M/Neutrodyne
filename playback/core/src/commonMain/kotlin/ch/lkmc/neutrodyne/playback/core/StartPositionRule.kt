// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import dev.zacsweers.metro.Inject

@Inject
class StartPositionRule {
    /** Smart resume is opt-in here; the M5 adapter owns the setting and live-pause clock. */
    fun startPositionMs(
        saved: Long?,
        savedAt: Long?,
        durationMs: Long?,
        played: Boolean,
        now: Long,
        smartResume: Boolean = false,
    ): Long {
        if (played || saved == null || saved <= START_IGNORE_MS) return 0
        if (durationMs != null && durationMs > 0 && saved >= durationMs - END_IGNORE_MS) return 0

        val rewind =
            if (smartResume && savedAt != null && now > savedAt &&
                now - savedAt > STALE_MS
            ) {
                START_IGNORE_MS
            } else {
                0
            }
        return (saved - rewind).coerceAtLeast(0)
    }

    private companion object {
        const val START_IGNORE_MS = 5_000L
        const val END_IGNORE_MS = 30_000L
        const val STALE_MS = 5 * 60_000L
    }
}
