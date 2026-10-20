// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SleepTimerSheetTest {
    private var previousLocale = Locale.ENGLISH

    @BeforeTest fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restore() {
        Locale.setDefault(previousLocale)
    }

    @Test fun lastChoiceCustomBoundsAndEndOfEpisode() =
        runComposeUiTest {
            val calls = mutableListOf<SleepTimerMode>()
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SleepTimerSheet(SleepTimerState.Off, 45, true, calls::add, {}, {})
                }
            }
            onNodeWithText("45 min").assertIsSelected().performClick()
            onNodeWithText("Custom…").performClick()
            for (invalid in listOf("0", "241", "-1", "999999999999", "abc", "")) {
                onNodeWithText("Minutes (1–240)").performTextReplacement(invalid)
                onNodeWithText("Start timer").assertIsNotEnabled()
            }
            for (valid in listOf(1, 240)) {
                onNodeWithText("Minutes (1–240)").performTextReplacement(valid.toString())
                onNodeWithText("Start timer").performClick()
            }
            onNodeWithText("End of episode").performClick()
            onNodeWithText("Turn off").performClick()
            assertEquals(
                listOf(
                    SleepTimerMode.Minutes(45),
                    SleepTimerMode.Minutes(1),
                    SleepTimerMode.Minutes(240),
                    SleepTimerMode.EndOfEpisode,
                    SleepTimerMode.Off,
                ),
                calls,
            )
        }

    @Test fun runningPausedExtensionLiveStateAndServiceLoss() =
        runComposeUiTest {
            val timer = mutableStateOf<SleepTimerState>(SleepTimerState.Running(1_421_000, 1_800_000, false, false))
            val available = mutableStateOf(true)
            val extensions = mutableListOf<Int>()
            var dismisses = 0
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SleepTimerSheet(timer.value, 30, available.value, {}, extensions::add, { dismisses++ })
                }
            }
            onNodeWithText("23:41").assertIsDisplayed()
            onNodeWithText("Waiting while paused").assertIsDisplayed()
            onNodeWithText("+5 min").performClick()
            onNodeWithText("+15 min").performClick()
            assertEquals(listOf(5, 15), extensions)
            runOnIdle { timer.value = SleepTimerState.EndOfEpisode(1) }
            onNodeWithText("Stops at the end of this episode").assertIsDisplayed()
            runOnIdle { available.value = false }
            waitForIdle()
            assertEquals(1, dismisses)
        }
}
