// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys as Keys

/**
 * One key from each settings file. Only their first reads matter: a key reads once its file has
 * loaded (or failed to load and fallen back to defaults). `SettingsGateTest` fails if the list
 * stops covering every [ch.lkmc.neutrodyne.core.model.settings.SettingsFile].
 */
internal val SETTINGS_FILE_SENTINELS: List<SettingKey<*>> =
    listOf(Keys.LIBRARY_DENSITY, Keys.PLAYER_PANEL_HIDDEN)

/**
 * Returns once both settings files (`settings` and `device_settings`) have loaded. Either shell
 * holds its first frame on it, within a one-second cap, so the first frame shows stored values
 * rather than their defaults.
 */
public suspend fun SettingsRepository.awaitSettingsFiles() {
    combine(SETTINGS_FILE_SENTINELS.map { observe(it) }) { }.first()
}
