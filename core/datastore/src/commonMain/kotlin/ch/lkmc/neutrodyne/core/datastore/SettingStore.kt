// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.IOException

/**
 * Typed access to one preferences file, shared by [SettingsStore] (portable) and
 * [DeviceSettingsStore] (device-bound) so `:core:data`'s `SettingsRepository` can route by
 * [SettingKey.file]. Reads never throw: absent, corrupt or unknown values fall back to
 * [SettingKey.default]. Added 2026-10-06 as the common supertype 01's rule 4 implies.
 */
interface SettingStore {
    /** Which file this store wraps — keys with another [SettingKey.file] are rejected. */
    val file: SettingsFile

    /** Emits the key's value (default until first written), then every change of that value. */
    fun <T : Any> observe(key: SettingKey<T>): Flow<T>

    /** The key's current value — its default when never written or corrupt. */
    suspend fun <T : Any> get(key: SettingKey<T>): T

    /** Persists [value] for [key]; rejects a value the key itself forbids (a `Choice` outside its values). */
    suspend fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    )

    /** Atomic read-modify-write: [transform] receives the current value (or the default). */
    suspend fun <T : Any> update(
        key: SettingKey<T>,
        transform: (T) -> T,
    )

    /** Removes the stored value so the key reads its default again. */
    suspend fun reset(key: SettingKey<*>)
}

/** The whole store behaviour of [SettingStore] once, so the two files' stores stay trivial. */
internal class SettingStoreImpl(
    override val file: SettingsFile,
    private val dataStore: DataStore<Preferences>,
) : SettingStore {
    override fun <T : Any> observe(key: SettingKey<T>): Flow<T> =
        readablePreferences()
            .map { preferences -> SettingPreferences.read(preferences, key) }
            .distinctUntilChanged()

    override suspend fun <T : Any> get(key: SettingKey<T>): T {
        requireFile(key)
        return SettingPreferences.read(readablePreferences().first(), key)
    }

    override suspend fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ) {
        requireFile(key)
        SettingPreferences.validate(key, value)
        dataStore.edit { preferences -> SettingPreferences.write(preferences, key, value) }
    }

    override suspend fun <T : Any> update(
        key: SettingKey<T>,
        transform: (T) -> T,
    ) {
        requireFile(key)
        dataStore.edit { preferences ->
            val current = SettingPreferences.read(preferences, key)
            val next = transform(current)
            SettingPreferences.validate(key, next)
            SettingPreferences.write(preferences, key, next)
        }
    }

    override suspend fun reset(key: SettingKey<*>) {
        requireFile(key)
        dataStore.edit { preferences -> SettingPreferences.remove(preferences, key) }
    }

    /**
     * The file's preferences, with an expected read failure — an I/O error, or a corrupt file whose
     * replacement failed (DataStore rethrows then, e.g. on a full disk) — read as an empty file, so
     * every key reads its default and nothing is overwritten. The flow then completes; a later
     * collection (the next lifecycle start) reads the file again. Bugs and cancellation propagate.
     */
    private fun readablePreferences(): Flow<Preferences> =
        dataStore.data.catch { failure ->
            if (failure !is IOException) throw failure
            Log.w(TAG, failure) { "${file.storeName} is unreadable; reading defaults" }
            emit(emptyPreferences())
        }

    private fun requireFile(key: SettingKey<*>) {
        require(key.file == file) {
            "'${key.name}' belongs to ${key.file.storeName}, not ${file.storeName}"
        }
    }

    private companion object {
        const val TAG = "Settings"
    }
}
