// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.model.HingeOrientation
import ch.lkmc.neutrodyne.core.model.HingeState
import ch.lkmc.neutrodyne.core.model.WindowHinge
import ch.lkmc.neutrodyne.core.model.WindowPosture
import ch.lkmc.neutrodyne.core.model.WindowRectDp
import ch.lkmc.neutrodyne.core.navigation.PaneLayout
import kotlinx.collections.immutable.persistentListOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AdaptivePlayerTest {
    private var previousLocale = Locale.ENGLISH

    @BeforeTest fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun panelHasRealCloseAndSharedTransportButNoBackOrDrag() =
        runDesktopComposeUiTest(width = 412, height = 800) {
            val fixture = SheetFixture().apply { layout = PaneLayout(1, true, 384) }
            val dispatcher = NavigationEventDispatcher()
            val input = DirectNavigationEventInput()
            dispatcher.addInput(input)
            val owner =
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = dispatcher
                }
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) { fixture.Content() }
            }
            onNodeWithTag("playerSidePanel").assertIsDisplayed()
            onNodeWithTag("playerSheet").assertDoesNotExist()
            onNodeWithContentDescription("Play").performScrollTo().performClick()
            onNodeWithContentDescription("Back 10 seconds").performScrollTo().performClick()
            onNodeWithTag("playerArtwork").performTouchInput { swipe(center, center + Offset(0f, -120f), 400) }
            runOnIdle {
                input.backStarted(NavigationEvent())
                input.backCompleted()
                assertFalse(fixture.expanded)
                assertEquals(0f, fixture.state.progress)
            }
            onNodeWithTag("playerSidePanel").assertIsDisplayed()
            onNodeWithContentDescription("Close player panel").performScrollTo().performClick()
            runOnIdle {
                assertEquals(listOf("toggle", "back", "closePanel"), fixture.calls)
                dispatcher.dispose()
            }
        }

    @Test
    fun panelToSheetStartsCollapsedAndNewPlaybackDoesNotResetHiddenChoice() =
        runDesktopComposeUiTest(width = 900, height = 800) {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            var panelWidth by mutableStateOf(412.dp)
            fixture.layout = PaneLayout(1, true, 692)
            setContent { Box(Modifier.size(panelWidth, 800.dp)) { fixture.Content() } }
            onNodeWithTag("playerSidePanel").assertIsDisplayed()
            runOnIdle {
                fixture.layout = PaneLayout(1, false, 743)
                panelWidth = 800.dp
            }
            onNodeWithTag("miniPlayer").assertIsDisplayed()
            onNodeWithTag("fullPlayer").assertDoesNotExist()
            onNodeWithTag("miniPlayer").performClick()
            onNodeWithTag("fullPlayer").assertIsDisplayed()
            runOnIdle {
                fixture.reopenPanelFromMini = true
                fixture.layout = PaneLayout(2, true, 692)
            }
            onNodeWithTag("playerSidePanel").assertIsDisplayed()
            runOnIdle { fixture.layout = PaneLayout(2, false, 1104) }
            onNodeWithTag("miniPlayer").performClick()
            runOnIdle { fixture.now = sampleNow(playing = true).copy(episodeId = 2) }
            onNodeWithTag("fullPlayer").assertDoesNotExist()
            runOnIdle { assertEquals(listOf("reopenPanel"), fixture.calls) }
        }

    @Test
    fun hiddenWideMiniUpwardDragReopensInsteadOfExpandingSheet() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val fixture =
                SheetFixture().apply {
                    reopenPanelFromMini = true
                    contentBottomPx = 600f
                }
            setContent { fixture.Content() }
            onNodeWithTag("miniPlayer").performTouchInput { swipe(center, center + Offset(0f, -500f), 400) }
            onNodeWithTag("miniPlayer").assertIsDisplayed()
            onNodeWithTag("fullPlayer").assertDoesNotExist()
            runOnIdle { assertEquals(listOf("reopenPanel"), fixture.calls) }
        }

    @Test
    fun compactLandscapeAtDoubleFontKeepsEveryControlReachable() =
        runDesktopComposeUiTest(width = 700, height = 320) {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { fixture.Content() } }
            val art = onNodeWithTag("playerArtRegion").fetchSemanticsNode().boundsInRoot
            val controls = onNodeWithTag("playerControlsRegion").fetchSemanticsNode().boundsInRoot
            assertTrue(art.right <= controls.left)
            assertTrue(controls.right <= 700f && controls.bottom <= 320f)
            onNodeWithContentDescription("Play").performScrollTo().assertIsDisplayed().performClick()
            onNodeWithContentDescription("Forward 30 seconds").performScrollTo().assertIsDisplayed().performClick()
            onNodeWithText("Sleep timer").performScrollTo().assertIsDisplayed().performClick()
            onAllNodesWithText("Up next")
                .onLast()
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            onNodeWithText("Notes").performScrollTo().assertIsDisplayed().performClick()
            onNodeWithText("No show notes").performScrollTo().assertIsDisplayed()
            runOnIdle { assertEquals(listOf("toggle", "forward"), fixture.calls) }
        }

    @Test
    fun tabletopKeepsArtAndTransportClearOfWindowHingeAfterHostOffset() =
        runDesktopComposeUiTest(width = 1000, height = 900) {
            val fixture =
                SheetFixture(PlayerSheetValue.Expanded).apply {
                    posture =
                        WindowPosture(
                            persistentListOf(
                                WindowHinge(
                                    WindowRectDp(0f, 400f, 1000f, 424f),
                                    HingeOrientation.HORIZONTAL,
                                    HingeState.HALF_OPENED,
                                    true,
                                ),
                            ),
                        )
                }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Box(Modifier.fillMaxSize().padding(start = 96.dp, top = 48.dp, bottom = 24.dp)) {
                        fixture.Content()
                    }
                }
            }
            val art = onNodeWithTag("playerArtRegion").fetchSemanticsNode().boundsInRoot
            val controls = onNodeWithTag("playerControlsRegion").fetchSemanticsNode().boundsInRoot
            assertTrue(art.left >= 96f && art.top >= 48f)
            assertTrue(art.bottom <= 400f)
            assertTrue(controls.top >= 424f)
            assertTrue(controls.bottom <= 900f)
            onNodeWithContentDescription("Play").performScrollTo().assertIsDisplayed().performClick()
            onNodeWithText("Sleep timer").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle { assertEquals(listOf("toggle"), fixture.calls) }
        }

    @Test
    fun queueScrollSurvivesRotationAndPanelToggle() =
        runDesktopComposeUiTest(width = 1000, height = 900) {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            var landscape by mutableStateOf(true)
            var queueInstances = 0
            val queue: @androidx.compose.runtime.Composable () -> Unit = {
                val queueState =
                    androidx.compose.foundation.lazy
                        .rememberLazyListState()
                androidx.compose.runtime.DisposableEffect(Unit) {
                    queueInstances++
                    onDispose { queueInstances-- }
                }
                LazyColumn(Modifier.height(240.dp).testTag("retainedQueue"), state = queueState) {
                    items(80) { Text("queued-$it", Modifier.height(48.dp)) }
                }
            }
            setContent {
                Box(Modifier.size(if (landscape) 800.dp else 412.dp, if (landscape) 480.dp else 800.dp)) {
                    fixture.Content(upNext = queue)
                }
            }
            onAllNodesWithText("Up next").onLast().performScrollTo().performClick()
            onNodeWithTag("retainedQueue").performScrollToIndex(20)
            onNodeWithText("queued-20").assertIsDisplayed()
            runOnIdle { landscape = false }
            onNodeWithTag("retainedQueue").performScrollTo()
            onNodeWithText("queued-20").assertIsDisplayed()
            runOnIdle { fixture.layout = PaneLayout(1, true, 692) }
            onNodeWithTag("retainedQueue").performScrollTo()
            onNodeWithText("queued-20").assertIsDisplayed()
            runOnIdle { assertEquals(1, queueInstances) }
        }

    @Test
    fun compactKeyboardFocusScrollsIntoViewAndCollapseRestoresMiniFocus() =
        runDesktopComposeUiTest(width = 700, height = 320) {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { fixture.Content() } }
            onNodeWithText("Sleep timer")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithText("Sleep timer").assertIsDisplayed().assertIsFocused()
            onNodeWithContentDescription("Collapse player").performScrollTo().performClick()
            onNodeWithTag("miniPlayer").assertIsDisplayed().assertIsFocused()
        }

    @Test
    fun consumedDeepLinkCannotReopenHiddenPanelAfterIdleAndNewPlayback() =
        runDesktopComposeUiTest(width = 900, height = 800) {
            val fixture = SheetFixture()
            var visible by mutableStateOf(true)
            setContent {
                if (visible) fixture.Content(sheetState = rememberPlayerSheetState())
            }
            runOnIdle { fixture.expandToken++ }
            onNodeWithTag("fullPlayer").assertIsDisplayed()
            runOnIdle {
                assertEquals(1, fixture.consumedExpandToken)
                visible = false
            }
            waitForIdle()
            runOnIdle {
                fixture.now = sampleNow(id = 2)
                fixture.reopenPanelFromMini = true
                visible = true
            }
            onNodeWithTag("miniPlayer").assertIsDisplayed()
            onNodeWithTag("fullPlayer").assertDoesNotExist()
            runOnIdle { assertTrue(fixture.calls.isEmpty()) }
        }

    @Test
    fun miniArtworkTapReopensHiddenPanel() {
        runDesktopComposeUiTest(width = 900, height = 800) {
            val fixture = SheetFixture().apply { reopenPanelFromMini = true }
            setContent { fixture.Content() }
            onNodeWithTag("playerArtwork").performTouchInput { click() }
            runOnIdle { assertEquals(listOf("reopenPanel"), fixture.calls) }
            onNodeWithTag("playerArtwork").performTouchInput {
                swipe(center, center + Offset(0f, -600f), 400)
            }
            runOnIdle { assertEquals(listOf("reopenPanel", "reopenPanel"), fixture.calls) }
        }
    }

    @Test
    fun railMiniKeepsSettingsGearReachableAndReopens() {
        runDesktopComposeUiTest(width = 900, height = 900) {
            val fixture = SheetFixture()
            var hidden by mutableStateOf(true)
            var reopens = 0
            val entries: Set<ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller> =
                setOf({
                    entry<ch.lkmc.neutrodyne.core.navigation.FeedsKey> { Text("Feeds content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.LibraryKey> { Text("Library content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.UpNextKey> { Text("Queue content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.DownloadsKey> { Text("Downloads content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.DiscoverKey> { Text("Discover content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey> { Text("Settings home") }
                })
            setContent {
                ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot(
                    state =
                        ch.lkmc.neutrodyne.core.ui.root.RootUiState.READY.copy(
                            hasNowPlaying = true,
                            playerPanelHidden = hidden,
                        ),
                    actions =
                        ch.lkmc.neutrodyne.core.ui.root.RootActions(
                            {},
                            {},
                            {},
                            {},
                            { false },
                            { hidden = true },
                            {
                                reopens++
                                hidden = false
                            },
                        ),
                    slots =
                        ch.lkmc.neutrodyne.core.ui.root.RootSlots(
                            player = { fixture.Content(slotOverride = it) },
                            userMessages = kotlinx.coroutines.flow.emptyFlow(),
                        ),
                    installers = entries,
                    platform = ch.lkmc.neutrodyne.core.model.BuildInfo.Platform.DESKTOP,
                )
            }
            onNodeWithContentDescription("Settings").performTouchInput { click() }
            onNodeWithText("Settings home").assertIsDisplayed()
            val dock = onNodeWithTag("miniPlayer").fetchSemanticsNode().boundsInRoot
            assertTrue(dock.left >= 96f && dock.right <= 900f)
            onNodeWithTag("miniPlayer").performTouchInput { click() }
            onNodeWithTag("playerSidePanel").assertIsDisplayed()
            runOnIdle { assertEquals(1, reopens) }
            onNodeWithContentDescription("Close player panel").performScrollTo().performClick()
            onNodeWithTag("miniPlayer").assertIsDisplayed()
        }
    }

    @Test
    fun shortBarRemainsReachableBesidePanel() {
        runDesktopComposeUiTest(width = 960, height = 400) {
            val fixture = SheetFixture()
            var hidden by mutableStateOf(false)
            val entries: Set<ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller> =
                setOf({
                    entry<ch.lkmc.neutrodyne.core.navigation.FeedsKey> { Text("Feeds content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.LibraryKey> { Text("Library content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.UpNextKey> { Text("Queue content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.DownloadsKey> { Text("Downloads content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.DiscoverKey> { Text("Discover content") }
                    entry<ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey> { Text("Settings home") }
                })
            setContent {
                ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot(
                    state =
                        ch.lkmc.neutrodyne.core.ui.root.RootUiState.READY.copy(
                            hasNowPlaying = true,
                            playerPanelHidden = hidden,
                        ),
                    actions =
                        ch.lkmc.neutrodyne.core.ui.root.RootActions(
                            {},
                            {},
                            {},
                            {},
                            { false },
                            { hidden = true },
                            {
                                hidden = false
                            },
                        ),
                    slots =
                        ch.lkmc.neutrodyne.core.ui.root.RootSlots(
                            player = { fixture.Content(slotOverride = it) },
                            userMessages = kotlinx.coroutines.flow.emptyFlow(),
                        ),
                    installers = entries,
                    platform = ch.lkmc.neutrodyne.core.model.BuildInfo.Platform.DESKTOP,
                )
            }
            onNodeWithTag("nav_discover").performTouchInput { click() }
            onNodeWithText("Discover content").assertIsDisplayed()
            val panel = onNodeWithTag("playerSidePanel").fetchSemanticsNode().boundsInRoot
            val navigation = onNodeWithTag("nav_discover").fetchSemanticsNode().boundsInRoot
            assertTrue(panel.bottom <= navigation.top, "Panel covers the bottom navigation: $panel / $navigation")
        }
    }

    @Test
    fun shortPanelAtDoubleFontKeepsConsentTargetsSeparateAndOperable() {
        runDesktopComposeUiTest(width = 360, height = 320) {
            val fixture =
                SheetFixture().apply {
                    layout = PaneLayout(1, true, 384)
                    now = sampleNow().copy(issue = ch.lkmc.neutrodyne.playback.api.PlaybackIssue.METERED_PAUSE)
                }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { fixture.Content() }
            }
            onNodeWithText("Continue").performScrollTo().performTouchInput { click() }
            onNodeWithText("Not now").performScrollTo().performTouchInput { click() }
            val consent = onNodeWithText("Continue").fetchSemanticsNode().boundsInRoot
            val decline = onNodeWithText("Not now").fetchSemanticsNode().boundsInRoot
            val controls = onNodeWithTag("playerControlsRegion").fetchSemanticsNode().boundsInRoot
            assertTrue(
                consent.width >= 48f && decline.width >= 48f,
                "Consent targets must be at least 48 dp: $consent / $decline",
            )
            assertTrue(consent.right <= decline.left || consent.bottom <= decline.top)
            assertTrue(consent.left >= controls.left && decline.right <= controls.right)
            runOnIdle { assertEquals(listOf("consent", "decline"), fixture.calls) }
        }
    }
}
