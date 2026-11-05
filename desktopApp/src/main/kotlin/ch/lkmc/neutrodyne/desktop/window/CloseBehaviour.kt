// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.model.settings.CloseBehaviour

/**
 * What a close request does (11 Window and tray behaviour's state diagram). The behaviour enum
 * itself is `desktop.close_behaviour`'s value type in `:core:model` ([CloseBehaviour]).
 */
internal enum class CloseOutcome {
    /** Quit through the clean-shutdown path (`session.json` `cleanExit`, lock release, log flush). */
    QUIT,

    /** Hide the window to the tray and keep running (composition kept, so reopening is instant). */
    HIDE_TO_TRAY,

    /**
     * 11 No tray available: iconify instead of hiding so the app stays reachable from the
     * taskbar when `SystemTray.isSupported()` is false.
     */
    ICONIFY,
}

/**
 * 11's close rule as a pure function: `KEEP_RUNNING` always hides; `QUIT_WHEN_IDLE` hides only
 * while busy. Without a tray the hide falls back to iconify, so the process stays reachable.
 */
internal fun closeRequestOutcome(
    behaviour: CloseBehaviour,
    busy: Boolean,
    trayAvailable: Boolean,
): CloseOutcome =
    when (behaviour) {
        CloseBehaviour.KEEP_RUNNING ->
            if (trayAvailable) CloseOutcome.HIDE_TO_TRAY else CloseOutcome.ICONIFY
        CloseBehaviour.QUIT_WHEN_IDLE ->
            when {
                !busy -> CloseOutcome.QUIT
                trayAvailable -> CloseOutcome.HIDE_TO_TRAY
                else -> CloseOutcome.ICONIFY
            }
    }

/**
 * 11 Tray icon row: the tray is shown **only** while the window is hidden. Callers additionally
 * check `SystemTray.isSupported()` — an unsupported tray iconifies rather than hides, so the
 * rule never sees that case.
 */
internal fun shouldShowTray(windowHidden: Boolean): Boolean = windowHidden
