// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
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
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.designsystem.components.DesktopScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.BrandColors
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.update.AppUpdateChecker
import ch.lkmc.neutrodyne.core.domain.update.UpdateNotices
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.CloseBehaviour
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import ch.lkmc.neutrodyne.core.model.settings.YouTubeRowArt
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalYouTubeRowArt
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.rememberDesktopPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.PlaybackKey
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import ch.lkmc.neutrodyne.core.ui.root.StartupGateState
import ch.lkmc.neutrodyne.core.ui.root.dispatchPlaybackKey
import ch.lkmc.neutrodyne.desktop.DesktopOpenHandler
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashOffer
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashOfferDialog
import ch.lkmc.neutrodyne.desktop.notifications.DesktopBannerNotifier
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.no_tray_hint_body
import ch.lkmc.neutrodyne.desktop.resources.no_tray_hint_title
import ch.lkmc.neutrodyne.desktop.resources.tray_next
import ch.lkmc.neutrodyne.desktop.resources.tray_pause
import ch.lkmc.neutrodyne.desktop.resources.tray_play
import ch.lkmc.neutrodyne.desktop.resources.tray_quit
import ch.lkmc.neutrodyne.desktop.resources.tray_show
import ch.lkmc.neutrodyne.desktop.resources.window_title
import ch.lkmc.neutrodyne.desktop.resources.window_title_playing
import ch.lkmc.neutrodyne.feature.importexport.importResumeBanner
import ch.lkmc.neutrodyne.feature.player.PlayerRoute
import ch.lkmc.neutrodyne.feature.queue.PlayerUpNextRoute
import ch.lkmc.neutrodyne.feature.settings.ReconnectDismissals
import ch.lkmc.neutrodyne.feature.settings.observeAppearance
import ch.lkmc.neutrodyne.feature.settings.rememberSyncRootPresentation
import ch.lkmc.neutrodyne.feature.settings.rememberUpdateRootPresentation
import ch.lkmc.neutrodyne.feature.settings.serialUserMessages
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.sync.api.SyncController
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.Frame
import java.awt.SystemTray
import java.awt.desktop.AppReopenedListener
import java.nio.file.Path

/**
 * The desktop's one window (11 Start-up sequence step 6, Window and tray behaviour): titled
 * "Neutrodyne" — "{episode title} — Neutrodyne" while playing — with the brand icon,
 * 1200 × 800 dp clamped to 90 % of the primary screen and centred, minimum 600 × 480 dp
 * (PO-19). A close request (the close button, Ctrl/Cmd+W, the macOS Quit item) runs
 * [closeRequestOutcome] over `desktop.close_behaviour` and [busySignal]: idle under
 * `QUIT_WHEN_IDLE` quits through the clean-shutdown path the shell runs after
 * `application { }` returns; busy hides to the tray (iconifies without one, with the
 * one-time hint of [maybeShowNoTrayHint]); `KEEP_RUNNING` always hides. A hidden window
 * still under `QUIT_WHEN_IDLE` quits after 10 continuous idle minutes ([hiddenIdleQuit]);
 * window bounds persistence is MD4.
 *
 * [onQuitRequest] ends the application scope (`exitApplication`); [background] starts the
 * window iconified (11's state diagram, the `--background` launch of start-at-login). The
 * content is gated on [databaseOpener]'s open like Android's `StartupGate` (01 Splash and
 * start-up gate): "Try again" re-runs `awaitOpen()` on [appScope], and a `DISK_FULL` failure
 * reveals [dataDir] in the platform file manager.
 */
