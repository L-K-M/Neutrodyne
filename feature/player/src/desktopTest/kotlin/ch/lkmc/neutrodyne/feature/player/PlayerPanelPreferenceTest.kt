// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlayerPanelPreferenceTest {
    private var previousLocale = Locale.ENGLISH

    @BeforeTest fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun closeAndReopenWriteTheDeviceSettingAndSurviveShellRecreation() =
        runComposeUiTest {
            val settings = FakeSettingsRepository()
            var preference: PlayerPanelPreference? = null
            var visible by mutableStateOf(true)
            setContent { if (visible) preference = rememberPlayerPanelPreference(settings) }
            waitForIdle()
            runOnIdle {
                assertFalse(requireNotNull(preference).hidden)
                requireNotNull(preference).close()
            }
            waitForIdle()
            runOnIdle {
                assertTrue(requireNotNull(preference).hidden)
                visible = false
            }
            waitForIdle()
            runOnIdle { visible = true }
            waitForIdle()
            runOnIdle {
                assertTrue(requireNotNull(preference).hidden)
                requireNotNull(preference).reopen()
            }
            waitForIdle()
            runOnIdle {
                assertFalse(requireNotNull(preference).hidden)
                assertEquals(listOf("set(ui.player_panel_hidden)", "set(ui.player_panel_hidden)"), settings.calls)
                assertFalse(runBlocking { settings.get(AppearanceSettingKeys.PLAYER_PANEL_HIDDEN) })
            }
        }

    @Test
    fun failedCloseDoesNotPretendToHideAndReportsFailure() =
        runComposeUiTest {
            val settings = FakeSettingsRepository().apply { failNextSet = SettingsError.WriteFailed }
            var preference: PlayerPanelPreference? = null
            setContent {
                preference = rememberPlayerPanelPreference(settings)
                PlayerPanelWriteFeedback(requireNotNull(preference))
            }
            waitForIdle()
            runOnIdle { requireNotNull(preference).close() }
            waitForIdle()
            runOnIdle { assertFalse(requireNotNull(preference).hidden) }
            onNodeWithText("Could not save the change. Try again.").assertIsDisplayed()
        }
}
