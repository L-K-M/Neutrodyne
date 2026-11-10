// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile

/**
 * The device-bound store wrapping `device_settings.preferences_pb` (01 DataStore files and typed
 * setting keys): SAF grants, prompt flags, selected tab/group, `sync.*` device keys and
 * `desktop.*` keys — never backed up, never synced. Implementation modules may inject it
 * directly for their own `DEVICE` keys.
 */
class DeviceSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingStore by SettingStoreImpl(SettingsFile.DEVICE, dataStore) {
    /**
     * Removes every key's stored value in one atomic commit — the device file's counterpart of
     * [SettingsStore.resetAll]. Device keys are never synced, so there are no write receipts.
     */
    suspend fun resetAll(keys: List<SettingKey<*>>) {
        keys.forEach { require(it.file == SettingsFile.DEVICE) { "'${it.name}' is not a device key" } }
        dataStore.edit { preferences ->
            for (key in keys) {
                SettingPreferences.remove(preferences, key)
            }
        }
    }
}
