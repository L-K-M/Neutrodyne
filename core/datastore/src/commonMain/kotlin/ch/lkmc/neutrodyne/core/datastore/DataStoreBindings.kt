// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The two DataStore singletons and their stores, contributed to [AppScope] (01 Components and
 * scopes; 01 DataStore files and typed setting keys, rule 3). Both shells' graphs see these
 * bindings; `:ytx` never opens either file.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object DataStoreBindings {
    @Provides
    @SingleIn(AppScope::class)
    @SettingsDataStore(SettingsFile.PORTABLE)
    fun portableDataStore(factory: SettingsDataStoreFactory): DataStore<Preferences> =
        factory.create(SettingsFile.PORTABLE)

    @Provides
    @SingleIn(AppScope::class)
    @SettingsDataStore(SettingsFile.DEVICE)
    fun deviceDataStore(factory: SettingsDataStoreFactory): DataStore<Preferences> = factory.create(SettingsFile.DEVICE)

    @Provides
    @SingleIn(AppScope::class)
    fun settingsStore(
        @SettingsDataStore(SettingsFile.PORTABLE) dataStore: DataStore<Preferences>,
    ): SettingsStore = SettingsStore(dataStore)

    @Provides
    @SingleIn(AppScope::class)
    fun deviceSettingsStore(
        @SettingsDataStore(SettingsFile.DEVICE) dataStore: DataStore<Preferences>,
    ): DeviceSettingsStore = DeviceSettingsStore(dataStore)
}
