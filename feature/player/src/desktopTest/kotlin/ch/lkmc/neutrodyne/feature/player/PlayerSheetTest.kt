// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.navigation.PaneLayout
import ch.lkmc.neutrodyne.core.navigation.SleepTimerKey
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
            onNodeWithTag("fullPlayer").assertIsDisplayed()
            onAllNodesWithText("Up next").onLast().performClick()
            onNodeWithTag("upNextList").performScrollToNode(hasText("Episode 3"))
            onNodeWithText("Episode 3").performCustomAccessibilityActionWithLabel("Move to top")
            runOnIdle { assertEquals(listOf(3L to 0), moves) }
            onNodeWithText("Episode 3").performCustomAccessibilityActionWithLabel("Remove from Up next")
            runOnIdle { assertEquals(listOf(2L), queued.map { it.row.id }) }
            onNodeWithText("Episode 3").assertDoesNotExist()
        }

    @Test fun chaptersLabelsTabsOverflowSleepAndEmptyFallback() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded)
            fixture.chapters =
                listOf(
                    EpisodeChapter(
                        0,
                        null,
                        "Opening",
                        null,
                        null,
                        ChapterSource.PSC,
                        false,
                    ),
                )
            setContent { fixture.Content() }
            onNodeWithContentDescription("Next chapter").performClick()
            onNodeWithContentDescription("Previous chapter").performClick()
            onNodeWithText("Chapters (1)").performScrollTo().performClick()
            onNodeWithText("Opening").performScrollTo().performClick()
            onNodeWithContentDescription("More options").performClick()
            onNodeWithText("Next episode").performClick()
            onNodeWithText("Sleep timer").performScrollTo().performClick()
            assertEquals(listOf("next", "previous", "seek:0", "nextEpisode"), fixture.calls)
            assertEquals(
                listOf<androidx.navigation3.runtime.NavKey>(SleepTimerKey),
                fixture.navigated,
            )
            runOnIdle { fixture.chapters = emptyList() }
            onNodeWithText("Chapters (1)").assertDoesNotExist()
            onNodeWithContentDescription("Next episode").performScrollTo().assertIsDisplayed()
        }

    @Test fun hidingMiniSkipKeepsFullPlayerSkip() =
        runComposeUiTest {
            val fixture = SheetFixture(PlayerSheetValue.Expanded).apply { miniSkip = false }
            setContent { fixture.Content() }
            onNodeWithContentDescription("Forward 30 seconds").assertIsDisplayed()
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
            onNodeWithTag("miniPlayer").performClick()
            onNodeWithTag("fullPlayer").assertIsDisplayed()
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
            onNodeWithTag("fullPlayer").assertIsDisplayed()
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
            onNodeWithTag("miniPlayer").assertIsDisplayed()
        }

    @Test fun miniExpandsAndExposesTransportAndAccessibilityActions() =
        runComposeUiTest {
            val fixture = SheetFixture()
            setContent { fixture.Content() }
            onNodeWithTag("miniPlayer").assertIsDisplayed().performClick()
            onNodeWithTag("fullPlayer").assertIsDisplayed()
            onNodeWithContentDescription("Play").performClick()
            onNodeWithContentDescription("Previous episode").performClick()
            onNodeWithContentDescription("Next episode").performClick()
            onNodeWithContentDescription("Back 10 seconds").performClick()
            onNodeWithContentDescription("Forward 30 seconds").performClick()
            assertEquals(listOf("toggle", "previous", "next", "back", "forward"), fixture.calls)
            onNodeWithContentDescription("Collapse player").performClick()
            onNodeWithTag("miniPlayer").assertIsDisplayed()
        }

    @Test fun verticalDragExpandsAndCollapsesThePlayer() =
        runComposeUiTest {
            val fixture = SheetFixture().apply { contentBottomPx = 600f }
            setContent { fixture.Content() }
            onNodeWithTag("miniPlayer").performTouchInput {
                swipe(center, center + Offset(0f, -500f), durationMillis = 400)
            }
            onNodeWithTag("fullPlayer").assertIsDisplayed()
            onNodeWithTag("playerArtwork").performTouchInput {
                swipe(center, center + Offset(0f, 400f), durationMillis = 400)
            }
            onNodeWithTag("miniPlayer").assertIsDisplayed()
        }

    @Test fun downwardDragDismissesOnlyWhilePaused() =
        runComposeUiTest {
            val fixture = SheetFixture().apply { contentBottomPx = 600f }
            setContent { fixture.Content() }
            onNodeWithTag("miniPlayer").performTouchInput {
                swipe(center, center + Offset(0f, 150f), durationMillis = 400)
            }
            waitForIdle()
            assertEquals(listOf("dismiss"), fixture.calls)
            runOnIdle { fixture.now = sampleNow(playing = true) }
            onNodeWithTag("miniPlayer").performTouchInput {
                swipe(center, center + Offset(0f, 150f), durationMillis = 400)
            }
            waitForIdle()
            assertEquals(listOf("dismiss"), fixture.calls)
        }

    @Test fun dragSurvivesRecompositionAcrossMiniAndFullBoundaries() =
        runComposeUiTest {
            val fixture = SheetFixture().apply { contentBottomPx = 600f }
            setContent { fixture.Content() }
            onNodeWithTag("miniPlayer").performTouchInput {
                down(center)
                moveBy(Offset(0f, -200f))
            }
            waitForIdle()
            onNodeWithTag("playerHost").performTouchInput {
                moveBy(Offset(0f, -300f))
                up()
            }
            onNodeWithTag("fullPlayer").assertIsDisplayed()
            onNodeWithContentDescription("Collapse player").performTouchInput {
                down(center)
                moveBy(Offset(0f, 300f))
            }
            waitForIdle()
            onNodeWithTag("playerHost").performTouchInput {
                moveBy(Offset(0f, 200f))
                up()
            }
            onNodeWithTag("miniPlayer").assertIsDisplayed()
        }

    @Test fun pausedMiniHasDismissActionAndPlayingMiniDoesNot() =
        runComposeUiTest {
            val fixture = SheetFixture()
            setContent { fixture.Content() }
            val actions = onNodeWithTag("miniPlayer").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertTrue(actions.any { it.label == "Dismiss player" })
            runOnIdle { actions.first { it.label == "Dismiss player" }.action() }
            assertEquals(listOf("dismiss"), fixture.calls)
            runOnIdle { fixture.now = sampleNow(playing = true) }
            val playingActions = onNodeWithTag("miniPlayer").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertFalse(playingActions.any { it.label == "Dismiss player" })
        }

    @Test fun nullCurrentHidesSheetButConsentStillWorks() =
        runComposeUiTest {
            val fixture = SheetFixture()
            fixture.now = null
            fixture.pending = PlayResult.NeedsMeteredConsent(1)
            setContent { fixture.Content() }
            onNodeWithTag("playerSheet").assertDoesNotExist()
            onNodeWithText("Continue on mobile data?").assertIsDisplayed()
            onNodeWithText("Not now").performClick()
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
            onNodeWithText("Notes").performScrollTo().performClick()
            runOnIdle {
                input.backStarted(NavigationEvent())
                input.backCompleted()
            }
            onNodeWithTag("fullPlayer").assertIsDisplayed()
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
            onNodeWithTag("miniPlayer").assertIsDisplayed()
            runOnIdle {
                assertFalse(fixture.state.isExpanded)
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
            onNodeWithTag("miniPlayer").performClick()
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
            onNodeWithTag("fullPlayer").assertIsDisplayed()
            runOnIdle { fixture.collapseToken++ }
            onNodeWithTag("miniPlayer").assertIsDisplayed()
        }
}

