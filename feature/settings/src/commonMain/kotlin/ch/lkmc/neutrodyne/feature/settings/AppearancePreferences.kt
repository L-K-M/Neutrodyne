// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys as Keys

/** Wait for both settings files before opening either shell's appearance gate. */
public fun SettingsRepository.observeAppearance(): Flow<AppearancePrefs> =
    combine(
        observe(Keys.THEME),
        observe(Keys.DYNAMIC_COLOR),
        observe(Keys.PURE_BLACK),
        observe(Keys.ARTWORK_TINT),
    ) { theme, dynamic, black, tint -> AppearancePrefs(theme, dynamic, black, tint) }
        .combine(observe(Keys.PLAYER_PANEL_HIDDEN)) { appearance, _ -> appearance }
