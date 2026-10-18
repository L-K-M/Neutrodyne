// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.Route
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

    @Volatile
    private var navigator: AppNavigator? = null

    /** Called from the player slot's composition inside the root (`navigation::push`). */
    fun attachPush(navigate: (NavKey) -> Unit) {
        push = navigate
    }

    /** The full navigator — also attached from the player slot's composition. */
    fun attachNavigator(navigator: AppNavigator) {
        this.navigator = navigator
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

    /**
     * Applies an [IntentRouter] route (a notification's click target, 11 Notifications) on the
     * AWT thread. Returns false while the root has not composed — the caller's banner is gone
     * by the next compose anyway, matching the OS-notification rule that clicks after the app
     * has quit apply nothing.
     */
    fun applyRoute(route: Route): Boolean {
        val current = navigator ?: return false
        SwingUtilities.invokeLater {
            when (route) {
                is Route.Navigate -> current.open(route.tab, route.stack)

                is Route.Push -> current.push(route.key)

                // `groupUuid` selects the Feeds pager's group — not a navigator-level API;
                // no notification route emits SelectFeed (IntentRouter maps only open/*).
                is Route.SelectFeed -> current.selectTab(FeedsKey)

                Route.ExpandPlayer, Route.None -> Unit
            }
        }
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

    /**
     * Clears the window's hidden state before a reveal (the "from the tray if hidden" half of
     * 11's Hand-off row) — the composition owns `visible = !hidden`, so a reveal that skipped
     * this would be undone by the next recomposition. Set by the window's composition.
     */
    @Volatile
    var unhide: () -> Unit = {}

    fun register(window: Window) {
        this.window = window
    }

    fun unregister(window: Window) {
        if (this.window === window) this.window = null
    }

    fun bringToFront() {
        val current = window ?: return
        SwingUtilities.invokeLater {
            unhide()
            current.isVisible = true
            if (current is Frame && current.state == Frame.ICONIFIED) current.state = Frame.NORMAL
            current.toFront()
        }
    }
}