private class SheetFixture(
    initial: PlayerSheetValue = PlayerSheetValue.Collapsed,
) {
    val state = PlayerSheetState(initial)
    var now by mutableStateOf<NowPlaying?>(sampleNow())
    var chapters by mutableStateOf<List<EpisodeChapter>>(emptyList())
    var pending by mutableStateOf<PlayResult.NeedsMeteredConsent?>(null)
    var expandToken by mutableIntStateOf(0)
    var collapseToken by mutableIntStateOf(0)
    var contentBottomPx = 0f
    var miniSkip = true
    val calls = mutableListOf<String>()
    val navigated = mutableListOf<androidx.navigation3.runtime.NavKey>()
    var expanded = false

    @androidx.compose.runtime.Composable
    fun Content(
        sheetState: PlayerSheetState = state,
        upNext: @androidx.compose.runtime.Composable () -> Unit = {},
    ) {
        NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT.copy(reducedMotion = true)) {
            PlayerSheet(
                ui = PlayerUiState(now = now, consent = pending, miniSkip = miniSkip, chapters = chapters),
                position = now?.position,
                slot =
                    PlayerSlotState(
                        PaneLayout(1, false, 400),
                        { navigated += it },
                        contentBottomPx = contentBottomPx,
                        expandRequest = expandToken,
                        collapseRequest = collapseToken,
                        onExpandedSettled = { expanded = it },
                    ),
                state = sheetState,
                upNext = upNext,
                positionAt = { it.positionAt(1_000) },
                toggle = { calls += "toggle" },
                back = { calls += "back" },
                forward = { calls += "forward" },
                previous = { calls += "previous" },
                next = { calls += "next" },
                nextEpisode = { calls += "nextEpisode" },
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
