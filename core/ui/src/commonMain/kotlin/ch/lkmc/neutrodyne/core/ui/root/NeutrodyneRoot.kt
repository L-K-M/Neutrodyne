// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.HingePolicy
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.PlatformSystemBarAppearance
import ch.lkmc.neutrodyne.core.designsystem.components.NdNavItem
import ch.lkmc.neutrodyne.core.designsystem.components.NdNavigationSuiteScaffold
import ch.lkmc.neutrodyne.core.designsystem.components.defaultSuiteType
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.statusBarAppearanceFor
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.designsystem.theme.ThemeMode
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.LocalPaneLayout
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.navigation.VerificationNoticeKey
import ch.lkmc.neutrodyne.core.ui.navigation.NavigationState
import ch.lkmc.neutrodyne.core.ui.navigation.NeutrodyneNavHost
import ch.lkmc.neutrodyne.core.ui.navigation.rememberNavigationState
import ch.lkmc.neutrodyne.core.ui.platform.testTagsAsResourceId
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.nav_up_next
import ch.lkmc.neutrodyne.core.ui.resolve
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
) {
    val navigation = rememberNavigationState(initialTab)
    val snackbarHost = remember { SnackbarHostState() }

    val dark = when (prefs.theme) {
        ThemeMode.SYSTEM -> systemUi.dark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    PlatformSystemBarAppearance(statusBarAppearanceFor(dark))

    NeutrodyneTheme(prefs = prefs, system = systemUi) {
        CompositionLocalProvider(
            LocalAppNavigator provides navigation,
            LocalSnackbarHost provides snackbarHost,
            LocalSettingsBadge provides state.settingsBadge,
        ) {
            when (val gate = state.startup) {
                StartupGateState.Pending, is StartupGateState.Failed ->
                    StartupGate(gate, actions, modifier)
                else -> ReadyRoot(state, actions, slots, installers, navigation, snackbarHost, platform, modifier)
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
    modifier: Modifier,
) {
    val suiteType = defaultSuiteType()
    val windowInfo = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val density = LocalDensity.current
    val windowWidthDp = with(density) {
        LocalWindowInfo.current.containerSize.width.toDp().value.toInt()
    }
    val navChromeDp = if (isRailSuite(suiteType)) RAIL_WIDTH_DP else 0
    val layout = ndPaneLayout(
        windowWidthDp = windowWidthDp,
        navChromeDp = navChromeDp,
        hasNowPlaying = state.hasNowPlaying,
        panelHidden = state.playerPanelHidden,
    )
    // The pane directive keeps the library's spacing/hinge values; only the partition count is ours.
    val directive = remember(windowInfo, layout.partitions) {
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

    // Snackbar stream: serial (showSnackbar suspends until dismissed).
    LaunchedEffect(slots) {
        slots.userMessages.collect { message ->
            snackbarHost.showSnackbar(
                message = message.text.resolve(),
                actionLabel = message.action?.resolve(),
                withDismissAction = message.action != null,
            )
        }
    }

    val miniInset = if (!layout.playerPanel && state.hasNowPlaying) MINI_PLAYER_HEIGHT else 0.dp
    CompositionLocalProvider(
        LocalPaneLayout provides layout,
        LocalMiniPlayerInset provides miniInset,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTagsAsResourceId()
                .onKeyEvent { event ->
                    dispatchRootKey(event, platform, navigation, actions.playbackKey)
                },
        ) {
            NdNavigationSuiteScaffold(
                items = navItems(),
                selected = navigation.tabs.indexOf(navigation.selectedTab),
                onSelect = { index -> navigation.selectTab(navigation.tabs[index]) },
                suiteType = suiteType,
                footer = { SettingsGearButton() },
            ) {
                Column(Modifier.fillMaxSize()) {
                    RootBanners(
                        state = state,
                        actions = actions,
                        navigator = navigation,
                        onFeedsOrLibrary = navigation.selectedTab == FeedsKey ||
                            navigation.selectedTab == LibraryKey,
                    )
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Box(Modifier.weight(1f)) {
                            NeutrodyneNavHost(
                                state = navigation,
                                installers = installers,
                                directive = directive,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (layout.playerPanel) {
                            slots.player(PlayerSlotState(layout, navigation::push))
                        }
                    }
                }
            }

            if (!layout.playerPanel) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    slots.player(PlayerSlotState(layout, navigation::push))
                }
            }

            state.remoteSession?.let { card ->
                ContinueOnThisDeviceCard(
                    remoteSession = card,
                    actions = actions,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = miniInset),
                )
            }

            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = miniInset + SNACKBAR_MARGIN),
            )
        }
    }
}

/** Rail suite types take 96 dp off the window's content width (08 Adaptive layouts). */
private fun isRailSuite(suiteType: NavigationSuiteType): Boolean = when (suiteType) {
    NavigationSuiteType.WideNavigationRailCollapsed,
    NavigationSuiteType.WideNavigationRailExpanded,
    NavigationSuiteType.NavigationRail,
    -> true
    else -> false
}

@Composable
private fun navItems(): List<NdNavItem> = listOf(
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
 * whether the event is consumed). Escape is absent on purpose: `NavDisplay`'s shared back chain
 * owns it (S9). Plain arrows skip only on the desktop, where they do not traverse focus.
 */
private fun dispatchRootKey(
    event: KeyEvent,
    platform: BuildInfo.Platform,
    navigation: NavigationState,
    dispatch: (PlaybackKey) -> Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown || event.isAltPressed) return false
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    val shift = event.isShiftPressed
    val desktop = platform == BuildInfo.Platform.DESKTOP

    val key: PlaybackKey = when {
        event.key == Key.Spacebar && !ctrl && !shift -> PlaybackKey.TOGGLE

        event.key == Key.DirectionLeft && ctrl && shift && desktop -> PlaybackKey.PREVIOUS_EPISODE
        event.key == Key.DirectionRight && ctrl && shift && desktop -> PlaybackKey.NEXT_EPISODE
        event.key == Key.DirectionLeft && shift && desktop -> PlaybackKey.PREVIOUS_CHAPTER
        event.key == Key.DirectionRight && shift && desktop -> PlaybackKey.NEXT_CHAPTER
        event.key == Key.DirectionLeft && ctrl -> PlaybackKey.SKIP_BACK
        event.key == Key.DirectionRight && ctrl -> PlaybackKey.SKIP_FORWARD
        event.key == Key.DirectionUp && ctrl && desktop -> PlaybackKey.VOLUME_UP
        event.key == Key.DirectionDown && ctrl && desktop -> PlaybackKey.VOLUME_DOWN
        event.key == Key.DirectionLeft && desktop -> PlaybackKey.SKIP_BACK
        event.key == Key.DirectionRight && desktop -> PlaybackKey.SKIP_FORWARD

        event.key == Key.Tab && ctrl && shift && navigation.selectedTab == FeedsKey ->
            PlaybackKey.PREVIOUS_FEED_PAGE
        event.key == Key.Tab && ctrl && navigation.selectedTab == FeedsKey ->
            PlaybackKey.NEXT_FEED_PAGE

        else -> return false
    }
    return dispatch(key)
}

private val RAIL_WIDTH_DP = 96

/** `LocalMiniPlayerInset` while the docked mini player shows (08: 80 dp reserved). */
private val MINI_PLAYER_HEIGHT = 80.dp
private val SNACKBAR_MARGIN = 16.dp
