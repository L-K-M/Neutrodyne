// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys as Keys

@OptIn(ExperimentalCoroutinesApi::class)
class AppearancePreferencesTest {
    @Test fun firstEmissionHasEveryStoredPreferenceAndChangesAreLive() =
        runTest {
            val settings = FakeSettingsRepository()
            settings.set(Keys.THEME, ThemeMode.DARK)
            settings.set(Keys.DYNAMIC_COLOR, false)
            settings.set(Keys.PURE_BLACK, true)
            settings.set(Keys.ARTWORK_TINT, false)
            settings.set(Keys.PLAYER_PANEL_HIDDEN, true)
            val snapshots = mutableListOf<AppearancePrefs>()
            backgroundScope.launch { settings.observeAppearance().collect { snapshots += it } }
            runCurrent()
            assertEquals(listOf(AppearancePrefs(ThemeMode.DARK, false, true, false)), snapshots)
            settings.set(Keys.ARTWORK_TINT, true)
            runCurrent()
            assertEquals(AppearancePrefs(ThemeMode.DARK, false, true, true), snapshots.last())
        }
}
