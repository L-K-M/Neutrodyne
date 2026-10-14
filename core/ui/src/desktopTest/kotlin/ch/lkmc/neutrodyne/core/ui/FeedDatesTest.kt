// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `FeedDates.duration` rendering (08 EpisodeRow meta): a positive sub-minute duration must not
 * floor to "0 min" — it renders the localised "<1 min"; zero, negative and unknown inputs keep
 * their existing outputs.
 */
@OptIn(ExperimentalTestApi::class)
class FeedDatesTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        // Compose resources resolve in the JVM default locale — pin English for string asserts.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun durationBoundariesRender() =
        runDesktopComposeUiTest(width = 300, height = 400) {
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    Column {
                        Text(FeedDates.duration(null).asString(), Modifier.testTag("unknown"))
                        Text(FeedDates.duration(-1L).asString(), Modifier.testTag("negative"))
                        Text(FeedDates.duration(0L).asString(), Modifier.testTag("zero"))
                        Text(FeedDates.duration(1L).asString(), Modifier.testTag("oneMs"))
                        Text(FeedDates.duration(59_999L).asString(), Modifier.testTag("underMin"))
                        Text(FeedDates.duration(60_000L).asString(), Modifier.testTag("oneMin"))
                        Text(FeedDates.duration(3_660_000L).asString(), Modifier.testTag("hourMin"))
                    }
                }
            }
            waitForIdle()

            assertEquals("", textOf("unknown"))
            assertEquals("", textOf("negative"))
            assertEquals("0 min", textOf("zero"))
            assertEquals("<1 min", textOf("oneMs"))
            assertEquals("<1 min", textOf("underMin"))
            assertEquals("1 min", textOf("oneMin"))
            assertEquals("1 h 1 min", textOf("hourMin"))
        }

    private fun ComposeUiTest.textOf(tag: String): String =
        onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
            .getOrElseNullable(SemanticsProperties.Text) { emptyList() }
            ?.firstOrNull()
            ?.text
            .orEmpty()
}
