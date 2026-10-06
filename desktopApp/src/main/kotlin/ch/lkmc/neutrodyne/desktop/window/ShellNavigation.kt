// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.navigation3.runtime.NavKey
import java.awt.Frame
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * Hands shell-driven navigation (the macOS application menu) to the root's `AppNavigator`
 * (the task's rule: menus never touch a back stack directly). The shell cannot read
 * `LocalAppNavigator` above `NeutrodyneRoot` — the player slot composes *inside* the root, so
 * its composition stores the root-provided push here (recorded in 11, 2026-10-06; MD4's
 * `DesktopMenuBar` replaces this M0b bridge with a first-class shell navigator).
 */
internal class DesktopMenuActions {
    @Volatile
    private var push: ((NavKey) -> Unit)? = null

    /** Called from the player slot's composition inside the root (`navigation::push`). */
    fun attachPush(navigate: (NavKey) -> Unit) {
        push = navigate
    }

    /**
     * Dispatches [key] onto the AWT event thread — `AppNavigator` state lives there. Returns
     * false while the root has not composed (the menu then simply does nothing).
     */
    fun pushKey(key: NavKey): Boolean {
        val navigate = push ?: return false
        SwingUtilities.invokeLater { navigate(key) }
        return true
    }
}

/**
 * Brings the window to the front for a hand-off's `activate` (11 Single instance, Hand-off row:
 * "shows the window (from the tray if hidden), de-iconifies it and calls `toFront()`"). The
 * window content registers its AWT frame here; [bringToFront] is safe from any thread — the
 * Windows second launch has already called `AllowSetForegroundWindow` through
 * [ch.lkmc.neutrodyne.desktop.shell.InstanceHandshake.send].
 */
internal class WindowActivator {
    @Volatile
    private var window: Window? = null

    fun register(window: Window) {
        this.window = window
    }

    fun unregister(window: Window) {
        if (this.window === window) this.window = null
    }

    fun bringToFront() {
        val current = window ?: return
        SwingUtilities.invokeLater {
            current.isVisible = true
            if (current is Frame && current.state == Frame.ICONIFIED) current.state = Frame.NORMAL
            current.toFront()
        }
    }
}
