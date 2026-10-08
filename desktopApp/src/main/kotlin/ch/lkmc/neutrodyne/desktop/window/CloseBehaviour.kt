// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

/**
 * `desktop.close_behaviour` (11 Close request row). The setting itself arrives with
 * Settings › Desktop; the M0b window always runs the default.
 */
internal enum class CloseBehaviour {
    /** Hide to the tray while busy, quit while idle (the default). */
    QUIT_WHEN_IDLE,

    /** Always keep running in the tray. */
    KEEP_RUNNING,
}

/** What a close request does (11 Window and tray behaviour's state diagram). */
internal enum class CloseOutcome {
    /** Quit through the clean-shutdown path (`session.json` `cleanExit`, lock release, log flush). */
    QUIT,

    /** Hide the window to the tray and keep running (MD2). */
    HIDE_TO_TRAY,
}

/**
 * 11's close rule as a pure function: `KEEP_RUNNING` always hides; `QUIT_WHEN_IDLE` hides only
 * while busy. At M0b nothing can be busy (playback arrives MD1, transfers M6), so every close
 * request quits — the branches stay because the rule is the tested contract (11's
 * `CloseBehaviourTest` row).
 */
internal fun closeRequestOutcome(
    behaviour: CloseBehaviour,
    busy: Boolean,
): CloseOutcome =
    when (behaviour) {
        CloseBehaviour.KEEP_RUNNING -> CloseOutcome.HIDE_TO_TRAY
        CloseBehaviour.QUIT_WHEN_IDLE -> if (busy) CloseOutcome.HIDE_TO_TRAY else CloseOutcome.QUIT
    }

/**
 * 11 Tray icon row: the tray is shown **only** while the window is hidden. At M0b the window is
 * never hidden (a busy close and `KEEP_RUNNING` are MD2), so the tray stays uncreated — this
 * rule keeps the code path and its test honest.
 */
internal fun shouldShowTray(windowHidden: Boolean): Boolean = windowHidden
