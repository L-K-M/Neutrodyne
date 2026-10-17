// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.SwipeAction
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GestureSettingsTest {
    private lateinit var previous: Locale

    @BeforeTest fun english() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restore() {
        Locale.setDefault(previous)
    }

    @Test
    fun androidRowsWriteSettingsAndHideUndeliveredChoices() =
        runComposeUiTest {
            val settings = FakeSettingsRepository()
            setAppearance(settings, PlatformKind.ANDROID)
            onNodeWithText("Swipe episodes in Feeds").performScrollTo().performClick()
            waitForIdle()
            assertEquals(true, runBlocking { settings.get(AppearanceSettingKeys.FEEDS_ROW_SWIPE) })
            assertEquals(
                SwipeAction.ADD_UP_NEXT,
                runBlocking { settings.get(AppearanceSettingKeys.SWIPE_START_ACTION) },
            )
            onNodeWithText("Swipe right").performScrollTo().performClick()
            onNodeWithText("Add to Up next").assertDoesNotExist()
            onNodeWithText("Download").assertDoesNotExist()
            onAllNodesWithText("Mark played / unplayed").onLast().performClick()
            waitForIdle()
            assertEquals(
                SwipeAction.MARK_PLAYED,
                runBlocking { settings.get(AppearanceSettingKeys.SWIPE_START_ACTION) },
            )
            onNodeWithText("Swipe left").performScrollTo().performClick()
            onAllNodesWithText("None").onLast().performClick()
            waitForIdle()
            assertEquals(
                SwipeAction.NONE,
                runBlocking { settings.get(AppearanceSettingKeys.SWIPE_END_ACTION) },
            )
        }

    @Test
    fun rtlLabelsFollowLogicalSwipeDirection() =
        runComposeUiTest {
            val settings = FakeSettingsRepository()
            setAppearance(settings, PlatformKind.ANDROID, LayoutDirection.Rtl)
            onNodeWithText("Swipe left").performScrollTo().performClick()
            onAllNodesWithText("Mark played / unplayed").onLast().performClick()
            waitForIdle()
            assertEquals(
                SwipeAction.MARK_PLAYED,
                runBlocking { settings.get(AppearanceSettingKeys.SWIPE_START_ACTION) },
            )
        }

    @Test
    fun desktopHidesAllGestureRows() =
        runComposeUiTest {
            setAppearance(FakeSettingsRepository(), PlatformKind.DESKTOP)
            onNodeWithText("Gestures").assertDoesNotExist()
            onNodeWithText("Swipe episodes in Feeds").assertDoesNotExist()
            onNodeWithText("Swipe right").assertDoesNotExist()
            onNodeWithText("Swipe left").assertDoesNotExist()
        }

    private fun ComposeUiTest.setAppearance(
        settings: FakeSettingsRepository,
        platform: PlatformKind,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val owner =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply {
                        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    }
            }
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalAppNavigator provides RecordingAppNavigator(),
                LocalLayoutDirection provides direction,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    AppearancePage(settings, DynamicColorSupport.UNAVAILABLE, platform)
                }
            }
        }
        waitForIdle()
    }
}
