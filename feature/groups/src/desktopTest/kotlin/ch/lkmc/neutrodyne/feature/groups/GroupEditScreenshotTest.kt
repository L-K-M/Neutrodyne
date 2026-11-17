// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import java.util.Locale

/**
 * Goldens of the radio group editor (05 Group edit, PO-55): a new group, an existing group with
 * its colour, icon, play order and members, a name error, and the editor while a save runs.
 */
@OptIn(ExperimentalTestApi::class)
class GroupEditScreenshotTest {
    @Test fun newGroup() = capture("new", GroupEditUiState(loaded = true, tiles = TILES), groupId = null)

    @Test fun existingGroup() = capture("existing", EXISTING, groupId = 1)

    @Test fun nameError() =
        capture(
            "name_error",
            GroupEditUiState(loaded = true, draft = GroupEditDraft(name = "News"), nameError = GroupError.NameTaken(2)),
            groupId = null,
        )

    @Test fun busy() = capture("busy", EXISTING.copy(busy = true), groupId = 1)

    private fun capture(
        name: String,
        state: GroupEditUiState,
        groupId: Long?,
    ) = runDesktopComposeUiTest(width = 360, height = 900) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
        try {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalPlatformKind provides PlatformKind.ANDROID) {
                    NeutrodyneTheme(
                        SystemUiState(dark = false, contrast = 0.0, reducedMotion = true),
                    ) {
                        GroupEditScreen(state, groupId, {}, {}, {}, {}, {}, {})
                    }
                }
            }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            onRoot().captureRoboImage("screenshots/desktop/group_edit_$name.png")
        } finally {
            Locale.setDefault(previous)
        }
    }

    private companion object {
        val TILES =
            listOf(
                testLibraryTile(7, displayTitle = "The Daily Wire Report"),
                testLibraryTile(8, displayTitle = "Field Notes"),
                testLibraryTile(9, displayTitle = "Night Owls"),
                testLibraryTile(10, displayTitle = "Slow Radio"),
            )
        val EXISTING =
            GroupEditUiState(
                loaded = true,
                tiles = TILES,
                draft =
                    GroupEditDraft(
                        name = "Audiobooks",
                        colorArgb = GroupPalette.COLORS[6],
                        iconKey = "memory",
                        playOrder = FeedOrder.OLDEST_FIRST,
                        showAsTab = false,
                        memberIds = setOf(7, 9),
                    ),
            )
    }
}
