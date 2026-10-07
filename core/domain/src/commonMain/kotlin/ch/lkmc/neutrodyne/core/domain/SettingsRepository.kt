// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import kotlinx.coroutines.flow.Flow

/**
 * Typed access to both settings files (01 DataStore files and typed setting keys). Every method
 * routes to the file declared by [SettingKey.file]; absent keys read as [SettingKey.default].
 *
 * The implementation lives in `:core:data` over `:core:datastore`'s two stores. Writes of keys
 * with [SettingKey.synced] are captured for sync **through this interface** (10's
 * `SettingsCapture`, MS2), never by DataStore observers. Secrets never pass through here — they
 * go through `SecretStore` (M1b).
 */
interface SettingsRepository {
    /** Emits the key's current value (its default until first written), then every change. */
    fun <T : Any> observe(key: SettingKey<T>): Flow<T>

    /** The key's current value — its default when never written or corrupt. */
    suspend fun <T : Any> get(key: SettingKey<T>): T

    /**
     * Persists [value] for [key]. Fails with [SettingsError.OutOfRange] when the value is rejected
     * by the key's own validator (for example a `Choice` outside its values) and with
     * [SettingsError.WriteFailed] when the DataStore write fails; both leave the old value intact.
     */
    suspend fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ): Outcome<Unit, SettingsError>

    /** Removes the key's stored value so reads fall back to [SettingKey.default]. */
    suspend fun reset(key: SettingKey<*>)

    /**
     * Every portable key of [ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys] with its
     * current value (defaults included), for 05's backup whitelist export. Never contains
     * `DEVICE` keys.
     */
    fun observePortableSnapshot(): Flow<Map<String, Any>>
}