@Composable
internal fun ApplicationScope.NeutrodyneWindow(
    installers: Set<EntryProviderInstaller>,
    menuActions: DesktopMenuActions,
    activator: WindowActivator,
    banners: DesktopBannerNotifier,
    background: Boolean,
    databaseOpener: DatabaseOpener,
    appScope: CoroutineScope,
    dataDir: Path,
    viewModelFactory: MetroViewModelFactory,
    playbackController: PlaybackController,
    playbackState: PlaybackStateSource,
    busySignal: AppBusySignal,
    clock: Clock,
    syncFocusPoke: () -> Unit,
    onQuitRequest: () -> Unit,
    onWindowFocusGain: () -> Unit = {},
    portal: ch.lkmc.neutrodyne.core.common.LinuxDesktopPortal? = null,
    openHandler: DesktopOpenHandler? = null,
    failedDownloads: kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.emptyFlow(),
    updateChecker: AppUpdateChecker? = null,
    updateNotices: UpdateNotices? = null,
    settings: SettingsRepository? = null,
    rowArt: YouTubeRowArt = YouTubeRowArt.VIDEO_THUMBNAIL,
    refreshController: ch.lkmc.neutrodyne.core.domain.RefreshController? = null,
    desktopPlaybackController: ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackController? = null,
    repoUrl: String = "",
    crashOffer: DesktopCrashOffer? = null,
    syncController: (() -> SyncController)? = null,
    reconnectDismissals: () -> ReconnectDismissals = { ReconnectDismissals() },
) {
    val state = rememberNeutrodyneWindowState()
    val icon = remember { WindowIcons.windowIconPainter() }
    var hidden by remember { mutableStateOf(false) }
    val openState by databaseOpener.openState.collectAsState()
    val nowPlaying by playbackState.nowPlaying.collectAsState()
    val busy by busySignal.busy.collectAsState(initial = false)
    val closeBehaviour by (
        settings?.observe(DesktopSettingKeys.CLOSE_BEHAVIOUR)
            ?: kotlinx.coroutines.flow.flowOf(CloseBehaviour.QUIT_WHEN_IDLE)
    ).collectAsState(initial = CloseBehaviour.QUIT_WHEN_IDLE)
    // The root reports every text-field focus transition so the menu can clear its
    // accelerators (AWT processes them before Compose's key input; 08 Keyboard and mouse).
    var textFieldFocused by remember { mutableStateOf(false) }
    val skipSilenceEnabled by (
        settings?.observe(PlaybackSettingKeys.SKIP_SILENCE)
            ?: kotlinx.coroutines.flow.flowOf(false)
    ).collectAsState(initial = false)
    val speedPresets by (
        settings
            ?.observe(PlaybackSettingKeys.SPEED_PRESETS)
            ?.map { PlaybackSettingKeys.parseSpeedPresets(it) ?: emptyList() }
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    ).collectAsState(initial = emptyList())
    val userVolume =
        remember(desktopPlaybackController, settings, appScope) {
            desktopPlaybackController?.let { DesktopUserVolume(it, settings, appScope) }
        }
    LaunchedEffect(userVolume) { userVolume?.restore() }
    // 11 Menus' Refresh and Ctrl/Cmd+R / F5: the window-level action both paths share.
    val refreshAll = {
        refreshController?.refreshNow(ch.lkmc.neutrodyne.core.domain.RefreshScope.All)
        Unit
    }
    val playbackMenuActions =
        remember(playbackController, userVolume) {
            object : PlaybackMenuActions {
                override fun togglePlayPause() = playbackController.togglePlayPause()

                override fun skipBack() = playbackController.skipBack()

                override fun skipForward() = playbackController.skipForward()

                override fun previousChapter() = playbackController.previousChapter()

                override fun nextChapter() = playbackController.nextChapter()

                override fun previousEpisode() = playbackController.skipToPrevious()

                override fun nextEpisode() = playbackController.skipToNext()

                override fun volumeUp() {
                    userVolume?.volumeUp()
                }

                override fun volumeDown() {
                    userVolume?.volumeDown()
                }
            }
        }

    // 11's hidden-idle quit: any state change restarts the continuous 10 idle minutes.
    val quitRequest by rememberUpdatedState(onQuitRequest)
    LaunchedEffect(clock) {
        hiddenIdleQuit(
            clock,
            hidden = snapshotFlow { hidden },
            behaviour = snapshotFlow { closeBehaviour },
            busy = busySignal.busy,
            onQuit = { quitRequest() },
        )
    }

    // macOS conventions row: a Dock click while hidden reopens the window (11's
    // AppReopenedListener); bringToFront already unhides and de-iconifies.
    DisposableEffect(activator) {
        val desktop =
            if (AppDirs.DesktopOs.current() == AppDirs.DesktopOs.MACOS) {
                runCatching { Desktop.getDesktop() }.getOrNull()
            } else {
                null
            }
        val listener = AppReopenedListener { activator.bringToFront() }
        desktop?.addAppEventListener(listener)
        onDispose { desktop?.removeAppEventListener(listener) }
    }

    val appearance by produceState<AppearancePrefs?>(null, settings) {
        launch {
            delay(APPEARANCE_GATE_MS)
            if (value == null) value = AppearancePrefs()
        }
        if (settings == null) value = AppearancePrefs() else settings.observeAppearance().collect { value = it }
    }
    val osDark = isSystemInDarkTheme()
    val prefs = appearance ?: AppearancePrefs()
    val dark =
        when (prefs.theme) {
            ThemeMode.SYSTEM -> osDark
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }

    // The Compose-side `hidden` owns visibility; a hand-off reveal clears it first, so the
    // activator's direct `isVisible` never fights the next recomposition.
    DisposableEffect(activator) {
        activator.unhide = { hidden = false }
        onDispose { activator.unhide = {} }
    }

    Window(
        onCloseRequest = {
            requestClose(
                behaviour = closeBehaviour,
                busy = busy,
                onQuit = onQuitRequest,
                onHide = { hidden = true },
                onIconify = {
                    activator.iconify()
                    maybeShowNoTrayHint(appScope, settings, banners)
                },
            )
        },
        state = state,
        visible = !hidden && appearance != null,
        title = windowTitle(nowPlaying?.takeIf { it.isPlaying }?.title),
        icon = icon,
        onKeyEvent = { event ->
            windowShortcut(event) {
                requestClose(
                    behaviour = closeBehaviour,
                    busy = busy,
                    onQuit = onQuitRequest,
                    onHide = { hidden = true },
                    onIconify = {
                        activator.iconify()
                        maybeShowNoTrayHint(appScope, settings, banners)
                    },
                )
            }
        },
    ) {
        // Set the AWT background during apply, before Window's visibility side effect.
        DisposableEffect(window, dark, prefs.pureBlack) {
            val argb =
                if (dark && prefs.pureBlack) {
                    0xFF000000.toInt()
                } else {
                    BrandColors.scheme(dark, 0.0).background.toArgb()
                }
            window.background = java.awt.Color(argb, true)
            onDispose { }
        }
        // 10 Scheduling on the desktop: a refocus after > 60 s away pokes the `sync` lane; the
        // callback itself is cheap — the trigger owns the cooldown and the lane poke.
        WindowEffects(
            activator = activator,
            background = background,
            onFocusGain = {
                onWindowFocusGain()
                syncFocusPoke()
            },
        )
        // The menu's actions are attached here, where the window's own services and state are
        // in scope; the navigation and player-slot actions arrive from the content below.
        val closeRequest = {
            requestClose(
                behaviour = closeBehaviour,
                busy = busy,
                onQuit = onQuitRequest,
                onHide = { hidden = true },
                onIconify = {
                    activator.iconify()
                    maybeShowNoTrayHint(appScope, settings, banners)
                },
            )
        }
        SideEffect {
            menuActions.refresh = refreshAll
            menuActions.closeWindow = closeRequest
            menuActions.quit = onQuitRequest
            menuActions.playback = playbackMenuActions
            menuActions.setSpeed = { speed ->
                appScope.launch {
                    playbackController.setSpeed(
                        speed,
                        ch.lkmc.neutrodyne.core.model.SettingScope.GLOBAL,
                    )
                }
            }
            menuActions.openSpeedSheet = { menuActions.pushKey(SpeedKey) }
            menuActions.setSleepTimer = { mode -> playbackController.setSleepTimer(mode) }
            menuActions.setSkipSilence = { enabled ->
                appScope.launch {
                    playbackController.setSkipSilence(enabled, ch.lkmc.neutrodyne.core.model.SettingScope.GLOBAL)
                }
            }
            menuActions.reportProblem = {
                runCatching {
                    java.awt.Desktop
                        .getDesktop()
                        .browse(java.net.URI.create("$repoUrl/issues"))
                }.onFailure { Log.w("NeutrodyneWindow", it) { "opening the issue page failed" } }
            }
        }
        DesktopMenuBar(
            actions = menuActions,
            enabled = openState is DatabaseOpenState.Opened,
            textFieldFocused = textFieldFocused,
            isPlaying = nowPlaying?.isPlaying == true,
            skipSilenceEnabled = skipSilenceEnabled,
            speedPresets = speedPresets,
        )
        DesktopApplicationMenu(menuActions = menuActions, onQuit = onQuitRequest)
        val content: @Composable () -> Unit = {
            NeutrodyneWindowContent(
                prefs = prefs,
                rowArt = rowArt,
                installers = installers,
                menuActions = menuActions,
                banners = banners,
                startup = openState.toGateState(),
                onRetryStartup = { appScope.launch { suspendRunCatching { databaseOpener.awaitOpen() } } },
                dataDir = dataDir,
                viewModelFactory = viewModelFactory,
                playbackController = playbackController,
                playbackState = playbackState,
                syncController = syncController,
                reconnectDismissals = reconnectDismissals,
                portal = portal,
                openHandler = openHandler,
                failedDownloads = failedDownloads,
                updateChecker = updateChecker,
                updateNotices = updateNotices,
                settings = settings,
                refresh = refreshAll,
                onTextFieldFocusChange = { textFieldFocused = it },
                volumeUp = {
                    userVolume?.volumeUp()
                    Unit
                },
                volumeDown = {
                    userVolume?.volumeDown()
                    Unit
                },
                crashOffer = crashOffer,
            )
        }
        if (openHandler == null) content() else DesktopDropTarget(openHandler, content)
    }

    if (shouldShowTray(hidden) && SystemTray.isSupported()) {
        val trayIcon = remember { WindowIcons.trayIconPainter() }
        if (trayIcon != null) {
            Tray(
                icon = trayIcon,
                tooltip = trayTooltip(nowPlaying),
                // 11 Tray icon's Windows row: the icon's default action shows the window.
                onAction = { activator.bringToFront() },
            ) {
                Item(stringResource(Res.string.tray_show)) {
                    hidden = false
                    activator.bringToFront()
                }
                Item(
                    stringResource(
                        when (trayPlayPause(nowPlaying)) {
                            TrayPlayPause.PAUSE -> Res.string.tray_pause
                            TrayPlayPause.PLAY -> Res.string.tray_play
                        },
                    ),
                    enabled = trayPlayPauseEnabled(nowPlaying),
                ) {
                    playbackController.togglePlayPause()
                }
                Item(stringResource(Res.string.tray_next), enabled = trayNextEnabled(nowPlaying)) {
                    playbackController.skipToNext()
                }
                Separator()
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

/** The window title rule (11's rules table): "{episode title} — Neutrodyne" while playing. */
@Composable
private fun windowTitle(episodeTitle: String?): String =
    if (episodeTitle == null) {
        stringResource(Res.string.window_title)
    } else {
        stringResource(Res.string.window_title_playing, episodeTitle)
    }

/**
 * 11's close rule, MD2-wired: [behaviour] is `desktop.close_behaviour` and [busy] the
 * `AppBusySignal` — a busy close hides (or iconifies through [onIconify] without a tray),
 * an idle close under `QUIT_WHEN_IDLE` quits.
 */
private fun requestClose(
    behaviour: CloseBehaviour,
    busy: Boolean,
    onQuit: () -> Unit,
    onHide: () -> Unit,
    onIconify: () -> Unit,
) {
    when (closeRequestOutcome(behaviour, busy, SystemTray.isSupported())) {
        CloseOutcome.QUIT -> onQuit()
        CloseOutcome.HIDE_TO_TRAY -> onHide()
        CloseOutcome.ICONIFY -> onIconify()
    }
}

/**
 * 11 No tray available's one-time hint: after an iconified busy close, a banner explains why
 * the window went to the taskbar instead of a tray — persisted through
 * `desktop.tray_hint_shown` so it shows exactly once.
 */
internal fun maybeShowNoTrayHint(
    appScope: CoroutineScope,
    settings: SettingsRepository?,
    banners: DesktopBannerNotifier,
) {
    appScope.launch {
        suspendRunCatching {
            val title = getString(Res.string.no_tray_hint_title)
            val body = getString(Res.string.no_tray_hint_body)
            showNoTrayHintOnce(settings, banners::post, title, body)
        }.onFailure { Log.w("NeutrodyneWindow", it) { "no-tray hint failed" } }
    }
}

/**
 * The hint's one-time rule, separate from Compose string resolution so the ordering guard
 * stays unit-testable: the banner is posted first and the `desktop.tray_hint_shown` flag
 * written only after the post returned, so a failing post leaves the hint showable instead
 * of suppressing it forever. Any failure is captured for the caller to log.
 */
internal suspend fun showNoTrayHintOnce(
    settings: SettingsRepository?,
    post: suspend (DesktopNotification) -> Unit,
    title: String,
    body: String,
) {
    suspendRunCatching {
        val repo = settings ?: return@suspendRunCatching
        if (repo.get(DesktopSettingKeys.TRAY_HINT_SHOWN)) return@suspendRunCatching
        post(
            DesktopNotification(
                id = NOTIFICATION_ID_NO_TRAY,
                kind = NotificationKind.APP_SHELL,
                title = title,
                body = body,
                route = null,
            ),
        )
        // Only a returned post counts: a throw above leaves the flag clear, so the hint can
        // be shown again on a later close instead of being suppressed forever.
        repo.set(DesktopSettingKeys.TRAY_HINT_SHOWN, true)
    }
}

private const val NOTIFICATION_ID_NO_TRAY = "shell-no-tray-hint"

/** Ctrl/Cmd+W and Ctrl/Cmd+Q (11 Keyboard shortcuts): both are close requests. */
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
    onFocusGain: () -> Unit,
) {
    val density = LocalDensity.current
    // Referenced inside the effect below: a restarting effect must read the current
    // callback, not capture the composition-time lambda.
    val focusGain by androidx.compose.runtime.rememberUpdatedState(onFocusGain)
    DisposableEffect(window, density) {
        window.minimumSize = minimumWindowDimension(density)

        // 11's `--background` start: iconified until the user restores it.
        if (background) window.extendedState = window.extendedState or Frame.ICONIFIED
        activator.register(window)
        // 03's desktop new-episode poster: focus clears the unseen set.
        val focusListener =
            object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(event: java.awt.event.WindowEvent) = focusGain()

                override fun windowLostFocus(event: java.awt.event.WindowEvent) = Unit
            }
        window.addWindowFocusListener(focusListener)
        onDispose {
            window.removeWindowFocusListener(focusListener)
            activator.unregister(window)
        }
    }
}

/**
 * The macOS application menu (11 Menus): About, Settings… (Cmd+,) and Quit (Cmd+Q) arrive
 * through `java.awt.Desktop` handlers. AWT builds and labels these items; Hide stays
 * system-provided. On the other OSes this composes nothing (their File menu carries Quit).
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

/**
 * What fills the window: the shared [NeutrodyneRoot] under the desktop platform actions
 * (08 Root contract) with the OS dark mode where Compose exposes it. The same composition is
 * `:desktopApp:run`, the window-content UI test and smoke mode's first frame.
 *
 * [startup] carries the database open's gate state (01 Splash and start-up gate; `Ready` by
 * default, so the UI test and smoke render straight through). [onRetryStartup] is the failed
 * gate's "Try again"; [dataDir] arms the `DISK_FULL` variant's "Manage storage", which reveals
 * the data folder (08 Banners and the startup gate). [viewModelFactory] is the graph's
 * `metroViewModel()` seam: every VM-backed entry resolves through it here, exactly where the
 * window, the UI test and smoke mode compose the same tree (01 Feature entry installers).
 * [banners] is the in-app banner fallback of the bound `DesktopNotifier` (11
 * Notifications: MD2's OS notifier posts here when the OS channel cannot deliver): its
 * stream feeds the root snackbar and its taps come back through `userMessageAction`,
 * where the notification's route is applied.
 */
@Composable
internal fun NeutrodyneWindowContent(
    installers: Set<EntryProviderInstaller>,
    menuActions: DesktopMenuActions,
    viewModelFactory: MetroViewModelFactory,
    playbackController: PlaybackController,
    playbackState: PlaybackStateSource,
    prefs: AppearancePrefs = AppearancePrefs(),
    banners: DesktopBannerNotifier? = null,
    startup: StartupGateState = StartupGateState.Ready,
    onRetryStartup: () -> Unit = {},
    dataDir: Path? = null,
    portal: ch.lkmc.neutrodyne.core.common.LinuxDesktopPortal? = null,
    openHandler: DesktopOpenHandler? = null,
    failedDownloads: kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.emptyFlow(),
    updateChecker: AppUpdateChecker? = null,
    updateNotices: UpdateNotices? = null,
    settings: SettingsRepository? = null,
    rowArt: YouTubeRowArt = YouTubeRowArt.VIDEO_THUMBNAIL,
    refresh: () -> Unit = {},
    onTextFieldFocusChange: (Boolean) -> Unit = {},
    volumeUp: () -> Unit = {},
    volumeDown: () -> Unit = {},
    crashOffer: DesktopCrashOffer? = null,
    syncController: (() -> SyncController)? = null,
    reconnectDismissals: () -> ReconnectDismissals = { ReconnectDismissals() },
) {
    val panelScope = rememberCoroutineScope()
    val setPanelVisibility: (PlayerPanelVisibility) -> Unit = { visibility ->
        panelScope.launch {
            suspendRunCatching {
                settings?.set(
                    AppearanceSettingKeys.PLAYER_PANEL_HIDDEN,
                    visibility == PlayerPanelVisibility.HIDDEN,
                )
            }.onSuccess { result ->
                if (result is Outcome.Failure) Log.w("DesktopPlayer") { "Player panel preference write failed" }
            }.onFailure { Log.w("DesktopPlayer", it) { "Player panel preference write failed" } }
        }
    }
    val systemUi = SystemUiState.DEFAULT.copy(dark = isSystemInDarkTheme())
    val platformActions = rememberDesktopPlatformActions(portal)
    val nowPlaying by playbackState.nowPlaying.collectAsState()
    val panelHidden by (
        settings?.observe(AppearanceSettingKeys.PLAYER_PANEL_HIDDEN)
            ?: flowOf(false)
    ).collectAsState(initial = false)
    val updates =
        if (updateChecker != null && updateNotices != null && settings != null) {
            rememberUpdateRootPresentation(updateChecker, updateNotices, settings)
        } else {
            null
        }
    val currentUpdates by rememberUpdatedState(updates)
    // MS3's sync bridge. The thunk defers `graph.syncController` (database-backed at
    // construction) until the open gate reports Ready/Recovered — the same rule the crash
    // offer below follows.
    val syncRoot =
        syncController?.let { resolve ->
            rememberSyncRootPresentation(
                syncController = resolve,
                playbackController = { playbackController },
                reconnectDismissals = reconnectDismissals,
                enabled = startup == StartupGateState.Ready || startup is StartupGateState.Recovered,
                isPlaying = nowPlaying?.isPlaying == true,
            )
        }
    // `NeutrodyneRoot` keys its snackbar effect on `slots` identity — a per-recomposition
    // `RootSlots` would cancel and re-show the in-flight banner, so both contracts are
    // remembered (they were all-no-op until the banner stream landed).
    val actions =
        remember(
            platformActions,
            dataDir,
            onRetryStartup,
            banners,
            playbackController,
            playbackState,
            settings,
            refresh,
            onTextFieldFocusChange,
            volumeUp,
            volumeDown,
            syncRoot?.continueHere,
            syncRoot?.dismissRemoteSession,
            syncRoot?.dismissReconnect,
            syncRoot?.isReconnectTargetLive,
        ) {
            RootActions(
                retryStartup = onRetryStartup,
                dismissNotice = { currentUpdates?.dismiss?.invoke(it) },
                disableUpdateChecks = { currentUpdates?.disable?.invoke() },
                continueHere = syncRoot?.continueHere ?: {},
                dismissRemoteSession = syncRoot?.dismissRemoteSession ?: {},
                dismissReconnect = syncRoot?.dismissReconnect ?: {},
                isReconnectTargetLive = syncRoot?.isReconnectTargetLive ?: { false },
                playbackKey = { key ->
                    when (key) {
                        // 11's volume chords are the desktop's in-app gain (08 Keyboard and
                        // mouse); the shared dispatch leaves them to the shell.
                        PlaybackKey.VOLUME_UP -> {
                            volumeUp()
                            true
                        }

                        PlaybackKey.VOLUME_DOWN -> {
                            volumeDown()
                            true
                        }

                        else -> {
                            dispatchPlaybackKey(key, playbackController, playbackState.nowPlaying.value)
                        }
                    }
                },
                manageStorage =
                    dataDir?.let { dir ->
                        { platformActions.reveal?.reveal(dir.toString()) }
                    },
                // A banner's "Open" reports here — the notifier maps it back to the
                // notification's route (11 Notifications' in-app fallback).
                userMessageAction = { id -> banners?.messageAction(id) },
                hidePlayerPanel = { setPanelVisibility(PlayerPanelVisibility.HIDDEN) },
                revealPlayerPanel = { setPanelVisibility(PlayerPanelVisibility.SHOWN) },
                refresh = refresh,
                onTextFieldFocusChange = onTextFieldFocusChange,
            )
        }
    val slots =
        remember(menuActions, banners, openHandler, syncRoot?.userMessages) {
            RootSlots(
                // Keep menu and input hooks inside the root alongside the window-scoped player.
                player = {
                    currentUpdates?.feedback?.invoke()
                    menuActions.attachPush(it.navigate)
                    menuActions.attachNavigator(LocalAppNavigator.current)
                    menuActions.attachBack(it.back)
                    menuActions.attachFind(it.find)
                    openHandler?.let { DesktopInputFeedback(it) }
                    PlayerRoute(it, upNext = { PlayerUpNextRoute(it.navigate) })
                },
                // Banner posts and sync notices share the root's serial snackbar through an
                // acknowledged fan-in: plain `merge` would let a banner's `emit` return before
                // the snackbar dismisses and drop its route bookkeeping before "Open" taps.
                userMessages =
                    serialUserMessages(
                        *listOfNotNull(banners?.userMessages, syncRoot?.userMessages).toTypedArray(),
                    ),
                importResume = { available, open -> importResumeBanner(available, open) },
            )
        }
    CompositionLocalProvider(
        LocalPlatformActions provides platformActions,
        // 07 Storage rows: without a provider the local falls back to ANDROID wording.
        LocalPlatformKind provides PlatformKind.DESKTOP,
        LocalYouTubeRowArt provides rowArt,
        LocalScrollbars provides DesktopScrollbars.style,
        // 01 Feature entry installers: metroViewModel()/assistedMetroViewModel() resolve here.
        LocalMetroViewModelFactory provides viewModelFactory,
    ) {
        NeutrodyneRoot(
            failedDownloads = failedDownloads,
            state =
                RootUiState.READY.copy(
                    startup = startup,
                    hasNowPlaying = nowPlaying != null,
                    playerPanelHidden = panelHidden,
                    settingsBadge = updates?.badge ?: false,
                    notice = updates?.notice,
                    heldChanges = syncRoot?.heldChanges,
                    remoteSession = syncRoot?.remoteSession,
                    reconnect = syncRoot?.reconnect,
                ),
            actions = actions,
            slots = slots,
            installers = installers,
            systemUi = systemUi,
            prefs = prefs,
            platform = BuildInfo.Platform.DESKTOP,
        )
    }

    // 11 Crash files and the email dialog: the offer appears only once the database gate
    // has opened (the states NeutrodyneRoot renders ReadyRoot for), scans off the UI
    // thread, and never sends without the user's click. It sits outside the root's own
    // theme provider, so it gets the same scheme in its own wrapper.
    if (crashOffer != null && (startup == StartupGateState.Ready || startup is StartupGateState.Recovered)) {
        NeutrodyneTheme(prefs = prefs, system = systemUi) {
            DesktopCrashOfferDialog(
                offer = crashOffer,
                urls = crashOffer.urls ?: platformActions.urls,
                reveal = crashOffer.reveal ?: platformActions.reveal,
            )
        }
    }
}

private const val APPEARANCE_GATE_MS = 1_000L

private enum class PlayerPanelVisibility { SHOWN, HIDDEN }
