// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import kotlinx.coroutines.flow.Flow

/**
 * The synced-settings half of 10 Settings capture: `:sync:impl`'s `SettingsCapture` installs a
 * recorder while linked, and the applier calls [applyRemote] for a winning `setting` record.
 * Implemented beside `SettingsRepository` in `:core:data` so both share the DataStore writes and
 * `:core:datastore` stays free of Room and sync (01 Sync write ordering).
 *
 * [json] is the value's JSON text in the key's own type (`SettingKey` subclasses own the shape);
 * an invalid or out-of-range value is skipped, never thrown.
 */
interface SettingsSyncPort {
    /**
     * Applies a remote value. Returns `false` when the value is invalid for the key or the
     * DataStore write fails; the caller then keeps it pending for a later drain.
     */
    suspend fun applyRemote(
        key: SettingKey<*>,
        json: String,
    ): Boolean

    /**
     * Local changes to synced keys, emitted after the DataStore write commits. `:sync:impl`
     * records the literal value and its clock before this point.
     */
    val localSyncedChanges: Flow<LocalSyncedSetting>

    /**
     * Installs the recorder `SettingsCapture` uses to persist a local intent before DataStore;
     * `null` clears it (unlinked or the switch off). The recorder is called only for keys with
     * [SettingKey.synced].
     */
    fun registerRecorder(recorder: suspend (key: SettingKey<*>, json: String) -> Unit)

    /**
     * Encodes [value] under [key]'s JSON shape; `"null"` for a reset, `null` when the value is
     * not valid for the key.
     */
    fun encode(
        key: SettingKey<*>,
        value: Any?,
    ): String?

    /**
     * Persists the local intent for a synced key before its DataStore write. A recorder failure
     * propagates so the caller leaves DataStore unchanged.
     */
    suspend fun recordLocal(
        key: SettingKey<*>,
        value: Any?,
    )

    /** Emits [LocalSyncedSetting] after a successful local DataStore write of a synced key. */
    fun emitLocal(
        key: SettingKey<*>,
        value: Any?,
        atMs: Long,
    )
}

/** One local synced-key change, with the value's JSON text and the wall time of the write. */
data class LocalSyncedSetting(
    val key: SettingKey<*>,
    val json: String,
    val atMs: Long,
)
