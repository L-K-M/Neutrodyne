// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import dev.zacsweers.metro.Qualifier

/**
 * Distinguishes the two per-process `DataStore<Preferences>` singletons (01 DataStore files and
 * typed setting keys, rule 3): `@SettingsDataStore(SettingsFile.PORTABLE)` is
 * `settings.preferences_pb`, `@SettingsDataStore(SettingsFile.DEVICE)` is
 * `device_settings.preferences_pb`. Exactly one DataStore per file per process.
 */
@Qualifier
annotation class SettingsDataStore(
    val file: SettingsFile,
)
