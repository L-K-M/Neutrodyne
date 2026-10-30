// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.navigation.PaneLayout
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.ui.RowCaps
import ch.lkmc.neutrodyne.core.ui.UpNextList
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlayerSheetTest {
    private var previousLocale = Locale.ENGLISH

    @BeforeTest fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test fun fullPlayerQueueSlotShowsAndEditsTheSharedList() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            var queued by mutableStateOf(
                (2L..3L).map {
                    UpNextItem(it, testEpisodeRow(it))
                },
            )
            val moves = mutableListOf<Pair<Long, Int>>()
            setContent {
                fixture.Content(upNext = {
                    UpNextList(
                        current = null,
                        upNext = queued,
                        context = null,
                        live = emptyMap(),
                        caps = RowCaps.FULL.copy(downloads = false),
                        onAction = {},
                        onMove = { id, target ->
                            moves += id to target
                            queued =
                                queued.toMutableList().apply { add(target, removeAt(indexOfFirst { it.row.id == id })) }
                        },
                        onRemove = { id -> queued = queued.filterNot { it.row.id == id } },
                        onStopAfter = {},
                    )
                })
            }
            onNodeWithTag("full_player").assertIsDisplayed()
            onNodeWithContentDescription("Expand player tabs").performClick()
            onNodeWithTag("upNextList").performScrollToNode(hasText("Episode 3"))
            onNodeWithText("Episode 3").performCustomAccessibilityActionWithLabel("Move to top")
            runOnIdle { assertEquals(listOf(3L to 0), moves) }
            onNodeWithText("Episode 3").performCustomAccessibilityActionWithLabel("Remove from Up next")
            runOnIdle { assertEquals(listOf(2L), queued.map { it.row.id }) }
            onNodeWithText("Episode 3").assertDoesNotExist()
        }

    @Test fun hidingMiniSkipKeepsFullPlayerSkip() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded).apply { miniSkip = false }
            setContent { fixture.Content() }
            onNodeWithContentDescription("Forward 30 seconds").assertIsDisplayed()
        }

    @Test fun openPlayerPanelHidesTransportUntilCollapsed() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            setContent { fixture.Content() }
            onNodeWithContentDescription("Play").assertIsDisplayed()
            onNodeWithContentDescription("Expand player tabs").performClick()
            onNodeWithContentDescription("Play").assertDoesNotExist()
            onNodeWithContentDescription("Collapse player tabs").performClick()
            onNodeWithContentDescription("Play").assertIsDisplayed()
        }

    @Test fun openPlayerPanelDoesNotRetainTransportKeyboardFocus() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            setContent { fixture.Content() }
            val play = onNodeWithContentDescription("Play")
            play.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            play.assertIsFocused()
            play.performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            runOnIdle {
                assertEquals(listOf("toggle"), fixture.calls)
                fixture.calls.clear()
            }
            onNodeWithContentDescription("Expand player tabs").performClick()
            onNodeWithTag("playerHost").performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            runOnIdle { assertTrue(fixture.calls.isEmpty(), "An obscured control retained keyboard focus") }
        }

    @Test fun expandedAndCollapsedSurviveSaveableRestoration() =
        runComposeUiTest {
            val fixture = SheetFixture()
            var registry by mutableStateOf(SaveableStateRegistry(null) { true })
            var show by mutableStateOf(true)
            setContent {
                if (show) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        fixture.Content(rememberPlayerSheetState())
                    }
                }
            }
            onNodeWithTag("mini_player").performClick()
            onNodeWithTag("full_player").assertIsDisplayed()
            var saved = emptyMap<String, List<Any?>>()
            runOnIdle {
                saved = registry.performSave()
                show = false
            }
            waitForIdle()
            runOnIdle {
                registry = SaveableStateRegistry(saved) { true }
                show = true
            }
            onNodeWithTag("full_player").assertIsDisplayed()
            onNodeWithContentDescription("Collapse player").performClick()
            runOnIdle {
                saved = registry.performSave()
                show = false
            }
            waitForIdle()
            runOnIdle {
                registry = SaveableStateRegistry(saved) { true }
                show = true
            }
            onNodeWithTag("mini_player").assertIsDisplayed()
        }

    @Test fun miniExpandsAndExposesTransportAndAccessibilityActions() =
        runComposeUiTest {
            val fixture = SheetFixture()
            setContent { fixture.Content() }
            onNodeWithTag("mini_player").assertIsDisplayed().performClick()
            onNodeWithTag("full_player").assertIsDisplayed()
            onNodeWithContentDescription("Play").performClick()
            onNodeWithContentDescription("Previous episode").performClick()
            onNodeWithContentDescription("Next episode").performClick()
            onNodeWithContentDescription("Back 10 seconds").performClick()
            onNodeWithContentDescription("Forward 30 seconds").performClick()
            assertEquals(listOf("toggle", "previous", "next", "back", "forward"), fixture.calls)
            onNodeWithContentDescription("Collapse player").performClick()
            onNodeWithTag("mini_player").assertIsDisplayed()
        }

    @Test fun verticalDragExpandsAndCollapsesThePlayer() =
        runComposeUiTest {
            val fixture = SheetFixture().apply { contentBottomPx = 600f }
            setContent { fixture.Content() }
            onNodeWithTag("mini_player").performTouchInput {
                swipe(center, center + Offset(0f, -500f), durationMillis = 400)
            }
            onNodeWithTag("full_player").assertIsDisplayed()
            onNodeWithTag("playerArtwork").performTouchInput {
                swipe(center, center + Offset(0f, 400f), durationMillis = 400)
            }
            onNodeWithTag("mini_player").assertIsDisplayed()
        }

    @Test fun downwardDragDismissesOnlyWhilePaused() =
        runComposeUiTest {
            val fixture = SheetFixture().apply { contentBottomPx = 600f }
            setContent { fixture.Content() }
            onNodeWithTag("mini_player").performTouchInput {
                swipe(center, center + Offset(0f, 150f), durationMillis = 400)
            }
            waitForIdle()
            assertEquals(listOf("dismiss"), fixture.calls)
            runOnIdle { fixture.now = sampleNow(playing = true) }
            onNodeWithTag("mini_player").performTouchInput {
                swipe(center, center + Offset(0f, 150f), durationMillis = 400)
            }
            waitForIdle()
            assertEquals(listOf("dismiss"), fixture.calls)
        }

    @Test fun dragSurvivesRecompositionAcrossMiniAndFullBoundaries() =
        runComposeUiTest {
            val fixture = SheetFixture().apply { contentBottomPx = 600f }
            setContent { fixture.Content() }
            onNodeWithTag("mini_player").performTouchInput {
                down(center)
                moveBy(Offset(0f, -200f))
            }
            waitForIdle()
            onNodeWithTag("playerHost").performTouchInput {
                moveBy(Offset(0f, -300f))
                up()
            }
            onNodeWithTag("full_player").assertIsDisplayed()
            onNodeWithContentDescription("Collapse player").performTouchInput {
                down(center)
                moveBy(Offset(0f, 300f))
            }
            waitForIdle()
            onNodeWithTag("playerHost").performTouchInput {
                moveBy(Offset(0f, 200f))
                up()
            }
            onNodeWithTag("mini_player").assertIsDisplayed()
        }

    @Test fun pausedMiniHasDismissActionAndPlayingMiniDoesNot() =
        runComposeUiTest {
            val fixture = SheetFixture()
            setContent { fixture.Content() }
            val actions = onNodeWithTag("mini_player").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertTrue(actions.any { it.label == "Dismiss player" })
            runOnIdle { actions.first { it.label == "Dismiss player" }.action() }
            assertEquals(listOf("dismiss"), fixture.calls)
            runOnIdle { fixture.now = sampleNow(playing = true) }
            val playingActions =
                onNodeWithTag(
                    "mini_player",
                ).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertFalse(playingActions.any { it.label == "Dismiss player" })
        }

    @Test fun nullCurrentHidesSheetButConsentStillWorks() =
        runComposeUiTest {
            val fixture = SheetFixture()
            fixture.now = null
            fixture.pending = PlayResult.NeedsMeteredConsent(1)
            setContent { fixture.Content() }
            onNodeWithTag("player_sheet").assertDoesNotExist()
            onNodeWithText("Stream on mobile data?").assertIsDisplayed()
            onNodeWithText("Once").assertIsDisplayed()
            onNodeWithText("Always").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertEquals(listOf("decline"), fixture.calls)
        }

    @Test fun overflowMarkPlayedDoesNotSendNextAndSpeedUsesNavigationKey() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            setContent { fixture.Content() }
            onNodeWithText("Speed 1.0×").performClick()
            assertEquals(listOf<androidx.navigation3.runtime.NavKey>(SpeedKey), fixture.navigated)
            onNodeWithContentDescription("More options").performClick()
            onNodeWithText("Mark played and skip").performClick()
            assertEquals(listOf("markPlayed"), fixture.calls)
        }

    @Test fun predictiveBackCancellationKeepsExpandedAndCommitCollapses() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
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
            runOnIdle {
                input.backStarted(NavigationEvent())
                input.backProgressed(NavigationEvent(progress = 0.5f))
            }
            runOnIdle {
                assertEquals(0.9f, fixture.state.progress)
                input.backCancelled()
            }
            runOnIdle {
                assertEquals(1f, fixture.state.progress)
                assertTrue(fixture.state.isExpanded)
            }
            runOnIdle {
                input.backStarted(NavigationEvent())
                input.backCompleted()
            }
            onNodeWithTag("mini_player").assertIsDisplayed()
            runOnIdle {
                assertFalse(fixture.state.isExpanded)
                dispatcher.dispose()
            }
        }

    @Test fun notesOpenLazilyAndBackClosesThePanelBeforeThePlayer() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            val dispatcher = NavigationEventDispatcher()
            var underlyingBack = 0
            dispatcher.addHandler(
                object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, true) {
                    override fun onBackCompleted() {
                        underlyingBack++
                    }
                },
            )
            val input = DirectNavigationEventInput()
            dispatcher.addInput(input)
            val owner =
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = dispatcher
                }
            var activeNotes = 0
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                    fixture.Content(notes = {
                        DisposableEffect(Unit) {
                            activeNotes++
                            onDispose { activeNotes-- }
                        }
                        Text("Notes body")
                    })
                }
            }
            runOnIdle { assertEquals(0, activeNotes) }
            onNodeWithText("Notes").performClick()
            onNodeWithText("Notes body").assertIsDisplayed()
            runOnIdle { assertEquals(1, activeNotes) }
            runOnIdle { input.backCompleted() }
            onNodeWithTag("full_player").assertIsDisplayed()
            onNodeWithText("Notes body").assertDoesNotExist()
            runOnIdle {
                assertEquals(0, activeNotes)
                input.backCompleted()
            }
            onNodeWithTag("mini_player").assertIsDisplayed()
            runOnIdle {
                input.backCompleted()
                assertEquals(1, underlyingBack)
                dispatcher.dispose()
            }
        }

    @Test fun largeFontHeaderKeepsAContextLineVisibleAndTransportReachable() =
        runComposeUiTest {
            val groupTitle = "A long group name that must not disappear behind the artwork"
            val fixture =
                SheetFixture(PlayerSheetValue.Expanded).apply {
                    now =
                        sampleNow().copy(
                            context =
                                PlayContextInfo(
                                    ContextType.GROUP,
                                    3,
                                    groupTitle,
                                    FeedOrder.NEWEST_FIRST,
                                    0,
                                    MediaFilter.ALL,
                                ),
                        )
                }
            setContent { fixture.StressContent() }
            val context = onNodeWithText(groupTitle, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            context.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertEquals(PLAYER_STRESS_FONT_SCALE, layout.layoutInput.density.fontScale)
            // Horizontal ellipsis is allowed; its full font-scaled line must still fit.
            assertEquals(1, layout.lineCount)
            val lineHeight =
                with(layout.layoutInput.density) {
                    layout.layoutInput.style.lineHeight
                        .toPx()
                }
            val bounds = context.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= lineHeight, "The context line was vertically clipped")
            assertTrue(bounds.bottom <= onNodeWithTag("playerArtwork").fetchSemanticsNode().boundsInRoot.top)

            onNodeWithTag("full_player").performScrollToNode(hasContentDescription("Play"))
            onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(listOf("toggle"), fixture.calls) }
        }

    @Test fun largeFontTabLabelsAreNotVerticallyClipped() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            setContent { fixture.StressContent() }
            val label =
                onNode(hasText("Up next", substring = true).and(hasAnyAncestor(hasTestTag("player_tabs_panel"))))
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            val textHeight = layout.getLineBottom(layout.lineCount - 1) - layout.getLineTop(0)
            assertTrue(
                label.fetchSemanticsNode().boundsInRoot.height >= textHeight,
                "The tab label was vertically clipped",
            )
        }

    @Test fun cancelledPanelBackDuringSheetCollapseDoesNotTrapTheNextBack() =
        runComposeUiTest {
            val fixture =
                SheetFixture(PlayerSheetValue.Expanded).apply {
                    systemUi = SystemUiState.DEFAULT.copy(reducedMotion = false)
                }
            val dispatcher = NavigationEventDispatcher()
            var underlyingBack = 0
            dispatcher.addHandler(
                object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, true) {
                    override fun onBackCompleted() {
                        underlyingBack++
                    }
                },
            )
            val input = DirectNavigationEventInput()
            dispatcher.addInput(input)
            val owner =
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = dispatcher
                }
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                    fixture.Content(notes = { Text("Notes body") })
                }
            }
            onNodeWithText("Notes").performClick()
            onNodeWithText("Notes body").assertIsDisplayed()
            mainClock.autoAdvance = false
            onNodeWithContentDescription("Collapse player").performClick()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            runOnIdle {
                assertFalse(fixture.expanded)
                assertTrue(fixture.state.canCollapse)
                input.backStarted(NavigationEvent())
                input.backProgressed(NavigationEvent(progress = 0.5f))
                input.backCancelled()
            }
            mainClock.autoAdvance = true
            onNodeWithTag("mini_player").assertIsDisplayed()
            runOnIdle {
                input.backCompleted()
                assertEquals(1, underlyingBack, "Cancelled panel Back trapped navigation behind the mini player")
                dispatcher.dispose()
            }
        }

    @Test fun heldSheetDragStillOwnsBackWhileThePanelCollapses() =
        runComposeUiTest {
            val fixture =
                SheetFixture(PlayerSheetValue.Expanded).apply {
                    systemUi = SystemUiState.DEFAULT.copy(reducedMotion = false)
                }
            val dispatcher = NavigationEventDispatcher()
            var underlyingBack = 0
            dispatcher.addHandler(
                object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, true) {
                    override fun onBackCompleted() {
                        underlyingBack++
                    }
                },
            )
            val input = DirectNavigationEventInput()
            dispatcher.addInput(input)
            val owner =
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = dispatcher
                }
            setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) { fixture.Content() }
            }
            onNodeWithText("Notes").performClick()
            // A frozen frame clock parks the launched panel collapse mid-flight, holding the window
            // where PlayerTabs is gone while panelState.canCollapse is still true.
            mainClock.autoAdvance = false
            val dragBy = onNodeWithTag("playerHost").fetchSemanticsNode().boundsInRoot.height * 0.55f
            onNodeWithContentDescription("Collapse player").performTouchInput {
                down(center)
                moveBy(Offset(0f, 80f))
                moveBy(Offset(0f, dragBy))
            }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            runOnIdle {
                assertTrue(
                    fixture.state.progress in 0.01f..0.59f,
                    "Held header drag left the sheet at progress ${fixture.state.progress}",
                )
            }
            onNodeWithTag("player_tabs_panel").assertDoesNotExist()
            runOnIdle {
                input.backStarted(NavigationEvent())
                input.backCompleted()
            }
            assertEquals(
                0,
                underlyingBack,
                "Back reached the covered content while the collapsing player panel still owned it",
            )
            onNodeWithTag("playerHost").performTouchInput { up() }
            mainClock.autoAdvance = true
            runOnIdle { fixture.collapseToken++ }
            onNodeWithTag("mini_player").assertIsDisplayed()
            runOnIdle {
                input.backStarted(NavigationEvent())
                input.backCompleted()
                assertEquals(1, underlyingBack)
                dispatcher.dispose()
            }
        }

    @Test fun scrubberSeeksOnlyOnReleaseAndClampsAtEnd() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            setContent { fixture.Content() }
            assertEquals(
                "12 seconds of 1 minute",
                onNodeWithTag("playerScrubber").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
            )
            onNodeWithTag("playerScrubber").assertIsDisplayed().performTouchInput {
                down(Offset(width * 0.2f, center.y))
                moveTo(Offset(width * 0.7f, center.y))
            }
            waitForIdle()
            assertTrue(
                onNodeWithTag(
                    "playerScrubber",
                ).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current >
                    12_000f,
            )
            assertTrue(fixture.calls.isEmpty())
            onNodeWithTag("playerScrubber").performTouchInput { up() }
            waitForIdle()
            assertEquals(1, fixture.calls.count { it.startsWith("seek:") })
        }

    @Test fun reducedMotionSettlesAndReportsSuiteOnlyAtExpandedAnchor() =
        runComposeUiTest {
            val fixture = SheetFixture()
            setContent { fixture.Content() }
            runOnIdle { assertFalse(fixture.expanded) }
            onNodeWithTag("mini_player").performClick()
            runOnIdle {
                assertTrue(fixture.expanded)
                assertEquals(1f, fixture.state.progress)
            }
            onNodeWithContentDescription("Collapse player").performClick()
            runOnIdle {
                assertFalse(fixture.expanded)
                assertEquals(0f, fixture.state.progress)
            }
        }

    @Test fun deepLinkExpandsAndNavigationRequestCollapses() =
        runComposeUiTest {
            val fixture = SheetFixture()
            setContent { fixture.Content() }
            runOnIdle { fixture.expandToken++ }
            onNodeWithTag("full_player").assertIsDisplayed()
            runOnIdle { fixture.collapseToken++ }
            onNodeWithTag("mini_player").assertIsDisplayed()
        }
}

