// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import java.awt.Frame
import java.awt.Window
import javax.swing.SwingUtilities

/** The Playback menu's transport and volume actions (11 Menus), attached by the window. */
internal interface PlaybackMenuActions {
    fun togglePlayPause()

    fun skipBack()

    fun skipForward()

    fun previousChapter()

    fun nextChapter()

    fun previousEpisode()

    fun nextEpisode()

    fun volumeUp()

    fun volumeDown()

    companion object {
        /** The menu's state before the window attaches the controller-backed actions. */
        val Inert: PlaybackMenuActions =
            object : PlaybackMenuActions {
                override fun togglePlayPause() = Unit

                override fun skipBack() = Unit

                override fun skipForward() = Unit

                override fun previousChapter() = Unit

                override fun nextChapter() = Unit

                override fun previousEpisode() = Unit

                override fun nextEpisode() = Unit

                override fun volumeUp() = Unit

                override fun volumeDown() = Unit
            }
    }
}

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

    @Volatile
    private var backAction: () -> Boolean = { false }

    @Volatile
    private var findAction: () -> Boolean = { false }

    // Window-level actions, attached by `NeutrodyneWindow` before the menu composes.
    @Volatile
    var refresh: () -> Unit = {}

    @Volatile
    var closeWindow: () -> Unit = {}

    @Volatile
    var quit: () -> Unit = {}

    @Volatile
    var playback: PlaybackMenuActions = PlaybackMenuActions.Inert

    @Volatile
    var setSpeed: (Float) -> Unit = {}

    @Volatile
    var openSpeedSheet: () -> Unit = {}

    @Volatile
    var setSleepTimer: (SleepTimerMode) -> Unit = {}

    @Volatile
    var setSkipSilence: (Boolean) -> Unit = {}

    @Volatile
    var reportProblem: () -> Unit = {}

    /** Called from the player slot's composition inside the root (`navigation::push`). */
    fun attachPush(navigate: (NavKey) -> Unit) {
        push = navigate
    }

    /** The root's back action (Esc; the Go menu's Back), attached from the player slot. */
    fun attachBack(back: () -> Boolean) {
        backAction = back
    }

    /** The root's Find action (Ctrl/Cmd+F; the Edit menu's Find). */
    fun attachFind(find: () -> Boolean) {
        findAction = find
    }

    /** Runs the root's back action; false when nothing handled it. */
    fun back(): Boolean = backAction()

    /** Runs the root's Find action; false when no destination has a search field. */
    fun find(): Boolean = findAction()

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
     * Selects [key]'s tab on the AWT event thread — `AppNavigator` state lives there. Returns
     * false while the root has not composed; the AOT training journey polls this until the
     * navigator is attached, then walks the five destinations (11 AOT cache).
     */
    fun selectTab(key: TopLevelKey): Boolean {
        val current = navigator ?: return false
        SwingUtilities.invokeLater { current.selectTab(key) }
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

    /**
     * 11 No tray available: the hide fallback when `SystemTray.isSupported()` is false — the
     * app stays reachable from the taskbar (and the Dock on macOS).
     */
    fun iconify() {
        val current = window ?: return
        SwingUtilities.invokeLater {
            if (current is Frame) current.extendedState = current.extendedState or Frame.ICONIFIED
        }
    }
}
