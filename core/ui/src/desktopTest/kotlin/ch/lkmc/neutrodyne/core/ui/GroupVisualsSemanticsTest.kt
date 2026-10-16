// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Semantics contract of the M2 group visuals (08): the mosaic's merged node reads
 * "{name}, {n} podcasts, {u} unplayed, {k} new", and the tab label reports `Role.Tab` plus the
 * selected state. Tests run under the suite's de_DE locale — assertions match the
 * locale-independent parts (name, counts, role) rather than translated words.
 */
@OptIn(ExperimentalTestApi::class)
class GroupVisualsSemanticsTest {
    @Test
    fun mosaicDescriptionCarriesNamePodcastAndCounts() =
        runDesktopComposeUiTest(width = 360, height = 560) {
            installFakeImageLoader()
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    GroupMosaic(groupFixture(memberCount = 5), mosaicMembers(4), unplayedCount = 14, newCount = 3)
                }
            }
            waitForIdle()

            val node =
                onNode(
                    SemanticsMatcher("describes group") { it.config.contains(SemanticsProperties.ContentDescription) },
                ).fetchSemanticsNode()
            val description = node.config[SemanticsProperties.ContentDescription].joinToString(" ")
            assertTrue(description.startsWith("Tech"), "description starts with the name: $description")
            for (part in listOf("5", "14", "3")) {
                assertTrue(description.contains(part), "description misses $part: $description")
            }
        }

    @Test
    fun thumbnailHasNoFabricatedCountsOrTileCaption() =
        runDesktopComposeUiTest(width = 360, height = 160) {
            installFakeImageLoader()
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    GroupMosaic(
                        groupFixture(),
                        mosaicMembers(2),
                        0,
                        0,
                        modifier = Modifier.size(48.dp).testTag("groupThumbnail"),
                        layout = GroupMosaicLayout.THUMBNAIL,
                    )
                }
            }
            onNodeWithTag("groupThumbnail").assertIsDisplayed()
            onNode(hasContentDescription("Tech", substring = true)).assertDoesNotExist()
            onNodeWithText("Tech", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun mosaicHiddenGroupKeepsDescriptiveLabel() =
        runDesktopComposeUiTest(width = 360, height = 560) {
            installFakeImageLoader()
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    GroupMosaic(
                        groupFixture(showAsTab = false, iconKey = "future_icon"),
                        mosaicMembers(2),
                        unplayedCount = 0,
                        newCount = 0,
                    )
                }
            }
            waitForIdle()

            // Unknown well-formed icon keys must not yield a missing or misleading label.
            onNode(hasContentDescription("Tech", substring = true)).assertIsDisplayed()
        }

    @Test
    fun tabLabelExposesTabRoleSelectedStateAndCounts() =
        runDesktopComposeUiTest(width = 360, height = 160) {
            installFakeImageLoader()
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    GroupTabLabel(groupFixture(), unplayedCount = 9, newCount = 4, selected = true)
                }
            }
            waitForIdle()

            val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
            onNode(tab).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            onNode(tab).assert(hasContentDescription("Tech", substring = true))
            onNodeWithText("9").assertIsDisplayed()
            onNodeWithText("4").assertIsDisplayed()
        }

    @Test
    fun tabLabelHidesZeroCountsAndUnselects() =
        runDesktopComposeUiTest(width = 360, height = 160) {
            installFakeImageLoader()
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    GroupTabLabel(groupFixture(), unplayedCount = 0, newCount = 0, selected = false)
                }
            }
            waitForIdle()

            val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
            onNode(tab).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            onNodeWithText("0").assertDoesNotExist()
        }
}
