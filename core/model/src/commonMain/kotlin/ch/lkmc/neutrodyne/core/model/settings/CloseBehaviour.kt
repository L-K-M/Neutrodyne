// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/**
 * `desktop.close_behaviour` (11 Desktop settings, the "When the window is closed" row). The
 * enum lives in `:core:model` like every type a setting key references; the rule that applies
 * it is `:desktopApp`'s `closeRequestOutcome`.
 */
public enum class CloseBehaviour {
    /** Hide to the tray while busy, quit while idle (the default). */
    QUIT_WHEN_IDLE,

    /** Always keep running in the tray. */
    KEEP_RUNNING,
}
