// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.radio_elapsed_full
import ch.lkmc.neutrodyne.core.ui.resources.radio_paused
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RadioInteractionTest {
    @Test
    fun swipeCommitsOnlyOnReleaseAtExactThresholdAndUndoRestores() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var state by mutableStateOf(radioFixture())
            var discarded: Long? = null
            val original = state
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    RadioScreen(
                        state,
                        RadioActions(discard = { id ->
                            discarded = id
                            state =
                                state.copy(
                                    episodes = state.episodes.filterNot { it.id == id },
                                    removed = state.episodes.first { it.id == id },
                                )
                        }, undo = { state = original }),
                    )
                }
            }
            val row = onNodeWithTag("radio_episode_1")
            row.performTouchInput {
                down(Offset(width - 10f, height / 2f))
                moveBy(Offset(-80f, 0f))
            }
            assertEquals(null, discarded)
            row.performTouchInput { up() }
            waitForIdle()
            assertEquals(1L, discarded)
            onNodeWithTag("radio_episode_1").assertDoesNotExist()
            onNodeWithTag("radio_undo").performClick()
            onNodeWithTag("radio_episode_1").assertIsDisplayed()
        }

    @Test
    fun belowThresholdAndVerticalScrollDoNotDiscardOrSelect() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var discards = 0
            var selections = 0
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    RadioScreen(
                        radioFixture(),
                        RadioActions(discard = { discards++ }, selectEpisode = { selections++ }),
                    )
                }
            }
            onNodeWithTag("radio_episode_2").performTouchInput {
                down(center)
                moveBy(Offset(-79f, 0f))
                up()
            }
            onNodeWithTag("radio_episode_2").performTouchInput {
                down(center)
                moveBy(Offset(-10f, -45f))
                up()
            }
            assertEquals(0, discards)
            assertEquals(0, selections)
        }

    @Test fun savedResumeIsPausedAndElapsedAccessibilityIsUncapped() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val fixture =
                radioFixture().let {
                    it.copy(
                        episodes = listOf(it.episodes.first().copy(durationMs = 54_000_000)),
                        elapsedMs = 43_200_000,
                    )
                }
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    RadioScreen(fixture, RadioActions())
                }
            }
            onNodeWithText(getString(Res.string.radio_paused)).assertIsDisplayed()
            onNodeWithContentDescription(getString(Res.string.radio_elapsed_full, 12L, 0L, 0L)).assertIsDisplayed()
        }

    @Test fun insertDraftSurvivesColumnSwitchAndRecreation() =
        runDesktopComposeUiTest(width = 700, height = 900) {
            var width by mutableIntStateOf(390)
            var visible by mutableStateOf(true)
            var saved: Map<String, List<Any?>>? = null
            var registry: SaveableStateRegistry? = null
            setContent {
                if (visible) {
                    val current = remember { SaveableStateRegistry(saved) { true } }
                    registry = current
                    CompositionLocalProvider(LocalSaveableStateRegistry provides current) {
                        NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                            Box(Modifier.width(width.dp).fillMaxHeight()) {
                                RadioScreen(radioFixture(), RadioActions())
                            }
                        }
                    }
                }
            }
            onNodeWithTag("radio_new_playlist").performClick()
            onNodeWithTag("radio_name").performTextInput("Evening")
            onAllNodes(isToggleable()).onFirst().performClick()
            runOnIdle { width = 700 }
            onNodeWithTag("radio_name").assert(hasText("Evening"))
            onAllNodes(isToggleable()).onFirst().assertIsOn()
            runOnIdle {
                saved = registry!!.performSave()
                visible = false
            }
            runOnIdle { visible = true }
            onNodeWithTag("radio_name").assert(hasText("Evening")).assertIsFocused()
            onAllNodes(isToggleable()).onFirst().assertIsOn()
            onNodeWithTag("radio_create").assertIsEnabled()
        }

    @Test fun pagingFailureOffersRetryAndRetainsTheProgramme() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var retries = 0
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture().copy(loadError = true), RadioActions(retry = { retries++ }))
                }
            }
            onNodeWithTag("radio_episode_1").assertIsDisplayed()
            onNodeWithTag("radio_retry").performClick()
            assertEquals(1, retries)
        }

    @Test fun resizeCancelsArmedSwipe() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var width by mutableIntStateOf(390)
            var discards = 0
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    Box(Modifier.width(width.dp).fillMaxHeight()) {
                        RadioScreen(radioFixture(), RadioActions(discard = { discards++ }))
                    }
                }
            }
            onNodeWithTag("radio_episode_1").performTouchInput {
                down(center)
                moveBy(Offset(-100f, 0f))
            }
            runOnIdle { width = 320 }
            onNodeWithTag("radio_episode_1").performTouchInput { up() }
            assertEquals(0, discards)
        }

    @Test fun focusedDeleteAndInsertEscapeRestoreFocus() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var removed: Long? = null
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture(), RadioActions(discard = { removed = it }))
                }
            }
            onNodeWithTag("radio_episode_2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("radio_episode_2").performKeyInput { pressKey(Key.Delete) }
            assertEquals(2L, removed)
            onNodeWithTag("radio_new_playlist").performClick()
            onNodeWithTag("radio_name").assertIsFocused()
            onNodeWithTag("radio_name").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("radio_insert").assertDoesNotExist()
            onNodeWithTag("radio_new_playlist").assertIsFocused()
        }
}
