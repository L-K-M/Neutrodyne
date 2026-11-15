// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 08 Continue on this device: one polite accessibility node with Play (click) and Dismiss. Dismiss
 * is the standard action because Compose Desktop does not expose custom actions to screen readers.
 */
@OptIn(ExperimentalTestApi::class)
class ContinueOnThisDeviceCardTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun englishLabels() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun theCardIsOnePoliteNodeWithPlayAndDismiss() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            val calls = mutableListOf<String>()
            showCard(CARD, calls)

            val node = onNodeWithContentDescription(DESCRIPTION).fetchSemanticsNode()
            assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
            assertEquals("Play", node.config.getOrNull(SemanticsActions.OnClick)?.label)
            assertEquals("Dismiss", node.config.getOrNull(SemanticsActions.Dismiss)?.label)
            assertNull(node.config.getOrNull(SemanticsActions.CustomActions))
            // The texts and buttons draw but expose no nodes of their own to accessibility services.
            for (text in listOf("Continue on this device", "Episode 7 · 23:14 · from Pixel 8", "Play", "Dismiss")) {
                onAllNodesWithText(text).assertCountEquals(0)
            }

            onNodeWithContentDescription(DESCRIPTION).performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithContentDescription(DESCRIPTION).performSemanticsAction(SemanticsActions.Dismiss)
            assertEquals(listOf("continue", "dismiss"), calls)
        }

    @Test
    fun aTapAnywhereOnTheCardContinuesHere() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            val calls = mutableListOf<String>()
            showCard(CARD, calls)
            onNodeWithContentDescription(DESCRIPTION).performClick()
            assertEquals(listOf("continue"), calls)
        }

    @Test
    fun thePositionIsSpokenInWordsWithoutZeroParts() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            showCard(CARD.copy(positionMs = 3_723_000), mutableListOf())
            onNode(described("at 1 hour 2 minutes 3 seconds,")).assertExists()
        }

    @Test
    fun wholeMinutesAndTheStartReadNaturally() =
        runDesktopComposeUiTest(width = 360, height = 300) {
            var card by mutableStateOf(CARD.copy(positionMs = 120_000))
            showCard({ card }, mutableListOf())
            onNode(described("at 2 minutes,")).assertExists()
            runOnIdle { card = CARD.copy(positionMs = 400) }
            onNode(described("at 0 seconds,")).assertExists()
        }

    private fun described(fragment: String): SemanticsMatcher =
        SemanticsMatcher("content description contains '$fragment'") { node ->
            node.config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
                .any { fragment in it }
        }

    private fun ComposeUiTest.showCard(
        card: RemoteSessionCard,
        calls: MutableList<String>,
    ) = showCard({ card }, calls)

    private fun ComposeUiTest.showCard(
        card: () -> RemoteSessionCard,
        calls: MutableList<String>,
    ) {
        val actions =
            RootActions(
                retryStartup = {},
                dismissNotice = {},
                continueHere = { calls += "continue" },
                dismissRemoteSession = { calls += "dismiss" },
                playbackKey = { false },
            )
        setContent {
            MaterialTheme {
                ContinueOnThisDeviceCard(card(), actions)
            }
        }
    }

    private companion object {
        val CARD =
            RemoteSessionCard(
                title = "Episode 7",
                podcastTitle = "Show",
                positionText = "23:14",
                positionMs = 1_394_000,
                deviceName = "Pixel 8",
            )
        const val DESCRIPTION = "Continue on this device: Episode 7 at 23 minutes 14 seconds, from Pixel 8"
    }
}
