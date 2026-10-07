// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile

/**
 * The portable store wrapping `settings.preferences_pb` (01 DataStore files and typed setting
 * keys): what a backup carries and sync may. Implementation modules may inject it directly for
 * their own `PORTABLE` keys.
 */
class SettingsStore internal constructor(
    dataStore: DataStore<Preferences>,
) : SettingStore by SettingStoreImpl(SettingsFile.PORTABLE, dataStore)
