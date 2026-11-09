// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.coroutines.flow.first

/**
 * The portable store wrapping `settings.preferences_pb` (01 DataStore files and typed setting
 * keys): what a backup carries and sync may. Implementation modules may inject it directly for
 * their own `PORTABLE` keys.
 *
 * On top of the plain [SettingStore] contract it carries per-key write receipts — opaque
 * markers a caller commits atomically with a portable write ([setWithReceipt],
 * [resetWithReceipt]) and reads back in one snapshot with the value ([readWithReceipt]). The
 * store never parses a receipt; it only keeps it paired with the write that carried it and
 * clears it on every untracked write.
 */
class SettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingStore by SettingStoreImpl(SettingsFile.PORTABLE, dataStore) {
    /**
     * The key's value and write receipt from one store snapshot. Unlike [get] this read is
     * strict — an unreadable file fails rather than reporting defaults — while the value itself
     * still obeys the never-throw rules (an absent, foreign-typed or invalid stored value reads
     * as the key's default). [SettingSnapshot.receipt] is the last receipt committed with a
     * `*WithReceipt` write, `null` when none is stored.
     */
    suspend fun <T : Any> readWithReceipt(key: SettingKey<T>): SettingSnapshot<T> {
        requirePortable(key)
        val preferences = dataStore.data.first()
        return SettingSnapshot(SettingPreferences.read(preferences, key), readReceipt(preferences, key))
    }

    /**
     * Persists [value] and the opaque [receipt] in one atomic commit — the pair either lands
     * together or not at all. The receipt is stored verbatim and never parsed; empty is a valid
     * receipt. Same file routing and value validation as [set].
     */
    suspend fun <T : Any> setWithReceipt(
        key: SettingKey<T>,
        value: T,
        receipt: String,
    ) {
        requirePortable(key)
        SettingPreferences.validate(key, value)
        dataStore.edit { preferences ->
            SettingPreferences.write(preferences, key, value)
            preferences[receiptPreferenceKey(key)] = receipt
        }
    }

    /**
     * Removes the stored value and commits [receipt] in the same edit — later reads see the
     * key's default carrying this receipt.
     */
    suspend fun resetWithReceipt(
        key: SettingKey<*>,
        receipt: String,
    ) {
        requirePortable(key)
        dataStore.edit { preferences ->
            SettingPreferences.remove(preferences, key)
            preferences[receiptPreferenceKey(key)] = receipt
        }
    }

    /** Strict value read: an unreadable file must not masquerade as a real default. */
    suspend fun <T : Any> getOrThrow(key: SettingKey<T>): T = readWithReceipt(key).value

    private fun requirePortable(key: SettingKey<*>) {
        require(key.file == SettingsFile.PORTABLE) {
            "'${key.name}' belongs to ${key.file.storeName}, not ${SettingsFile.PORTABLE.storeName}"
        }
    }
}

/** A [SettingsStore.readWithReceipt] result: the value and the write receipt committed with it. */
data class SettingSnapshot<T : Any>(
    val value: T,
    val receipt: String?,
)
