// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `PodcastHeader` through `runComposeUiTest` (08 Podcast header): the description's "More"/"Less"
 * disclosure follows the laid-out overflow, not the newline count — a single long paragraph that
 * wraps past three lines still gets "More", and a short description shows no toggle.
 * `installFakeImageLoader` keeps the cover off the network.
 */
@OptIn(ExperimentalTestApi::class)
class PodcastHeaderTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun wrappingParagraphWithoutNewlinesOffersMore() =
        runDesktopComposeUiTest(width = 400, height = 640) {
            setHeader(
                testPodcastDetail(
                    description =
                        ShowNotes(
                            listOf(
                                ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text(LONG_PARAGRAPH))),
                            ),
                        ),
                ),
            )
            onNodeWithText("More").assertIsDisplayed().performClick()
            onNodeWithText("Less").assertIsDisplayed()
            onNodeWithText(LONG_PARAGRAPH, substring = true).assertIsDisplayed()
        }

    @Test
    fun shortDescriptionHidesTheToggle() =
        runDesktopComposeUiTest(width = 400, height = 640) {
            setHeader(
                testPodcastDetail(
                    description =
                        ShowNotes(
                            listOf(
                                ShowNoteBlock.Paragraph(listOf(ShowNoteSpan.Text("One line."))),
                            ),
                        ),
                ),
            )
            onNodeWithText("One line.").assertIsDisplayed()
            onNodeWithText("More").assertDoesNotExist()
        }

    @Test
    fun groupPlatesKeep48DpTargetsForOpenAndRemove() =
        runDesktopComposeUiTest(width = 400, height = 640) {
            setHeader(testPodcastDetail(), listOf(NEWS))
            onNodeWithText("News").assertTargetIsAtLeast48Dp()
            onNodeWithContentDescription("Remove from News").assertTargetIsAtLeast48Dp()
        }

    @Test
    fun aOneLetterGroupPlateStillOpensFromA48DpTarget() =
        runDesktopComposeUiTest(width = 400, height = 640) {
            setHeader(testPodcastDetail(), listOf(NEWS.copy(name = "N")))
            onNodeWithText("N").assertTargetIsAtLeast48Dp()
        }

    private fun SemanticsNodeInteraction.assertTargetIsAtLeast48Dp() {
        val node = fetchSemanticsNode()
        val minimum = with(node.layoutInfo.density) { 48.dp.toPx() }
        assertTrue(node.size.width >= minimum && node.size.height >= minimum, "Click target: ${node.size}")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setHeader(
        detail: PodcastDetail,
        groups: List<Group> = emptyList(),
    ) {
        setContent {
            NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                PodcastHeader(
                    detail = detail,
                    nowMs = TestClock.DEFAULT_NOW,
                    groups = groups,
                    onRemoveGroup = {},
                    onAddToGroups = {},
                )
            }
        }
        waitForIdle()
    }

    private companion object {
        val NEWS =
            Group(
                id = 1,
                uuid = "news",
                name = "News",
                orderKey = "a",
                colorArgb = null,
                iconKey = null,
                feedOrder = FeedOrder.NEWEST_FIRST,
                playOrder = FeedOrder.NEWEST_FIRST,
                filterFlags = 0,
                mediaFilter = MediaFilter.ALL,
                hideOlderThanDays = null,
                showAsTab = false,
                lastViewedAt = null,
                createdAt = 0,
                memberCount = 1,
            )

        /** No newlines: past three visual lines only by wrapping at a compact width. */
        const val LONG_PARAGRAPH =
            "A show about distributed systems and the people who build them. " +
                "Each week the hosts read a paper, trace one outage, and argue about " +
                "whether the fix belongs in the protocol or the pager. Episodes run " +
                "long, the references run longer, and the show notes carry the bibliography."
    }
}
