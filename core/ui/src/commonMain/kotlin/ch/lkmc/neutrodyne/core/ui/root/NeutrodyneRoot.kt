// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

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
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.designsystem.PlatformSystemBarAppearance
import ch.lkmc.neutrodyne.core.designsystem.components.NdNavItem
import ch.lkmc.neutrodyne.core.designsystem.components.NdNavigationSuiteScaffold
import ch.lkmc.neutrodyne.core.designsystem.components.defaultSuiteType
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.statusBarAppearanceFor
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.LocalPaneLayout
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.navigation.VerificationNoticeKey
import ch.lkmc.neutrodyne.core.ui.navigation.NavigationState
import ch.lkmc.neutrodyne.core.ui.navigation.NeutrodyneNavHost
import ch.lkmc.neutrodyne.core.ui.navigation.rememberNavigationState
import ch.lkmc.neutrodyne.core.ui.platform.testTagsAsResourceId
import ch.lkmc.neutrodyne.core.ui.resolve
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.downloads_failed_badge
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.nav_up_next
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The one root composable both shells host (01 Navigation, 08 Destinations): `MainActivity`'s
 * `setContent` and 11's `NeutrodyneWindow` call it with the shell-owned [state], [actions],
 * [slots] and the Metro-contributed [installers]. It owns the theme, the navigation suite with its
 * five destinations and the rail's Settings gear, the shared [NeutrodyneNavHost], the startup gate,
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
    failedDownloads: Flow<Int> = kotlinx.coroutines.flow.emptyFlow(),
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
    PlatformSystemBarAppearance(statusBarAppearanceFor(dark))

    NeutrodyneTheme(prefs = prefs, system = systemUi) {
        CompositionLocalProvider(
            LocalNavigationEventDispatcherOwner provides backOwner,
            LocalAppNavigator provides navigation,
            LocalRootViewModelStoreOwner provides rootViewModelOwner,
            LocalSnackbarHost provides snackbarHost,
            LocalSettingsBadge provides state.settingsBadge,
            LocalRootScope provides rootScope,
        ) {
            when (val gate = state.startup) {
                StartupGateState.Pending, is StartupGateState.Failed -> {
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
                                    collapseRequest++
                                    navigation.push(route.key)
                                }

                                is Route.SelectFeed -> {
                                    if (actions.selectFeedSource(route.groupUuid)) {
                                        collapseRequest++
                                        navigation.open(FeedsKey, emptyList())
                                    } else {
                                        launch { snackbarHost.showSnackbar(getString(Res.string.write_failed)) }
                                    }
                                }

                                Route.ExpandPlayer -> {
                                    expandRequest++
                                }

                                else -> {
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
                        modifier,
                        expandRequest,
                        collapseRequest,
                        failedDownloads,
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
    modifier: Modifier = Modifier,
    expandRequest: Int = 0,
    collapseRequest: Int = 0,
    failedDownloads: Flow<Int> = emptyFlow(),
) {
    val failedDownloadCount by failedDownloads.collectAsStateWithLifecycle(initialValue = 0)
    var expandedSettled by remember { mutableStateOf(false) }
    var playerNavigationRequests by rememberSaveable { mutableIntStateOf(0) }
    var lastPlayerTab by remember { mutableStateOf(navigation.selectedTab) }
    LaunchedEffect(navigation.selectedTab) {
        if (lastPlayerTab != navigation.selectedTab) playerNavigationRequests++
        lastPlayerTab = navigation.selectedTab
    }
    val navigateFromPlayer: (NavKey) -> Unit = { key ->
        if (key != SpeedKey) playerNavigationRequests++
        if (key is TopLevelKey) navigation.selectTab(key) else navigation.push(key)
    }
    var contentBottomPx by remember { mutableFloatStateOf(0f) }
    val suiteState = rememberNavigationSuiteScaffoldState()
    val hideSuite = expandedSettled && contentBottomPx > 0f
    LaunchedEffect(hideSuite) {
        // The sheet already covers the chrome. Snapping avoids a second resize animation.
        suiteState.snapTo(
            if (hideSuite) NavigationSuiteScaffoldValue.Hidden else NavigationSuiteScaffoldValue.Visible,
        )
    }
    val suiteType = defaultSuiteType()
    val windowInfo = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val density = LocalDensity.current
    val dockBottomInsetPx = if (isRailSuite(suiteType)) WindowInsets.navigationBars.getBottom(density).toFloat() else 0f
    val windowWidthDp =
        with(density) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
                .value
                .toInt()
        }
    val navChromeDp = if (isRailSuite(suiteType)) RAIL_WIDTH_DP else 0
    val layout =
        ndPaneLayout(
            windowWidthDp = windowWidthDp,
            navChromeDp = navChromeDp,
            // M4 uses the sheet at every width. Keep the panel geometry for MD1.
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
        if (state.notice == RootNotice.VERIFICATION_ENFORCEMENT) {
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
    val discoverSearchFocus = remember { DiscoverSearchFocus() }
    val miniInset = if (!layout.playerPanel && state.hasNowPlaying) MINI_PLAYER_HEIGHT else 0.dp
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
                    },
        ) {
            NdNavigationSuiteScaffold(
                items = navItems(failedDownloadCount),
                selected = navigation.tabs.indexOf(navigation.selectedTab),
                onSelect = { index -> navigation.selectTab(navigation.tabs[index]) },
                suiteType = suiteType,
                footer = { SettingsGearButton() },
                state = suiteState,
            ) {
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
                            slots.importResume(available && (top == FeedsKey || top == LibraryKey)) { id ->
                                // The banner's focused button disappears on push; retain a key-event target.
                                importContentFocus.requestFocus()
                                navigation.push(ImportKey(id.toString()))
                            }
                        },
                    )
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Box(
                            Modifier.weight(1f).focusRequester(importContentFocus).focusable().onGloballyPositioned {
                                // A restored full player still needs the visible suite's first measurement.
                                if ((!expandedSettled || contentBottomPx == 0f) && !suiteState.isAnimating &&
                                    suiteState.currentValue == NavigationSuiteScaffoldValue.Visible
                                ) {
                                    contentBottomPx = it.boundsInRoot().bottom - dockBottomInsetPx
                                }
                            },
                        ) {
                            NeutrodyneNavHost(
                                state = navigation,
                                installers = installers,
                                directive = directive,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (layout.playerPanel) {
                            slots.player(PlayerSlotState(layout, navigateFromPlayer))
                        }
                    }
                }
            }

            if (!layout.playerPanel) {
                Box(Modifier.fillMaxSize()) {
                    slots.player(
                        PlayerSlotState(
                            layout,
                            navigateFromPlayer,
                            contentBottomPx,
                            expandRequest,
                            collapseRequest + playerNavigationRequests,
                        ) {
                            expandedSettled = it
                        },
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

            SnackbarHost(
                hostState = snackbarHost,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = miniInset + SNACKBAR_MARGIN),
            )
        }
    }
}

/** Rail suite types take 96 dp off the window's content width (08 Adaptive layouts). */
private fun isRailSuite(suiteType: NavigationSuiteType): Boolean =
    when (suiteType) {
        NavigationSuiteType.WideNavigationRailCollapsed,
        NavigationSuiteType.WideNavigationRailExpanded,
        NavigationSuiteType.NavigationRail,
        -> true

        else -> false
    }

@Composable
private fun navItems(failedDownloads: Int): List<NdNavItem> =
    listOf(
        NdNavItem(
            label = stringResource(Res.string.nav_feeds),
            icon = NdIcons.DynamicFeed,
            iconSelected = NdIcons.DynamicFeedFilled,
            testTag = "nav_feeds",
        ),
        NdNavItem(
            label = stringResource(Res.string.nav_library),
            icon = NdIcons.GridView,
            iconSelected = NdIcons.GridViewFilled,
            testTag = "nav_library",
        ),
        NdNavItem(
            label = stringResource(Res.string.nav_up_next),
            icon = NdIcons.QueueMusic,
            iconSelected = NdIcons.QueueMusicFilled,
            testTag = "nav_up_next",
        ),
        NdNavItem(
            label = stringResource(Res.string.nav_downloads),
            icon = NdIcons.Download,
            iconSelected = NdIcons.DownloadFilled,
            testTag = "nav_downloads",
            badgeCount = failedDownloads,
            badgeDescription =
                pluralStringResource(
                    Res.plurals.downloads_failed_badge,
                    failedDownloads,
                    failedDownloads,
                ),
        ),
        NdNavItem(
            label = stringResource(Res.string.nav_discover),
            icon = NdIcons.Explore,
            iconSelected = NdIcons.ExploreFilled,
            testTag = "nav_discover",
        ),
    )

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

private val RAIL_WIDTH_DP = 96

/** `LocalMiniPlayerInset` while the docked mini player shows (08: 80 dp reserved). */
private val MINI_PLAYER_HEIGHT = 80.dp
private val SNACKBAR_MARGIN = 16.dp
