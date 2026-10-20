// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import coil3.test.FakeImage
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Android golden set for the M2 group visuals (09; mirrors the desktop set in
 * `screenshots/desktop/`). Goldens live in `core/ui/screenshots/android/`; record via
 * `recordRoborazziAndroidHostTest`, verify with `-Proborazzi.test.verify=true`.
 *
 * `AndroidComposeTestRule.setContent` may run only once per test, so each golden gets
 * its own `@Test`; the helper captures the full root like the desktop suite does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupVisualsScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun fakeImages() = installFakeImageLoader(mosaicImages())

    private fun capture(
        name: String,
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
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
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("screenshots/android/$name.png")
    }

    private fun mosaicShot(
        name: String,
        members: Int,
        dark: Boolean = false,
    ) = capture(name, dark) {
        GroupMosaic(groupFixture(), mosaicMembers(members), unplayedCount = 14, newCount = 3)
    }

    @Test fun mosaic0Light() = mosaicShot("groupmosaic_0_light", 0)

    @Test fun mosaic1Light() = mosaicShot("groupmosaic_1_light", 1)

    @Test fun mosaic2Light() = mosaicShot("groupmosaic_2_light", 2)

    @Test fun mosaic3Light() = mosaicShot("groupmosaic_3_light", 3)

    @Test fun mosaic4Light() = mosaicShot("groupmosaic_4_light", 4)

    @Test fun mosaic4Dark() = mosaicShot("groupmosaic_4_dark", 4, dark = true)

    @Test
    fun suggestedGroupsCard() {
        var selected = emptySet<String>()
        var dismissed = false
        capture("suggested_groups") { SuggestedGroupsFixture({ selected = it }, { dismissed = true }) }
        rule.onNodeWithText("News (2)").assertIsSelected()
        rule.onNodeWithText("Technology (3)").performClick()
        rule.onNodeWithText("Create groups").performClick()
        assertEquals(setOf("News", "Technology"), selected)
        rule.onNodeWithText("Not now").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun noSuggestionsHideTheCard() {
        rule.setContent { SuggestedGroupsCard(emptyList(), emptySet(), false, {}, {}, {}) }
        rule.onNodeWithTag("suggested-groups").assertDoesNotExist()
    }

    @Test
    fun mosaic0Dark() =
        capture("groupmosaic_0_dark", dark = true) {
            GroupMosaic(groupFixture(memberCount = 0), emptyList(), unplayedCount = 0, newCount = 0)
        }

    @Test
    fun mosaicLongName() =
        capture("groupmosaic_longname") {
            GroupMosaic(
                group = groupFixture(name = "Sehr langer Gruppenname der nicht in die Zeile passt"),
                members = mosaicMembers(4),
                unplayedCount = 14,
                newCount = 3,
            )
        }

    @Test
    fun mosaicEmojiName() =
        capture("groupmosaic_emoji") {
            GroupMosaic(
                group = groupFixture(name = "🎧 Podcasts 🎙️🔥"),
                members = mosaicMembers(4),
                unplayedCount = 14,
                newCount = 3,
            )
        }

    @Test
    fun mosaicHidden() =
        capture("groupmosaic_hidden") {
            GroupMosaic(
                group = groupFixture(showAsTab = false),
                members = mosaicMembers(4),
                unplayedCount = 14,
                newCount = 3,
            )
        }

    @Test
    fun mosaicGrapheme() =
        capture("groupmosaic_grapheme") {
            GroupMosaic(
                group = groupFixture(name = "Nachtschicht", iconKey = null, memberCount = 0),
                members = emptyList(),
                unplayedCount = 0,
                newCount = 0,
            )
        }

    @Test
    fun tabCounts0() =
        capture("grouptab_counts0") {
            GroupTabLabel(groupFixture(), unplayedCount = 0, newCount = 0, selected = true)
        }

    @Test
    fun tabCount9() =
        capture("grouptab_count9") {
            GroupTabLabel(groupFixture(), unplayedCount = 9, newCount = 4, selected = true)
        }

    @Test
    fun tabCount99Plus() =
        capture("grouptab_count99plus") {
            GroupTabLabel(groupFixture(), unplayedCount = 142, newCount = 130, selected = false)
        }

    @Test
    fun tabLongName() =
        capture("grouptab_longname") {
            GroupTabLabel(
                groupFixture(name = "Sehr langer Gruppenname der nicht in die Zeile passt"),
                unplayedCount = 9,
                newCount = 4,
                selected = false,
            )
        }

    @Test
    fun tabEmojiName() =
        capture("grouptab_emoji") {
            GroupTabLabel(
                groupFixture(name = "🎧 Podcasts 🎙️🔥"),
                unplayedCount = 9,
                newCount = 4,
                selected = true,
            )
        }

    @Test
    fun tabDot() =
        capture("grouptab_dot") {
            GroupTabLabel(groupFixture(iconKey = null), unplayedCount = 9, newCount = 4, selected = true)
        }

    @Test
    fun tabDark() =
        capture("grouptab_dark", dark = true) {
            GroupTabLabel(groupFixture(), unplayedCount = 9, newCount = 4, selected = true)
        }

    private companion object {
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
