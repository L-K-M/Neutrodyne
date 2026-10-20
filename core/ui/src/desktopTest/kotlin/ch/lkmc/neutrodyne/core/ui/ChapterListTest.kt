// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ChapterListTest {
    @Test fun highlightsCurrentAndSeeksExactStart() =
        runComposeUiTest {
            val first = EpisodeChapter(0, 754_000, "Opening", null, null, ChapterSource.PSC, false)
            val current =
                first.copy(
                    startMs = 754_000,
                    endMs = null,
                    title = "Interview",
                    imageUrl = "https://example.test/chapter.png",
                )
            val seeks = mutableListOf<Long>()
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    ChapterList(listOf(first, current), CurrentChapter(9, 1, 2, current), seeks::add)
                }
            }
            onNodeWithText("Interview").assertIsSelected().performClick()
            onNodeWithText("Interview").performKeyInput { pressKey(Key.Enter) }
            assertEquals(listOf(754_000L, 754_000L), seeks)
        }
}
