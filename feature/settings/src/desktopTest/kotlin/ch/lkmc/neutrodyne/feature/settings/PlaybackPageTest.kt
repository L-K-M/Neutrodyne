// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMaintenance
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlaybackPageTest {
    private lateinit var oldLocale: Locale

    @BeforeTest fun english() {
        oldLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restore() {
        Locale.setDefault(oldLocale)
    }

    // The settings-home Playback row and its route land with P6's SettingsHomeRoute/SettingsRoute
    // activation; settingsHomePlaybackRowNavigatesAndShowsLiveSummary returns with those hunks.

    @Test fun desktopHidesNavigationFocusAndExplainsMeteredPolicy() =
        runComposeUiTest {
            val vm = PlaybackSettingsViewModel(FakeSettingsRepository(), FakePlaybackMaintenance())
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides RecordingAppNavigator()) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { PlaybackPage(PlatformKind.DESKTOP, vm) }
                }
            }
            onNodeWithText("Default speed").assertIsDisplayed()
            onNodeWithText("Pause for navigation prompts").assertDoesNotExist()
            onNodeWithText("Not used on computers").assertExists()
        }

    @Test fun skipIntervalChoiceAndValidatedPresetsWriteSettings() =
        runComposeUiTest {
            val settings = FakeSettingsRepository()
            val vm = PlaybackSettingsViewModel(settings, FakePlaybackMaintenance())
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides RecordingAppNavigator()) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { PlaybackPage(PlatformKind.ANDROID, vm) }
                }
            }
            onNodeWithText("Skip back").performScrollTo().performClick()
            onNodeWithText("20 seconds").performClick()
            waitForIdle()
            assertEquals(20_000L, runBlocking { settings.get(PlaybackSettingKeys.SKIP_BACK_MS) })
            onNodeWithText("Speed presets").performScrollTo().performClick()
            onNode(hasSetTextAction()).performTextReplacement("0.1")
            onNodeWithText("Save").assertIsNotEnabled()
            onNode(hasSetTextAction()).performTextReplacement("1.0,1.5")
            onNodeWithText("Save").performClick()
            waitForIdle()
            assertEquals("1.0,1.5", runBlocking { settings.get(PlaybackSettingKeys.SPEED_PRESETS) })
        }

    @Test fun clearCacheRequiresConfirmationAndCallsMaintenance() =
        runComposeUiTest {
            val maintenance = FakePlaybackMaintenance().apply { bytes = 10_000_000 }
            val vm = PlaybackSettingsViewModel(FakeSettingsRepository(), maintenance)
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides RecordingAppNavigator()) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { PlaybackPage(PlatformKind.ANDROID, vm) }
                }
            }
            onNodeWithText("Clear streaming cache").performScrollTo().performClick()
            assertEquals(0, maintenance.clearCalls)
            onNodeWithText("Clear").performClick()
            waitForIdle()
            assertEquals(1, maintenance.clearCalls)
        }
}
