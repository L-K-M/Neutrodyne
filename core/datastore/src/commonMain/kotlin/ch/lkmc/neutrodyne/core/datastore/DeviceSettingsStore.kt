// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile

/**
 * The device-bound store wrapping `device_settings.preferences_pb` (01 DataStore files and typed
 * setting keys): SAF grants, prompt flags, selected tab/group, `sync.*` device keys and
 * `desktop.*` keys — never backed up, never synced. Implementation modules may inject it
 * directly for their own `DEVICE` keys.
 */
class DeviceSettingsStore internal constructor(
    dataStore: DataStore<Preferences>,
) : SettingStore by SettingStoreImpl(SettingsFile.DEVICE, dataStore)
