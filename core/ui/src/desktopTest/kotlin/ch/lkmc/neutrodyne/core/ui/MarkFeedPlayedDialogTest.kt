// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class MarkFeedPlayedDialogTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun optionsCountAndStaleConfirmationUseFrozenSelection() =
        runComposeUiTest {
            val clock = TestClock()
            var actualCount = 2
            var writes = 0
            val cutoffs = mutableListOf<Long?>()
            lateinit var controller: MarkFeedPlayedController
            setContent {
                val scope = rememberCoroutineScope()
                androidx.compose.runtime.remember {
                    MarkFeedPlayedController(scope, clock, { _, before ->
                        cutoffs += before
                        actualCount
                    }) { source, before, expected ->
                        assertEquals(FeedSource.Group(7), source)
                        assertEquals(cutoffs.last(), before)
                        if (expected != actualCount) {
                            MarkPlayedOutcome.SelectionChanged(actualCount)
                        } else {
                            writes++
                            MarkPlayedOutcome.Played
                        }
                    }.also {
                        controller = it
                        it.open(FeedSource.Group(7), UiText.Raw("tech"))
                    }
                }
                val state by controller.state.collectAsState()
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    state?.let {
                        MarkFeedPlayedDialog(it, controller::select, controller::confirm, controller::dismiss)
                    }
                }
            }
            waitForIdle()
            onNodeWithText("All").assertIsSelected()
            onNodeWithText("Mark 2 episodes in 'tech' as played?").assertIsDisplayed()
            for (option in listOf("Older than 1 week", "Older than 1 month", "Older than 3 months")) {
                onNodeWithText(option).performClick().assertIsSelected()
                onNodeWithText("Mark 2 episodes in 'tech' as played?").assertIsDisplayed()
            }
            runOnIdle {
                actualCount = 1
                clock.nowMs += 86_400_000
            }
            onNodeWithText("Mark as played").performClick()
            onNodeWithText("The selection changed. Check the new count and confirm again.").assertIsDisplayed()
            onNodeWithText("Mark 1 episode in 'tech' as played?").assertIsDisplayed()
            assertEquals(0, writes)
            onNodeWithText("Mark as played").performClick()
            runOnIdle {
                assertEquals(1, writes)
                assertNull(controller.state.value)
            }
        }

    @Test
    fun zeroCountAndCountingHaveNoConfirmAction() =
        runComposeUiTest {
            var state by androidx.compose.runtime.mutableStateOf(MarkFeedPlayedState(FeedSource.All, UiText.Raw("All")))
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    MarkFeedPlayedDialog(state, {}, { error("Must not confirm") }, {})
                }
            }
            waitForIdle()
            onNodeWithText("Counting episodes…").assertIsDisplayed()
            onNodeWithText("Mark as played").assertDoesNotExist()
            runOnIdle { state = state.copy(count = 0, phase = MarkPlayedPhase.READY) }
            onNodeWithText("Mark 0 episodes in 'All' as played?").assertIsDisplayed()
            onNodeWithText("Mark as played").assertDoesNotExist()
            onNodeWithText("Cancel").assertIsDisplayed()
        }

    @Test
    fun germanCountsUseSingularAndPlural() =
        runComposeUiTest {
            Locale.setDefault(Locale.GERMAN)
            var state by androidx.compose.runtime.mutableStateOf(
                MarkFeedPlayedState(FeedSource.Group(7), UiText.Raw("tech"), count = 1, phase = MarkPlayedPhase.READY),
            )
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    MarkFeedPlayedDialog(state, {}, {}, {})
                }
            }
            waitForIdle()
            onNodeWithText("1 Folge in 'tech' als gespielt markieren?").assertIsDisplayed()
            runOnIdle { state = state.copy(count = 2) }
            onNodeWithText("2 Folgen in 'tech' als gespielt markieren?").assertIsDisplayed()
        }
}
