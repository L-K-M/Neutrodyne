// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import coil3.test.FakeImage
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

/**
 * Desktop golden set for the M2 group visuals (09 Compose UI and screenshot tests; 08's
 * screenshot matrix): `GroupMosaic` at 0–4 members in light and dark plus long/emoji names and
 * the hidden-group marker, and `GroupTabLabel` at counts 0 / 9 / 99+ with long/emoji names.
 * Goldens live in `core/ui/screenshots/desktop/`; record via `recordRoborazziDesktop`.
 *
 * Determinism: fixed 1× density window, `NeutrodyneTheme` with `dynamicColor = false`, and
 * `installFakeImageLoader` answering each member's `ArtworkRef` with a fixed-colour `FakeImage`.
 */
@OptIn(ExperimentalTestApi::class)
class GroupVisualsScreenshotTest {
    private fun capture(
        name: String,
        dark: Boolean = false,
        height: Int = 560,
        content: @Composable () -> Unit,
    ) = runDesktopComposeUiTest(width = 360, height = height) {
        setContent {
            NeutrodyneTheme(
                AppearancePrefs(dynamicColor = false),
                SystemUiState(dark = dark, contrast = 0.0, reducedMotion = true),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) { content() }
            }
        }
        waitForIdle()
        onRoot().captureRoboImage("screenshots/desktop/$name.png")
    }

    @Test
    fun mosaicMembersZeroToFourLight() {
        installFakeImageLoader(mosaicImages())
        for (n in 0..4) {
            capture("groupmosaic_${n}_light") {
                GroupMosaic(
                    group = groupFixture(),
                    members = mosaicMembers(n),
                    unplayedCount = 14,
                    newCount = 3,
                )
            }
        }
    }

    @Test
    fun mosaicDark() {
        installFakeImageLoader(mosaicImages())
        capture("groupmosaic_4_dark", dark = true) {
            GroupMosaic(groupFixture(), mosaicMembers(4), unplayedCount = 14, newCount = 3)
        }
        capture("groupmosaic_0_dark", dark = true) {
            GroupMosaic(groupFixture(memberCount = 0), emptyList(), unplayedCount = 0, newCount = 0)
        }
    }

    @Test
    fun mosaicLongAndEmojiNames() {
        installFakeImageLoader(mosaicImages())
        capture("groupmosaic_longname") {
            GroupMosaic(
                group = groupFixture(name = "Sehr langer Gruppenname der nicht in die Zeile passt"),
                members = mosaicMembers(4),
                unplayedCount = 14,
                newCount = 3,
            )
        }
        capture("groupmosaic_emoji") {
            GroupMosaic(
                group = groupFixture(name = "🎧 Podcasts 🎙️🔥"),
                members = mosaicMembers(4),
                unplayedCount = 14,
                newCount = 3,
            )
        }
    }

    @Test
    fun mosaicHiddenAndFallbackStates() {
        installFakeImageLoader(mosaicImages())
        capture("groupmosaic_hidden") {
            GroupMosaic(
                group = groupFixture(showAsTab = false),
                members = mosaicMembers(4),
                unplayedCount = 14,
                newCount = 3,
            )
        }
        // No mapped icon and no artwork: the name grapheme fills the tone surface.
        capture("groupmosaic_grapheme") {
            GroupMosaic(
                group = groupFixture(name = "Nachtschicht", iconKey = null, memberCount = 0),
                members = emptyList(),
                unplayedCount = 0,
                newCount = 0,
            )
        }
    }

    @Test
    fun tabLabelStates() {
        installFakeImageLoader(mosaicImages())
        capture("grouptab_counts0", height = 160) {
            GroupTabLabel(groupFixture(), unplayedCount = 0, newCount = 0, selected = true)
        }
        capture("grouptab_count9", height = 160) {
            GroupTabLabel(groupFixture(), unplayedCount = 9, newCount = 4, selected = true)
        }
        capture("grouptab_count99plus", height = 160) {
            GroupTabLabel(groupFixture(), unplayedCount = 142, newCount = 130, selected = false)
        }
        capture("grouptab_longname", height = 160) {
            GroupTabLabel(
                groupFixture(name = "Sehr langer Gruppenname der nicht in die Zeile passt"),
                unplayedCount = 9,
                newCount = 4,
                selected = false,
            )
        }
        capture("grouptab_emoji", height = 160) {
            GroupTabLabel(
                groupFixture(name = "🎧 Podcasts 🎙️🔥"),
                unplayedCount = 9,
                newCount = 4,
                selected = true,
            )
        }
        capture("grouptab_dot", height = 160) {
            GroupTabLabel(
                groupFixture(iconKey = null),
                unplayedCount = 9,
                newCount = 4,
                selected = true,
            )
        }
        capture("grouptab_dark", dark = true, height = 160) {
            GroupTabLabel(groupFixture(), unplayedCount = 9, newCount = 4, selected = true)
        }
    }

    private companion object {
        /** Distinct fixed colours per member cover so the 2 × 2 layout is visible in goldens. */
        fun mosaicImages(): Map<Any, FakeImage> =
            mosaicMembers(4)
                .mapIndexed { i, member ->
                    member.artwork to FakeImage(96, 96, color = CELL_COLORS[i])
                }.toMap()

        val CELL_COLORS =
            listOf(
                0xFF3366CC.toInt(),
                0xFFDC3912.toInt(),
                0xFF109618.toInt(),
                0xFF990099.toInt(),
            )
    }
}
