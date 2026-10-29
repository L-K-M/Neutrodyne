// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.queue

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.UpNextCurrent
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UpNextScreenTest {
    private companion object {
        const val SWIPE_PREVIEW_FRACTION = 0.2f
    }

    private val state =
        mutableStateOf(
            UpNextUiState(
                loaded = true,
                current =
                    testNowPlaying().let {
                        UpNextCurrent(it.episodeId, it.title, it.podcastTitle, it.artwork, it.isPlaying)
                    },
                upNext = (2L..4L).map { UpNextItem(it, testEpisodeRow(it)) },
                context = PlayContextInfo(ContextType.GROUP, 5, "tech", FeedOrder.NEWEST_FIRST, 0, MediaFilter.ALL),
            ),
        )
    private val moves = mutableListOf<Pair<Long, Int>>()
    private val removals = mutableListOf<Long>()
    private val actions = mutableListOf<EpisodeAction>()
    private var stopped = 0
    private var discovered = 0
    private var cleared = 0
    private lateinit var previousLocale: Locale

    @BeforeTest fun setup() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest fun restoreLocale() = Locale.setDefault(previousLocale)

    @Test fun embeddedQueueKeepsEditingWithoutDestinationChrome() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            content(surface = UpNextSurface.PLAYER)
            onNodeWithText("Up next").assertDoesNotExist()
            onNodeWithTag("queueCurrent").assertIsDisplayed()
            onNodeWithText("Clear Up next").performClick()
            assertEquals(1, cleared)
            onNodeWithText("Episode 3").performCustomAccessibilityActionWithLabel("Move up")
            assertEquals(listOf(3L to 0), moves)
        }

    @Test fun currentIsFixedAndContextStopsWithoutClearingQueue() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            content()
            onNodeWithTag("queueCurrent").assertIsDisplayed()
            onNodeWithTag("queueDrag1", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("queueCurrent").performCustomAccessibilityActionWithLabel("Pause")
            assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayToggle(1)), actions)
            onNodeWithText("Then: tech, Newest first").assertIsDisplayed()
            onNodeWithText("Stop after Up next").performClick()
            assertEquals(1, stopped)
            assertTrue(removals.isEmpty())
            assertTrue(moves.isEmpty())
        }

    @Test fun talkbackMovesRemovalAndPlaybackUseRowActions() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            content()
            onNodeWithText("Episode 3").performCustomAccessibilityActionWithLabel("Move up")
            onNodeWithText("Episode 3").performCustomAccessibilityActionWithLabel("Move down")
            onNodeWithText("Episode 4").performCustomAccessibilityActionWithLabel("Move to top")
            assertEquals(listOf(3L to 0, 3L to 2, 4L to 0), moves)
            onNodeWithText("Episode 3").performCustomAccessibilityActionWithLabel("Remove from Up next")
            onNodeWithText("Episode 2").performCustomAccessibilityActionWithLabel("Play next")
            onNodeWithText("Episode 2").performCustomAccessibilityActionWithLabel("Play last")
            onNodeWithText("Episode 2").performCustomAccessibilityActionWithLabel("Play")
            assertEquals(listOf(3L), removals)
            assertEquals(
                listOf(EpisodeAction.PlayNext(2), EpisodeAction.PlayLast(2), EpisodeAction.PlayToggle(2)),
                actions,
            )
            val handle = onNodeWithTag("queueDrag2", useUnmergedTree = true).fetchSemanticsNode()
            assertFalse(handle.config.contains(SemanticsActions.OnClick))
        }

    @Test fun keyboardMovesDeletesAndOpensRow() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            content()
            onNodeWithText("Episode 3").requestFocus()
            onNodeWithText("Episode 3").performKeyInput {
                keyDown(Key.AltLeft)
                pressKey(Key.DirectionUp)
                keyUp(Key.AltLeft)
                pressKey(Key.Delete)
                pressKey(Key.Enter)
            }
            assertEquals(listOf(3L to 0), moves)
            assertEquals(listOf(3L), removals)
            assertEquals(listOf<EpisodeAction>(EpisodeAction.Open(3)), actions)
        }

    @Test fun mouseContextMenuMovesRows() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            content()
            onNodeWithText("Episode 3").performMouseInput { click(center, MouseButton.Secondary) }
            onNodeWithText("Move to top").performClick()
            assertEquals(listOf(3L to 0), moves)
        }

    @Test fun mouseDragWritesOnlyOnRelease() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            content()
            val start = onNodeWithTag("queueDrag2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
            val end = onNodeWithText("Episode 4").fetchSemanticsNode().boundsInRoot.center
            onRoot().performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, 20f), delayMillis = 50)
            }
            waitForIdle()
            onRoot().performMouseInput { moveTo(Offset(start.x, end.y), delayMillis = 500) }
            waitForIdle()
            assertTrue(moves.isEmpty())
            onRoot().performMouseInput { release() }
            waitForIdle()
            assertEquals(listOf(2L to 2), moves)
        }

    @Test fun escapeCancelsDragWithoutWrite() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            content()
            val start = onNodeWithTag("queueDrag2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
            val end = onNodeWithText("Episode 4").fetchSemanticsNode().boundsInRoot.center
            onRoot().performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, 20f), delayMillis = 50)
            }
            waitForIdle()
            onRoot().performMouseInput { moveTo(Offset(start.x, end.y), delayMillis = 500) }
            waitForIdle()
            onNodeWithText("Episode 2").requestFocus()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            onRoot().performMouseInput { release() }
            waitForIdle()
            assertTrue(moves.isEmpty())
        }

    @Test fun touchSwipeRemovesButDesktopSwipeDoesNot() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            content(platform = PlatformKind.ANDROID)
            onNodeWithText("Episode 3").performTouchInput { swipeRight() }
            assertEquals(listOf(3L), removals)
        }

    @Test fun androidSwipeTracksFingerInLtr() = assertTouchSwipeTracksFinger(LayoutDirection.Ltr)

    @Test fun androidSwipeTracksFingerInRtl() = assertTouchSwipeTracksFinger(LayoutDirection.Rtl)

    private fun assertTouchSwipeTracksFinger(direction: LayoutDirection) =
        runDesktopComposeUiTest(width = 650, height = 950) {
            content(platform = PlatformKind.ANDROID, layoutDirection = direction)
            val row = onNodeWithText("Episode 3")
            val before = row.fetchSemanticsNode().boundsInRoot
            val distance = before.width * SWIPE_PREVIEW_FRACTION * if (direction == LayoutDirection.Ltr) 1 else -1
            row.performTouchInput {
                down(center)
                moveBy(Offset(distance, 0f))
            }
            val shift =
                row
                    .fetchSemanticsNode()
                    .boundsInRoot.center.x - before.center.x
            assertTrue(shift * distance > 0f, "$direction: row shifted $shift for pointer delta $distance")
            onRoot().performTouchInput { up() }
            waitForIdle()
            assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
            assertTrue(removals.isEmpty())

            row.performTouchInput {
                if (direction == LayoutDirection.Rtl) swipeLeft() else swipeRight()
            }
            assertEquals(listOf(3L), removals)
        }

    @Test fun desktopSwipeKeepsUnavailableRows() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            state.value =
                state.value.copy(
                    upNext = listOf(UpNextItem(2, testEpisodeRow(2, availability = Availability.UNAVAILABLE))),
                )
            content()
            onNodeWithText("Episode 2").performTouchInput { swipeRight() }
            assertTrue(removals.isEmpty())
            onNodeWithText("Episode 2")
                .assertIsDisplayed()
                .performCustomAccessibilityActionWithLabel("Remove from Up next")
            assertEquals(listOf(2L), removals)
            onNodeWithText("Clear Up next").performClick()
            assertEquals(1, cleared)
        }

    @Test fun offlineBannerShowsBelowChromeOnlyWhileOffline() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            val banner = "You're offline — downloaded episodes still play"
            state.value = state.value.copy(caps = state.value.caps.copy(offline = true))
            content()
            onNodeWithText(banner).assertIsDisplayed()
            val bannerTop = onNodeWithText(banner).fetchSemanticsNode().boundsInRoot.top
            val titleBottom = onNodeWithText("Up next").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue(bannerTop >= titleBottom)
            state.value = state.value.copy(upNext = emptyList())
            onNodeWithText(banner).assertIsDisplayed()
            state.value = state.value.copy(caps = state.value.caps.copy(offline = false))
            onNodeWithText(banner).assertDoesNotExist()
        }

    @Test fun emptyQueueOffersDiscover() =
        runDesktopComposeUiTest(width = 650, height = 950) {
            state.value =
                UpNextUiState(
                    loaded = true,
                    context =
                        PlayContextInfo(
                            ContextType.EXTERNAL,
                            null,
                            "external",
                            FeedOrder.NEWEST_FIRST,
                            0,
                            MediaFilter.ALL,
                        ),
                )
            content()
            onNodeWithText("Discover").performClick()
            assertEquals(1, discovered)
            onNodeWithText("Stop after Up next").assertDoesNotExist()
            onNodeWithText("Clear Up next").assertDoesNotExist()
        }

    private fun ComposeUiTest.content(
        platform: PlatformKind = PlatformKind.DESKTOP,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        surface: UpNextSurface = UpNextSurface.DESTINATION,
    ) {
        setContent {
            CompositionLocalProvider(
                LocalUiClock provides TestClock(),
                LocalPlatformKind provides platform,
                LocalLayoutDirection provides layoutDirection,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    UpNextScreen(
                        state.value,
                        { actions += it },
                        { id, index -> moves += id to index },
                        { removals += it },
                        { cleared++ },
                        { stopped++ },
                        {},
                        { discovered++ },
                        {},
                        {},
                        surface = surface,
                    )
                }
            }
        }
    }
}
