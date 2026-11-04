// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.HingePolicy
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.designsystem.PlatformSystemBarAppearance
import ch.lkmc.neutrodyne.core.designsystem.StatusBarAppearance
import ch.lkmc.neutrodyne.core.designsystem.statusBarAppearanceFor
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedBrowserKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.LocalPaneLayout
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.VerificationNoticeKey
import ch.lkmc.neutrodyne.core.ui.navigation.NavigationState
import ch.lkmc.neutrodyne.core.ui.navigation.NeutrodyneNavHost
import ch.lkmc.neutrodyne.core.ui.navigation.rememberNavigationState
import ch.lkmc.neutrodyne.core.ui.platform.testTagsAsResourceId
import ch.lkmc.neutrodyne.core.ui.resolve
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * The one root composable both shells host (01 Navigation, 08 Destinations): `MainActivity`'s
 * `setContent` and 11's `NeutrodyneWindow` call it with the shell-owned [state], [actions],
 * [slots] and the Metro-contributed [installers]. It owns the theme, the
 * radio home and retained destination stacks, the shared [NeutrodyneNavHost], the startup gate,
 * root banners/notices, the `player` slot (mini sheet vs side panel per [ndPaneLayout]) and the
 * snackbar host for [RootSlots.userMessages].
 *
 * The shells additionally provide `LocalPlatformActions` (`rememberAndroidPlatformActions()` /
 * `rememberDesktopPlatformActions(portal)`) and `LocalScrollbars` (desktop) around this call.
 *
 * Contract deltas from 01's sketch (recorded in 08 "Navigation", 2026-10-06): [prefs] and
 * [systemUi] moved in so the theme lives inside the root; [platform] gates the desktop-only key
 * chords; `RootUiState` gained `hasNowPlaying`/`playerPanelHidden` for `ndPaneLayout`; `RootActions`
 * gained `manageStorage`, `reportStartupFailure` and `disableUpdateChecks` for the gate and the
 * first-run card.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
