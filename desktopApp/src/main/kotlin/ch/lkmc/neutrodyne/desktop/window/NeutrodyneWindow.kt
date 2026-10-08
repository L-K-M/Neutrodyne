// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.designsystem.components.DesktopScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.rememberDesktopPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.tray_quit
import ch.lkmc.neutrodyne.desktop.resources.tray_show
import ch.lkmc.neutrodyne.desktop.resources.tray_tooltip
import ch.lkmc.neutrodyne.desktop.resources.window_title
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import java.awt.SystemTray

/**
 * The desktop's one window (11 Start-up sequence step 6, Window and tray behaviour): titled
 * "Neutrodyne" with the brand icon, 1200 × 800 dp clamped to 90 % of the primary screen and
 * centred, minimum 600 × 480 dp (PO-19). At M0b nothing can be busy, so every close request
 * (the close button, Ctrl/Cmd+W, the macOS Quit item) quits through the clean-shutdown path
 * the shell runs after `application { }` returns; window bounds persistence is MD4.
 *
 * [onQuitRequest] ends the application scope (`exitApplication`); [background] starts the
 * window iconified (11's state diagram, the `--background` launch of start-at-login).
 */
@Composable
internal fun ApplicationScope.NeutrodyneWindow(
    installers: Set<EntryProviderInstaller>,
    menuActions: DesktopMenuActions,
    activator: WindowActivator,
    background: Boolean,
    onQuitRequest: () -> Unit,
) {
    val state = rememberNeutrodyneWindowState()
    val icon = remember { WindowIcons.windowIconPainter() }
    var hidden by remember { mutableStateOf(false) }

    // The Compose-side `hidden` owns visibility; a hand-off reveal clears it first, so the
    // activator's direct `isVisible` never fights the next recomposition.
    DisposableEffect(activator) {
        activator.unhide = { hidden = false }
        onDispose { activator.unhide = {} }
    }

    Window(
        onCloseRequest = { requestClose(onQuitRequest) { hidden = it } },
        state = state,
        visible = !hidden,
        title = stringResource(Res.string.window_title),
        icon = icon,
        onKeyEvent = { event -> windowShortcut(event) { requestClose(onQuitRequest) { hidden = it } } },
    ) {
        WindowEffects(activator = activator, background = background)
        DesktopApplicationMenu(menuActions = menuActions, onQuit = onQuitRequest)
        NeutrodyneWindowContent(installers = installers, menuActions = menuActions)
    }

    if (shouldShowTray(hidden) && SystemTray.isSupported()) {
        val trayIcon = remember { WindowIcons.trayIconPainter() }
        if (trayIcon != null) {
            Tray(
                icon = trayIcon,
                tooltip = stringResource(Res.string.tray_tooltip),
            ) {
                Item(stringResource(Res.string.tray_show)) {
                    hidden = false
                    activator.bringToFront()
                }
                Item(stringResource(Res.string.tray_quit)) { onQuitRequest() }
            }
        }
    }
}

/**
 * The initial window state (11 Window state): the default bounds of [defaultWindowBounds] when
 * a screen is readable, else Compose's platform default placement. Also smoke mode's window
 * step, so the smoke first frame is the real window.
 */
@Composable
internal fun rememberNeutrodyneWindowState(): WindowState {
    val bounds = remember { WindowIcons.primaryScreen()?.let(::defaultWindowBounds) }
    return rememberWindowState(
        width = bounds?.width ?: WindowBounds.DEFAULT_WIDTH,
        height = bounds?.height ?: WindowBounds.DEFAULT_HEIGHT,
        position = bounds?.let { WindowPosition(it.x, it.y) } ?: WindowPosition.PlatformDefault,
    )
}

/**
 * 11's close rule at M0b: nothing is busy (playback arrives MD1, transfers M6) and the
 * behaviour setting with it, so the outcome is always `QUIT`; the hide branch stays for the
 * rule's shape.
 */
private fun requestClose(
    onQuitRequest: () -> Unit,
    setHidden: (Boolean) -> Unit,
) {
    when (closeRequestOutcome(CloseBehaviour.QUIT_WHEN_IDLE, busy = false)) {
        CloseOutcome.QUIT -> onQuitRequest()
        CloseOutcome.HIDE_TO_TRAY -> setHidden(true)
    }
}

