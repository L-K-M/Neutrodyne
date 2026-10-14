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
 * `UiText.Joined` (08's sentence-style summaries): the closing suffix belongs to the joined
 * sentence — an empty join stays empty rather than emitting a lone "." — in both the composable
 * [asString] and the suspending [UiText.resolve] paths.
 */
@OptIn(ExperimentalTestApi::class)
class UiTextTest {
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
    fun joinedAppendsTheSuffixOnlyToJoinedTextInComposition() =
        runDesktopComposeUiTest(width = 300, height = 300) {
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    Column {
                        Text(UiText.Joined(emptyList()).asString(), Modifier.testTag("empty"))
                        Text(
                            UiText.Joined(listOf(UiText.Raw("")), suffix = "!").asString(),
                            Modifier.testTag("blank"),
                        )
                        Text(
                            UiText.Joined(listOf(UiText.Raw("A"), UiText.Raw("B"))).asString(),
                            Modifier.testTag("pair"),
                        )
                        Text(
                            UiText
                                .Joined(
                                    listOf(UiText.Raw("A"), UiText.Raw("B")),
                                    separator = " · ",
                                    suffix = "",
                                ).asString(),
                            Modifier.testTag("flat"),
                        )
                    }
                }
            }
            waitForIdle()

            assertEquals("", textOf("empty"))
            assertEquals("", textOf("blank"))
            assertEquals("A. B.", textOf("pair"))
            assertEquals("A · B", textOf("flat"))
        }

    @Test
    fun joinedAppendsTheSuffixOnlyToJoinedTextWhenResolving() =
        runDesktopComposeUiTest(width = 300, height = 300) {
            assertEquals("", UiText.Joined(emptyList()).resolve())
            assertEquals("", UiText.Joined(listOf(UiText.Raw("")), suffix = "!").resolve())
            assertEquals(
                "A. B.",
                UiText.Joined(listOf(UiText.Raw("A"), UiText.Raw("B"))).resolve(),
            )
            assertEquals(
                "A · B",
                UiText
                    .Joined(
                        listOf(UiText.Raw("A"), UiText.Raw("B")),
                        separator = " · ",
                        suffix = "",
                    ).resolve(),
            )
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
