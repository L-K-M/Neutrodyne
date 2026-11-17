// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys as Keys

/**
 * Returns once both settings files (`settings` and `device_settings`) have loaded. Either shell
 * holds its first frame on it, within a one-second cap, so the first frame shows stored values
 * rather than their defaults.
 */
public suspend fun SettingsRepository.awaitSettingsFiles() {
    combine(observe(Keys.LIBRARY_DENSITY), observe(Keys.PLAYER_PANEL_HIDDEN)) { _, _ -> }.first()
}
