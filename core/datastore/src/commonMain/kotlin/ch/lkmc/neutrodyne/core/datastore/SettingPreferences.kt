// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import kotlinx.collections.immutable.toPersistentSet

/**
 * Converts between [SettingKey]s and DataStore's `Preferences` (01 DataStore files and typed
 * setting keys). Every read falls back to the key's default — absent, wrong-typed or unknown
 * values included — because `Preferences.Key` equality is by name alone, so a value stored under
 * a re-typed name must read as the default rather than crash. `Choice`s are stored as their
 * enum name; `TextSet`s as string sets.
 */
internal object SettingPreferences {
    /** The raw preference entry for [key] — `Bool`→boolean, …, `Choice`→its enum name, `TextSet`→string set. */
    fun preferenceKeyOf(key: SettingKey<*>): Preferences.Key<*> =
        when (key) {
            is SettingKey.Bool -> booleanPreferencesKey(key.name)
            is SettingKey.Int32 -> intPreferencesKey(key.name)
            is SettingKey.Int64 -> longPreferencesKey(key.name)
            is SettingKey.Float32 -> floatPreferencesKey(key.name)
            is SettingKey.Text -> stringPreferencesKey(key.name)
            is SettingKey.TextSet -> stringSetPreferencesKey(key.name)
            is SettingKey.Choice<*> -> stringPreferencesKey(key.name)
        }

    /** Reads [key] from [preferences]; any surprise (absent, wrong type, unknown name) yields the default. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> read(
        preferences: Preferences,
        key: SettingKey<T>,
    ): T =
        when (key) {
            is SettingKey.Bool -> {
                preferences.stored<Boolean>(booleanPreferencesKey(key.name), key.default)
            }

            is SettingKey.Int32 -> {
                preferences.stored<Int>(intPreferencesKey(key.name), key.default)
            }

            is SettingKey.Int64 -> {
                preferences.stored<Long>(longPreferencesKey(key.name), key.default)
            }

            is SettingKey.Float32 -> {
                preferences.stored<Float>(floatPreferencesKey(key.name), key.default)
            }

            is SettingKey.Text -> {
                preferences.stored<String>(stringPreferencesKey(key.name), key.default)
            }

            is SettingKey.TextSet -> {
                preferences
                    .stored<Set<String>>(stringSetPreferencesKey(key.name), key.default.toSet())
                    .toPersistentSet()
            }

            is SettingKey.Choice<*> -> {
                key.fromStored(runCatching { preferences[stringPreferencesKey(key.name)] }.getOrNull())
            }
        } as T

    /** Writes [value] under [key]'s preference entry. Callers validate first (see [validate]). */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> write(
        preferences: MutablePreferences,
        key: SettingKey<T>,
        value: T,
    ) {
        when (key) {
            is SettingKey.Bool -> {
                preferences[booleanPreferencesKey(key.name)] = value as Boolean
            }

            is SettingKey.Int32 -> {
                preferences[intPreferencesKey(key.name)] = value as Int
            }

            is SettingKey.Int64 -> {
                preferences[longPreferencesKey(key.name)] = value as Long
            }

            is SettingKey.Float32 -> {
                preferences[floatPreferencesKey(key.name)] = value as Float
            }

            is SettingKey.Text -> {
                preferences[stringPreferencesKey(key.name)] = value as String
            }

            is SettingKey.TextSet -> {
                preferences[stringSetPreferencesKey(key.name)] =
                    (value as Set<*>).map { it as String }.toSet()
            }

            is SettingKey.Choice<*> -> {
                preferences[stringPreferencesKey(key.name)] = (value as Enum<*>).name
            }
        }
    }

    /** Removes [key]'s entry, restoring its default for later reads. */
    fun remove(
        preferences: MutablePreferences,
        key: SettingKey<*>,
    ) {
        preferences -= preferenceKeyOf(key)
    }

    /** Rejects what the key itself forbids: a `Choice` value outside its declared values. */
    fun validate(
        key: SettingKey<*>,
        value: Any,
    ) {
        if (key is SettingKey.Choice<*>) {
            require(value in key.values) { "'${key.name}' does not allow value '$value'" }
        }
    }

    /** `preferences[prefKey]` with every failure (missing or wrong-typed entry) mapped to [default]. */
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified V : Any> Preferences.stored(
        prefKey: Preferences.Key<*>,
        default: V,
    ): V = runCatching { this[prefKey as Preferences.Key<V>] }.getOrNull() as? V ?: default
}
