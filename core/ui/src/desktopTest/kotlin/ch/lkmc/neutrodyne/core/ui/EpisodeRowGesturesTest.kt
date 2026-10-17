// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.SwipeAction
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EpisodeRowGesturesTest {
    @BeforeTest
    fun images() {
        installFakeImageLoader()
    }

    @Test
    fun modifiedClicksSelectAndExtendWithoutOpening() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setRow(actions)
            for (key in listOf(Key.CtrlLeft, Key.MetaLeft, Key.ShiftLeft)) {
                onNodeWithTag("row").performMultiModalInput {
                    key { keyDown(key) }
                    mouse { click(centerLeft) }
                    key { keyUp(key) }
                }
            }
            assertEquals(
                listOf<EpisodeAction>(
                    EpisodeAction.Select(1),
                    EpisodeAction.Select(1),
                    EpisodeAction.ExtendSelection(1),
                ),
                actions,
            )
        }

    @Test
    fun secondaryClickOpensMenuWithoutOpeningOrSelecting() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setRow(actions)
            onNodeWithTag("row").performMouseInput { rightClick(centerLeft) }
            onNodeWithText("Select").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.Select(1)), actions)
        }

    @Test
    fun keyboardContextMenusAndEnterUseTheFocusedRow() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setRow(actions)
            onNodeWithTag("row").performMouseInput { click(centerLeft) }
            actions.clear()
            onNodeWithTag("row").performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.F10)
                keyUp(Key.ShiftLeft)
            }
            onNodeWithText("Select").performClick()
            onNodeWithTag("row").performKeyInput { pressKey(Key.Menu) }
            onNodeWithText("Select").performClick()
            onNodeWithTag("row").performKeyInput { pressKey(Key.Enter) }
            assertEquals(
                listOf<EpisodeAction>(EpisodeAction.Select(1), EpisodeAction.Select(1), EpisodeAction.Open(1)),
                actions,
            )
        }

    @Test
    fun selectionModeClickTogglesButEnterOpens() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setRow(actions, selected = false)
            onNodeWithTag("row").performMouseInput { click(centerLeft) }
            onNodeWithTag("row").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("row").performKeyInput { pressKey(Key.NumPadEnter) }
            assertEquals(
                listOf<EpisodeAction>(EpisodeAction.Select(1), EpisodeAction.Open(1), EpisodeAction.Open(1)),
                actions,
            )
        }

    @Test
    fun focusedChildKeepsItsEnterActivationInSelectionMode() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setRow(actions, selected = false, caps = RowCaps.FULL.copy(downloads = false))
            onNodeWithTag("row").performMouseInput { click(centerLeft) }
            actions.clear()
            onNodeWithTag("row").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("row").performKeyInput { pressKey(Key.Enter) }
            assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayToggle(1)), actions)
            onNodeWithTag("row").performKeyInput {
                pressKey(Key.Tab)
                pressKey(Key.Enter)
            }
            onNodeWithText("Select").performClick()
            assertEquals(listOf<EpisodeAction>(EpisodeAction.PlayToggle(1), EpisodeAction.Select(1)), actions)
        }

    @Test
    fun desktopIgnoresSwipeCapabilities() =
        runComposeUiTest {
            val actions = mutableListOf<EpisodeAction>()
            setRow(actions)
            onNodeWithTag("row").performTouchInput { swipeLeft() }
            assertTrue(actions.none { it is EpisodeAction.SetPlayed })
        }

    private fun ComposeUiTest.setRow(
        actions: MutableList<EpisodeAction>,
        selected: Boolean? = null,
        caps: RowCaps = RowCaps.FULL.copy(swipe = SwipeConfig(SwipeAction.MARK_PLAYED, SwipeAction.MARK_PLAYED)),
    ) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            setContent {
                CompositionLocalProvider(LocalPlatformKind provides PlatformKind.DESKTOP) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        EpisodeRow(
                            testEpisodeRow(1),
                            null,
                            EpisodeRowStyle.FEED,
                            caps,
                            highlightNew = false,
                            selected = selected,
                            onAction = { actions += it },
                            modifier = Modifier.testTag("row"),
                        )
                    }
                }
            }
            waitForIdle()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