private class SheetFixture(
    initial: PlayerSheetValue = PlayerSheetValue.Collapsed,
) {
    val state = PlayerSheetState(initial)
    var now by mutableStateOf<NowPlaying?>(sampleNow())
    var pending by mutableStateOf<PlayResult.NeedsMeteredConsent?>(null)
    var expandToken by mutableIntStateOf(0)
    var collapseToken by mutableIntStateOf(0)
    var contentBottomPx = 0f
    var miniSkip = true
    var systemUi = SystemUiState.DEFAULT.copy(reducedMotion = true)
    val calls = mutableListOf<String>()
    val navigated = mutableListOf<androidx.navigation3.runtime.NavKey>()
    var expanded = false

    @androidx.compose.runtime.Composable
    fun StressContent() {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, PLAYER_STRESS_FONT_SCALE),
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) {
            Box(Modifier.requiredSize(360.dp, PLAYER_CAPTURE_HEIGHT_DP.dp)) { Content() }
        }
    }

    @androidx.compose.runtime.Composable
    fun Content(
        sheetState: PlayerSheetState = state,
        upNext: @androidx.compose.runtime.Composable () -> Unit = {},
        notes: @androidx.compose.runtime.Composable () -> Unit = {},
    ) {
        NeutrodyneTheme(AppearancePrefs(), systemUi) {
            BoxWithConstraints {
                val paneLayout = PaneLayout(1, false, maxWidth.value.toInt())
                PlayerSheet(
                    ui =
                        PlayerUiState(
                            now = now,
                            consent =
                                pending?.let {
                                    ch.lkmc.neutrodyne.core.ui
                                        .MeteredConsent(it.episodeId)
                                },
                            miniSkip = miniSkip,
                        ),
                    position = now?.position,
                    slot =
                        PlayerSlotState(
                            paneLayout,
                            { navigated += it },
                            contentBottomPx = contentBottomPx,
                            expandRequest = expandToken,
                            collapseRequest = collapseToken,
                            onExpandedSettled = { expanded = it },
                        ),
                    state = sheetState,
                    upNext = upNext,
                    notes = notes,
                    positionAt = { it.positionAt(1_000) },
                    toggle = { calls += "toggle" },
                    back = { calls += "back" },
                    forward = { calls += "forward" },
                    previous = { calls += "previous" },
                    next = { calls += "next" },
                    seek = { calls += "seek:$it" },
                    dismiss = { calls += "dismiss" },
                    stop = { calls += "stop" },
                    markPlayed = { calls += "markPlayed" },
                    consent = { calls += "consent" },
                    declineConsent = { calls += "decline" },
                )
            }
        }
    }
}
