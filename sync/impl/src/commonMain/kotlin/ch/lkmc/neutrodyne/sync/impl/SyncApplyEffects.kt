// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 10 Durable apply effects: drains the recoverable part of a committed page (settings, protected
 * credentials) after the commit and at each start. A winning setting stores `pendingSetting`;
 * [drainSettings] applies it through [SettingsSyncPort] and clears the marker only after the
 * DataStore write succeeds, so a crash between the stores repeats the idempotent write.
 *
 * Every key drains under [SettingsWriteCoordinator], the same per-key mutex a local `set`/`reset`
 * and a linked restore take. Under that lock the marker and the newest local `v` outbox intent are
 * re-read: a strictly newer local intent wins and the stale remote value is skipped, while an equal
 * clock is an identical intent and still applies (a storage-failure retry). The marker is cleared
 * from a fresh row inside a Room write transaction, and only when it still matches the applied
 * value and clock, so a newer page's effect or other raw metadata is never rolled back. A skipped
 * marker is cleared by the same conditional ack: the upload ack deletes the outbox intent before
 * the server's echo page can replace the marker, so leaving it would let a later drain re-apply
 * the superseded remote value. No Room transaction spans the DataStore write.
 *
 * A drain runs only while the persisted "Sync playback settings" switch is on
 * ([settingsSyncEnabled], the live `SyncServerAddress.syncPlaybackSettings()` read — never
 * `SyncFlags`' late cache): while it is off the markers stay pending (10 Pull and apply step 3),
 * and turning it on drains them. A drain failure propagates; the markers it did not clear stay
 * for the next drain.
 */
class SyncApplyEffects(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsSyncPort,
    private val writeLocks: SettingsWriteCoordinator,
    private val settingsSyncEnabled: suspend () -> Boolean,
) {
    /** Applies every pending setting whose marker survived; leaves the rest for a later drain. */
    suspend fun drainSettings() {
        // The switch gate is the persisted value read live: markers stay pending while it is
        // off, so enabling it later applies them (the server's values win, as at the first link).
        if (!settingsSyncEnabled()) return
        for (row in db.syncClockDao().all()) {
            if (row.coll != SyncCollection.SETTING.wire) continue
            val key = AllSettingKeys.list.firstOrNull { it.name == row.rid } ?: continue
            drainSetting(key)
        }
    }

    /**
     * 10 Durable apply effects / Settings capture: the at-each-start drain, before any push or
     * hash reconciliation. Only while linked — an unlink cleared the sync tables, so a leftover
     * marker of the old account must never re-apply. Local only (Room and DataStore): no network
     * call, nothing that blocks start-up beyond the markers' own writes.
     *
     * [capture] reconciles first: its committed-intent recovery must precede the marker drain,
     * because the drain clears a superseded marker after applying — an older local intent
     * materialized only afterwards would then overwrite the newer remote value it had lost to.
     */
    suspend fun drainAtStart(capture: SettingsCapture) {
        val state = db.syncStateDao().get() ?: return
        if (!state.enabled || state.linkedAt == null) return
        capture.reconcile()
        drainSettings()
    }

    private suspend fun drainSetting(key: SettingKey<*>) {
        writeLocks.withLock(key) {
            // A previous key or local writer may have held this drain while sync was disabled.
            // Admit each key under its write lock; later keys stay pending once the switch is off.
            if (!settingsSyncEnabled()) return@withLock
            val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name) ?: return@withLock
            val raw = decode(row.clocks) ?: return@withLock
            val pending = raw.pendingSetting ?: return@withLock
            if (hasNewerLocalIntent(key, pending)) {
                ack(key, pending, applied = false)
                return@withLock
            }
            if (!settings.applyRemote(key, pending.value)) return@withLock
            ack(key, pending, applied = true)
        }
    }

    /**
     * True when the newest local `v` intent's complete clock is strictly newer than the pending
     * remote clock. A marker or intent without a parseable clock cannot prove a newer local write,
     * so it applies as before.
     */
    private suspend fun hasNewerLocalIntent(
        key: SettingKey<*>,
        pending: PendingSetting,
    ): Boolean {
        val pendingClock = runCatching { Hlc.parse(pending.clock) }.getOrNull() ?: return false
        val local = db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v") ?: return false
        val localClock = runCatching { Hlc(local.hlc, NodeId(local.nodeId)) }.getOrNull() ?: return false
        return localClock > pendingClock
    }

    /**
     * Clears the marker only when the fresh row still carries exactly the given value and clock.
     * The re-read and the write share one Room transaction, so a concurrent metadata update or a
     * newer page's marker is neither overwritten nor cleared. Used both after a successful apply
     * and when a strictly newer local intent supersedes the marker.
     *
     * Only an [applied] marker advances the materialization baseline to the applied value and
     * clock — atomically with the clear. A superseded marker never materialized, so the baseline
     * stays with the local intent that beat it.
     */
    private suspend fun ack(
        key: SettingKey<*>,
        pending: PendingSetting,
        applied: Boolean,
    ) {
        db.withWriteTransaction {
            val fresh =
                db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
                    ?: return@withWriteTransaction
            val freshRaw = decode(fresh.clocks) ?: return@withWriteTransaction
            val marker = freshRaw.pendingSetting ?: return@withWriteTransaction
            if (marker.value != pending.value || marker.clock != pending.clock) {
                return@withWriteTransaction
            }
            val next =
                if (applied) {
                    freshRaw.settingHash.advancedTo(
                        marker.value,
                        marker.clock,
                        settings.canonicalJson(key, marker.value) ?: return@withWriteTransaction,
                    )
                } else {
                    freshRaw.settingHash
                }
            db.syncClockDao().upsert(
                fresh.copy(clocks = encode(freshRaw.copy(pendingSetting = null, settingHash = next))),
            )
        }
    }

    private fun decode(json: String): RawSyncRecord? =
        runCatching { SyncJson.decodeFromString<RawSyncRecord>(json) }.getOrNull()

    private fun encode(raw: RawSyncRecord): String = SyncJson.encodeToString(raw)
}