/** Ctrl/Cmd+W and Ctrl/Cmd+Q (11 Keyboard shortcuts): both are close requests at M0b. */
private fun windowShortcut(
    event: KeyEvent,
    onCloseRequest: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (!event.isCtrlPressed && !event.isMetaPressed) return false

    return when (event.key) {
        Key.W, Key.Q -> {
            onCloseRequest()
            true
        }

        else -> {
            false
        }
    }
}

/** The AWT-side window rules: minimum size (PO-19), iconified start, hand-off activation. */
@Composable
private fun androidx.compose.ui.window.FrameWindowScope.WindowEffects(
    activator: WindowActivator,
    background: Boolean,
) {
    val density = LocalDensity.current
    DisposableEffect(window, density) {
        window.minimumSize =
            with(density) {
                Dimension(
                    WindowBounds.MIN_WIDTH.roundToPx(),
                    WindowBounds.MIN_HEIGHT.roundToPx(),
                )
            }

        // 11's `--background` start: iconified until the user restores it.
        if (background) window.extendedState = window.extendedState or Frame.ICONIFIED
        activator.register(window)
        onDispose { activator.unregister(window) }
    }
}

/**
 * The macOS application menu (11 Menus): About, Settings… (Cmd+,) and Quit (Cmd+Q) arrive
 * through `java.awt.Desktop`'s handlers — AWT builds and labels those items itself, so a
 * Compose `MenuBar` here would duplicate them; the in-window menu bar of Windows and Linux is
 * MD4 (11 Desktop UX). On the other OSes this composes nothing.
 */
@Composable
private fun DesktopApplicationMenu(
    menuActions: DesktopMenuActions,
    onQuit: () -> Unit,
) {
    DisposableEffect(menuActions, onQuit) {
        val desktop =
            if (AppDirs.DesktopOs.current() != AppDirs.DesktopOs.MACOS) {
                null // the application menu exists on macOS only (11 Menus)
            } else {
                runCatching { Desktop.getDesktop() }.getOrNull()
            }
        if (desktop != null) {
            desktop.setAboutHandler { menuActions.pushKey(SettingsKey(SettingsPage.ABOUT)) }
            desktop.setPreferencesHandler { menuActions.pushKey(SettingsHomeKey) }
            desktop.setQuitHandler { _, response ->
                // The clean path quits after `application { }` returns; cancel macOS's own quit.
                response.cancelQuit()
                onQuit()
            }
        }

        onDispose {
            desktop?.setAboutHandler(null)
            desktop?.setPreferencesHandler(null)
            desktop?.setQuitHandler(null)
        }
    }
}

/** Nothing to retry, dismiss or play before M1/M4: the root's callbacks are inert (M0). */
private val M0_ROOT_ACTIONS =
    RootActions(
        retryStartup = {},
        dismissNotice = {},
        continueHere = {},
        dismissRemoteSession = {},
        playbackKey = { false },
    )

/**
 * What fills the window: the shared [NeutrodyneRoot] under the desktop platform actions
 * (08 Root contract) with the OS dark mode where Compose exposes it. The same composition is
 * `:desktopApp:run`, the window-content UI test and smoke mode's first frame.
 */
@Composable
internal fun NeutrodyneWindowContent(
    installers: Set<EntryProviderInstaller>,
    menuActions: DesktopMenuActions,
) {
    val systemUi = SystemUiState.DEFAULT.copy(dark = isSystemInDarkTheme())
    CompositionLocalProvider(
        LocalPlatformActions provides rememberDesktopPlatformActions(),
        LocalScrollbars provides DesktopScrollbars.style,
    ) {
        NeutrodyneRoot(
            state = RootUiState.READY,
            actions = M0_ROOT_ACTIONS,
            slots =
                RootSlots(
                    // No player before M4; the slot composes inside the root, where the
                    // navigator is readable — it hands the shell's menus the root's push
                    // (recorded in 11, 2026-10-06).
                    player = { menuActions.attachPush(it.navigate) },
                    userMessages = emptyFlow(),
                ),
            installers = installers,
            systemUi = systemUi,
            platform = BuildInfo.Platform.DESKTOP,
        )
    }
}
