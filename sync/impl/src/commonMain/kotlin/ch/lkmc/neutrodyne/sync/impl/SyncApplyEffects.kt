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
 */
class SyncApplyEffects(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsSyncPort,
    private val writeLocks: SettingsWriteCoordinator,
) {
    /** Applies every pending setting whose marker survived; leaves the rest for a later drain. */
    suspend fun drainSettings() {
        for (row in db.syncClockDao().all()) {
            if (row.coll != SyncCollection.SETTING.wire) continue
            val key = AllSettingKeys.list.firstOrNull { it.name == row.rid } ?: continue
            drainSetting(key)
        }
    }

    private suspend fun drainSetting(key: SettingKey<*>) {
        writeLocks.withLock(key) {
            val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name) ?: return@withLock
            val raw = decode(row.clocks) ?: return@withLock
            val pending = raw.pendingSetting ?: return@withLock
            if (hasNewerLocalIntent(key, pending)) {
                ack(key, pending)
                return@withLock
            }
            if (!settings.applyRemote(key, pending.value)) return@withLock
            ack(key, pending)
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
     */
    private suspend fun ack(
        key: SettingKey<*>,
        applied: PendingSetting,
    ) {
        db.withWriteTransaction {
            val fresh =
                db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
                    ?: return@withWriteTransaction
            val freshRaw = decode(fresh.clocks) ?: return@withWriteTransaction
            val marker = freshRaw.pendingSetting ?: return@withWriteTransaction
            if (marker.value != applied.value || marker.clock != applied.clock) {
                return@withWriteTransaction
            }
            db.syncClockDao().upsert(fresh.copy(clocks = encode(freshRaw.copy(pendingSetting = null))))
        }
    }

    private fun decode(json: String): RawSyncRecord? =
        runCatching { SyncJson.decodeFromString<RawSyncRecord>(json) }.getOrNull()

    private fun encode(raw: RawSyncRecord): String = SyncJson.encodeToString(raw)
}
