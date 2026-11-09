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
     * Materializes a linked restore's already committed literal intent with its opaque receipt.
     * Returns false on invalid data or unavailable storage. The caller holds the per-key lock;
     * capture and recordCommitted already committed together, before this DataStore-only write.
     */
    suspend fun applyRecorded(
        key: SettingKey<*>,
        json: String,
        origin: SettingWriteOrigin,
    ): Boolean

    /** Records the existing intent's receipt in the caller's writer, without any DataStore IO. */
    suspend fun recordCommitted(
        key: SettingKey<*>,
        json: String,
        before: SyncedSettingSnapshot,
    ): SettingWriteOrigin

    /** Installs the adapter for a restore's committed intent; null clears it on unlink. */
    fun registerCommittedRecorder(
        recorder: (suspend (key: SettingKey<*>, json: String, before: SyncedSettingSnapshot) -> SettingWriteOrigin)?,
    )

    /**
     * Local changes to synced keys, emitted after the DataStore write commits. `:sync:impl`
     * records the literal value and its clock before this point.
     */
    val localSyncedChanges: Flow<LocalSyncedSetting>

    /**
     * Installs the recorder `SettingsCapture` uses to persist a local intent before DataStore;
     * `null` clears it while unlinked. While linked it stays installed and live-gated, including
     * when the switch is off. Its result identifies this exact write's admission, independent of
     * later gate changes; the repository persists it atomically with the value. Called only for
     * [SettingKey.synced].
     */
    fun registerRecorder(recorder: (suspend (key: SettingKey<*>, json: String) -> SettingWriteOrigin)?)

    /**
     * Installs the listener `SettingsCapture` uses to record that a local synced write reached
     * DataStore (its materialization baseline); `null` clears it. Called from [emitLocal], after
     * every successful local write of a synced key — captured or not — so an uncaptured write
     * (the switch off) is still distinguishable from a never-materialized committed intent.
     */
    fun registerMaterialized(listener: (suspend (key: SettingKey<*>, json: String) -> Unit)?)

    /**
     * Encodes [value] under [key]'s JSON shape; `"null"` for a reset, `null` when the value is
     * not valid for the key.
     */
    fun encode(
        key: SettingKey<*>,
        value: Any?,
    ): String?

    /**
     * Purely decodes and re-encodes valid JSON in the key's stored type, without store IO.
     * Preserves a reset as "null"; returns null for invalid input. Recovery hashes this
     * representation so Float32 normalization cannot be mistaken for another local write.
     */
    fun canonicalJson(
        key: SettingKey<*>,
        json: String,
    ): String?

    /**
     * The key's current stored value in the same JSON shape [encode] produces (its default when
     * never written); `null` when it cannot be read or encoded. Recovery compares this against
     * the committed intent and the baseline to tell an unmaterialized intent from a later
     * uncaptured edit.
     */
    suspend fun currentJson(key: SettingKey<*>): String?

    /** Strictly reads the effective value and its local write origin from one store snapshot. */
    suspend fun currentSnapshot(key: SettingKey<*>): SyncedSettingSnapshot?

    /**
     * Persists the local intent for a synced key before its DataStore write. A recorder failure
     * propagates so the caller leaves DataStore unchanged.
     */
    suspend fun recordLocal(
        key: SettingKey<*>,
        value: Any?,
    ): SettingWriteOrigin

    /**
     * Runs the materialized listener, then emits [LocalSyncedSetting] after a successful local
     * DataStore write of a synced key.
     */
    suspend fun emitLocal(
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

/** Admission fixed before a local write; an uncaptured edit has no wire clock. */
sealed interface SettingWriteOrigin {
    data object Uncaptured : SettingWriteOrigin

    /** Opaque account/link and complete-clock receipt owned by the capture implementation. */
    data class Captured(
        val token: String,
    ) : SettingWriteOrigin
}

/** A missing origin means an untracked writer, such as a direct store edit or plain unlinked restore. */
data class SyncedSettingSnapshot(
    val json: String,
    val origin: SettingWriteOrigin?,
    /** Opaque identity of this physical write; null for an untracked store value. */
    val receipt: String? = null,
)
