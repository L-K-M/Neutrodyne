// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import okio.Path.Companion.toPath

/**
 * The store factory (01 DataStore files and typed setting keys, rule 3): exactly one
 * `DataStore<Preferences>` per [SettingsFile] per process, created from [StoragePaths] through
 * Okio on both platforms. A corrupt file is reset to defaults and the event logged at WARN.
 */
class SettingsDataStoreFactory
    @Inject
    constructor(
        private val storagePaths: StoragePaths,
        @ApplicationScope appScope: CoroutineScope,
        @Dispatcher(NeutrodyneDispatchers.IO) ioDispatcher: CoroutineDispatcher,
    ) {
        /** The DataStore scope: the app's supervised scope, moved to IO where the file lives. */
        private val scope = appScope + ioDispatcher

        /** Creates the DataStore for [file]; the shell graphs keep each result a singleton. */
        fun create(file: SettingsFile): DataStore<Preferences> =
            PreferenceDataStoreFactory.createWithPath(
                corruptionHandler =
                    ReplaceFileCorruptionHandler { corruption ->
                        Log.w(LOG_TAG, corruption) {
                            "Preferences file '${storagePaths.dataStoreFile(
                                file.storeName,
                            )}' is corrupt; resetting to defaults"
                        }
                        emptyPreferences()
                    },
                scope = scope,
                produceFile = { storagePaths.dataStoreFile(file.storeName).toPath() },
            )

        private companion object {
            const val LOG_TAG = "DataStore"
        }
    }