public fun NeutrodyneRoot(
    state: RootUiState,
    actions: RootActions,
    slots: RootSlots,
    installers: Set<EntryProviderInstaller>,
    modifier: Modifier = Modifier,
    prefs: AppearancePrefs = AppearancePrefs(),
    systemUi: SystemUiState = SystemUiState.DEFAULT,
    platform: BuildInfo.Platform = BuildInfo.Platform.ANDROID,
    initialTab: TopLevelKey = FeedsKey,
    routes: Flow<Route> = emptyFlow(),
) {
    val rootViewModelOwner = LocalViewModelStoreOwner.current
    val backOwner = LocalNavigationEventDispatcherOwner.current ?: rememberNavigationEventDispatcherOwner()
    val navigation = rememberNavigationState(initialTab)
    val snackbarHost = remember { SnackbarHostState() }
    val rootScope = rememberCoroutineScope()
    var expandRequest by rememberSaveable { mutableIntStateOf(0) }
    var collapseRequest by rememberSaveable { mutableIntStateOf(0) }

    val dark =
        when (prefs.theme) {
            ThemeMode.SYSTEM -> systemUi.dark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    NeutrodyneTheme(prefs = prefs, system = systemUi) {
        CompositionLocalProvider(
            LocalAppNavigator provides navigation,
            LocalSnackbarHost provides snackbarHost,
            LocalSettingsBadge provides state.settingsBadge,
            LocalRootScope provides rootScope,
            LocalRootViewModelStoreOwner provides rootViewModelOwner,
            LocalNavigationEventDispatcherOwner provides backOwner,
        ) {
            when (val gate = state.startup) {
                StartupGateState.Pending, is StartupGateState.Failed -> {
                    PlatformSystemBarAppearance(statusBarAppearanceFor(dark))
                    StartupGate(gate, actions, modifier)
                }

                else -> {
                    // Keep shell intents queued until screens may resolve database-backed services.
                    LaunchedEffect(routes) {
                        routes.collect { route ->
                            when (route) {
                                is Route.Navigate -> {
                                    collapseRequest++
                                    navigation.open(route.tab, route.stack)
                                }

                                is Route.Push -> {
                                    if (route.key != SpeedKey) collapseRequest++
                                    navigation.push(route.key)
                                }

                                is Route.SelectFeed -> {
                                    if (actions.selectFeedSource(route.groupUuid)) {
                                        collapseRequest++
                                        navigation.open(
                                            FeedsKey,
                                            if (route.groupUuid ==
                                                null
                                            ) {
                                                listOf(FeedBrowserKey)
                                            } else {
                                                emptyList()
                                            },
                                        )
                                    } else {
                                        launch { snackbarHost.showSnackbar(getString(Res.string.write_failed)) }
                                    }
                                }

                                Route.ExpandPlayer -> {
                                    expandRequest++
                                }

                                Route.None -> {
                                    Unit
                                }
                            }
                        }
                    }
                    ReadyRoot(
                        state,
                        actions,
                        slots,
                        installers,
                        navigation,
                        snackbarHost,
                        platform,
                        statusBarAppearanceFor(dark),
                        modifier,
                        expandRequest,
                        collapseRequest,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ReadyRoot(
    state: RootUiState,
    actions: RootActions,
    slots: RootSlots,
    installers: Set<EntryProviderInstaller>,
    navigation: NavigationState,
    snackbarHost: SnackbarHostState,
    platform: BuildInfo.Platform,
    appBarAppearance: StatusBarAppearance,
    modifier: Modifier = Modifier,
    expandRequest: Int = 0,
    collapseRequest: Int = 0,
) {
    var expandedSettled by remember { mutableStateOf(false) }
    var playerNavigationRequests by rememberSaveable { mutableIntStateOf(0) }
    var lastPlayerTab by remember { mutableStateOf(navigation.selectedTab) }
    LaunchedEffect(navigation.selectedTab) {
        if (lastPlayerTab != navigation.selectedTab) playerNavigationRequests++
        lastPlayerTab = navigation.selectedTab
    }
    val navigateFromPlayer: (NavKey) -> Unit = { key ->
        // Same-tab requests must collapse too; this token signals change, not event count.
        if (key != SpeedKey) playerNavigationRequests++
        if (key is TopLevelKey) navigation.selectTab(key) else navigation.push(key)
    }
    var contentBottomPx by remember { mutableFloatStateOf(0f) }
    var measuredContentBottomPx by remember { mutableFloatStateOf(0f) }
    var viewportBottomPx by remember { mutableFloatStateOf(0f) }
    val radioHome = navigation.selectedTab == FeedsKey && navigation.stack(FeedsKey).lastOrNull() == FeedsKey
    PlatformSystemBarAppearance(
        if (radioHome && !expandedSettled) StatusBarAppearance.LightIcons else appBarAppearance,
    )
    val hideSuite = state.hasNowPlaying && expandedSettled && contentBottomPx > 0f
    val dockBottomToCommit =
        if (!expandedSettled ||
            contentBottomPx == 0f
        ) {
            measuredContentBottomPx.takeIf { it > 0f }
        } else {
            null
        }
    SideEffect { dockBottomToCommit?.let { contentBottomPx = it } }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(hideSuite, focusManager) {
        if (hideSuite) focusManager.clearFocus(force = true)
    }
    LaunchedEffect(state.hasNowPlaying) {
        if (!state.hasNowPlaying) expandedSettled = false
    }
    val windowInfo = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val density = LocalDensity.current
    val dockBottomInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val windowWidthDp =
        with(density) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
                .value
                .toInt()
        }
    val navChromeDp = 0
    val layout =
        ndPaneLayout(
            windowWidthDp = windowWidthDp,
            navChromeDp = navChromeDp,
            // M4 uses the sheet at every width; MD1/M10 activate the side panel.
            hasNowPlaying = false,
            panelHidden = state.playerPanelHidden,
        )
    // The pane directive keeps the library's spacing/hinge values; only the partition count is ours.
    val directive =
        remember(windowInfo, layout.partitions) {
            calculatePaneScaffoldDirective(windowInfo, verticalHingePolicy = HingePolicy.AvoidSeparating)
                .copy(maxHorizontalPartitions = layout.partitions)
        }
    SideEffect { navigation.panePartitions = layout.partitions }

    // One startup-recovery snackbar per Recovered instance.
    val recovered = state.startup as? StartupGateState.Recovered
    LaunchedEffect(recovered) {
        if (recovered != null) snackbarHost.showSnackbar(recovered.cause.resolve())
    }

    // The M11a verification notice is a dialog key pushed once, never a route (08 Update notices).
    LaunchedEffect(state.notice) {
        if (state.notice == RootNotice.VERIFICATION_ENFORCEMENT &&
            navigation.tabs.none { VerificationNoticeKey in navigation.stack(it) }
        ) {
            navigation.push(VerificationNoticeKey)
        }
    }

    // Snackbar stream: serial (showSnackbar suspends until dismissed); action taps report back
    // by message id (08's Undo catalogue).
    LaunchedEffect(slots) {
        slots.userMessages.collect { message ->
            val result = snackbarHost.showUserMessage(message)
            if (result == SnackbarResult.ActionPerformed) actions.userMessageAction(message.id)
        }
    }

    // Escape-as-back on the desktop (08 Back handling order): nothing feeds the NavigationEvent
    // dispatcher there, so the root turns Escape into a completed back event through a
    // DesktopBackInput — when a handler (NavDisplay, or a future expanded PlayerSheet ahead of
    // it) is enabled on the host dispatcher, it claims the event; otherwise the root pops the
    // selected tab's stack itself.
    val eventDispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val backInput = remember { DesktopBackInput() }
    DisposableEffect(eventDispatcher) {
        if (eventDispatcher != null) eventDispatcher.addInput(backInput)
        onDispose { if (eventDispatcher != null) eventDispatcher.removeInput(backInput) }
    }

    val importContentFocus = remember { FocusRequester() }
    val rootKeyFocus = remember { FocusRequester() }
    var rootHasFocus by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(rootHasFocus, windowFocused) {
        if (platform == BuildInfo.Platform.DESKTOP && windowFocused && !rootHasFocus) {
            // A removed snackbar or menu action can leave no focused key-event target.
            // Wait for explicit launcher/field restoration before taking the fallback.
            withFrameNanos { }
            if (!rootHasFocus) rootKeyFocus.requestFocus()
        }
    }
    LaunchedEffect(navigation.selectedTab, navigation.stack(navigation.selectedTab).lastOrNull(), hideSuite) {
        rootKeyFocus.requestFocus()
    }
    val discoverSearchFocus = remember { DiscoverSearchFocus() }
    val miniInset = if (!radioHome && !layout.playerPanel && state.hasNowPlaying) MINI_PLAYER_HEIGHT else 0.dp
    CompositionLocalProvider(
        LocalDiscoverSearchFocus provides discoverSearchFocus,
        LocalPaneLayout provides layout,
        LocalMiniPlayerInset provides miniInset,
    ) {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .testTagsAsResourceId()
                    .focusRequester(rootKeyFocus)
                    .onFocusChanged { rootHasFocus = it.hasFocus }
                    .onGloballyPositioned { viewportBottomPx = it.boundsInRoot().bottom }
                    .onKeyEvent { event ->
                        if (platform == BuildInfo.Platform.DESKTOP && navigation.selectedTab == DiscoverKey &&
                            event.type == KeyEventType.KeyDown && event.key == Key.F &&
                            (event.isCtrlPressed || event.isMetaPressed) &&
                            !event.isAltPressed && !event.isShiftPressed &&
                            discoverSearchFocus.request()
                        ) {
                            true
                        } else {
                            dispatchRootKey(
                                event = event,
                                platform = platform,
                                navigation = navigation,
                                desktopBack = {
                                    if (eventDispatcher != null && backInput.hasEnabledHandlers) {
                                        backInput.triggerBack()
                                    } else {
                                        navigation.pop()
                                    }
                                },
                                dispatch = actions.playbackKey,
                            )
                        }
                    }.focusable(),
        ) {
            Box(
                Modifier.fillMaxSize().focusGroup().then(if (hideSuite) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        RootBanners(
                            state = state,
                            actions = actions,
                            navigator = navigation,
                            onFeedsOrLibrary =
                                navigation.selectedTab == FeedsKey ||
                                    navigation.selectedTab == LibraryKey,
                            importResume = { available ->
                                val top = navigation.stack(navigation.selectedTab).lastOrNull()
                                slots.importResume(
                                    available && (top == FeedsKey || top == FeedBrowserKey || top == LibraryKey),
                                ) { id ->
                                    // The banner's focused button disappears on push; retain a key-event target.
                                    importContentFocus.requestFocus()
                                    navigation.push(ImportKey(id.toString()))
                                }
                            },
                        )
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            Box(
                                Modifier
                                    .weight(
                                        1f,
                                    ).focusRequester(importContentFocus)
                                    .focusable()
                                    .onGloballyPositioned {
                                        measuredContentBottomPx = it.boundsInRoot().bottom - dockBottomInsetPx
                                    },
                            ) {
                                NeutrodyneNavHost(
                                    state = navigation,
                                    installers = installers,
                                    directive = directive,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            if (!radioHome && layout.playerPanel) {
                                slots.player(PlayerSlotState(layout, navigateFromPlayer))
                            }
                        }
                    }
                }
            }

            if (!layout.playerPanel) {
                Box(Modifier.fillMaxSize()) {
                    slots.player(
                        PlayerSlotState(
                            layout = layout,
                            presentation =
                                if (radioHome) PlayerPresentation.RADIO_HOME else PlayerPresentation.STANDARD,
                            navigate = navigateFromPlayer,
                            contentBottomPx = contentBottomPx,
                            expandRequest = expandRequest,
                            collapseRequest = collapseRequest + playerNavigationRequests,
                            onExpandedSettled = { expandedSettled = it },
                        ),
                    )
                }
            }

            state.remoteSession?.let { card ->
                ContinueOnThisDeviceCard(
                    remoteSession = card,
                    actions = actions,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = miniInset),
                )
            }

            // One host stays above the player; collapsed messages retain the content dock.
            val snackbarBottomPx =
                if (hideSuite) {
                    WindowInsets.navigationBars.getBottom(density).toFloat()
                } else {
                    (viewportBottomPx - contentBottomPx).coerceAtLeast(0f)
                }
            SnackbarHost(
                hostState = snackbarHost,
                modifier =
                    Modifier.align(Alignment.BottomCenter).padding(
                        start = if (hideSuite) 0.dp else navChromeDp.dp,
                        bottom =
                            with(density) { snackbarBottomPx.toDp() } +
                                (if (hideSuite) 0.dp else miniInset) + SNACKBAR_MARGIN,
                    ),
            )
        }
    }
}

/**
 * The root key chords (08 Keyboard and mouse): the bubbling phase — after the focused element —
 * maps hardware keys to [PlaybackKey]; the shell's dispatch decides whether the action ran (and so
 * whether the event is consumed). Escape is the exception: on the desktop it completes a back
 * event through [desktopBack] instead of becoming a playback chord (S9: the desktop NavDisplay
 * ships no Escape input of its own). Plain arrows skip only on the desktop, where they do not
 * traverse focus.
 */
private fun dispatchRootKey(
    event: KeyEvent,
    platform: BuildInfo.Platform,
    navigation: NavigationState,
    desktopBack: () -> Boolean,
    dispatch: (PlaybackKey) -> Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown || event.isAltPressed) return false
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    val shift = event.isShiftPressed
    val desktop = platform == BuildInfo.Platform.DESKTOP

    if (event.key == Key.Escape && desktop && !ctrl && !shift) return desktopBack()

    val key: PlaybackKey =
        when {
            event.key == Key.Spacebar && !ctrl && !shift -> {
                PlaybackKey.TOGGLE
            }

            event.key == Key.DirectionLeft && ctrl && shift && desktop -> {
                PlaybackKey.PREVIOUS_EPISODE
            }

            event.key == Key.DirectionRight && ctrl && shift && desktop -> {
                PlaybackKey.NEXT_EPISODE
            }

            event.key == Key.DirectionLeft && shift && desktop -> {
                PlaybackKey.PREVIOUS_CHAPTER
            }

            event.key == Key.DirectionRight && shift && desktop -> {
                PlaybackKey.NEXT_CHAPTER
            }

            event.key == Key.DirectionLeft && ctrl -> {
                PlaybackKey.SKIP_BACK
            }

            event.key == Key.DirectionRight && ctrl -> {
                PlaybackKey.SKIP_FORWARD
            }

            event.key == Key.DirectionUp && ctrl && desktop -> {
                PlaybackKey.VOLUME_UP
            }

            event.key == Key.DirectionDown && ctrl && desktop -> {
                PlaybackKey.VOLUME_DOWN
            }

            event.key == Key.DirectionLeft && desktop -> {
                PlaybackKey.SKIP_BACK
            }

            event.key == Key.DirectionRight && desktop -> {
                PlaybackKey.SKIP_FORWARD
            }

            event.key == Key.Tab && ctrl && shift && navigation.selectedTab == FeedsKey -> {
                PlaybackKey.PREVIOUS_FEED_PAGE
            }

            event.key == Key.Tab && ctrl && navigation.selectedTab == FeedsKey -> {
                PlaybackKey.NEXT_FEED_PAGE
            }

            else -> {
                return false
            }
        }
    return dispatch(key)
}

/**
 * A [NavigationEventInput] that reports whether the host dispatcher currently has an enabled
 * back handler (see [hasEnabledHandlers]) and injects a completed back event on demand. The
 * root needs the flag because a dispatcher with no enabled handler swallows the event — the
 * selected tab's stack is then popped directly instead.
 */
private class DesktopBackInput : NavigationEventInput() {
    var hasEnabledHandlers = false
        private set

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        this.hasEnabledHandlers = hasEnabledHandlers
    }

    fun triggerBack(): Boolean {
        dispatchOnBackStarted(NavigationEvent())
        dispatchOnBackCompleted()
        return true
    }
}

/** `LocalMiniPlayerInset` while the docked mini player shows (08: 80 dp reserved). */
private val MINI_PLAYER_HEIGHT = 80.dp
private val SNACKBAR_MARGIN = 16.dp
