// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 10 Durable apply effects: drains the recoverable part of a committed page (settings, protected
 * credentials) after the commit and at each start. A winning setting stores `pendingSetting`;
 * [drain] applies it through [SettingsSyncPort] and clears the marker only after the DataStore
 * write succeeds, so a crash between the stores repeats the idempotent write.
 */
class SyncApplyEffects(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsSyncPort,
) {
    /** Applies every pending setting whose marker survived; leaves the rest for a later drain. */
    suspend fun drainSettings() {
        for (row in db.syncClockDao().all()) {
            if (row.coll != SyncCollection.SETTING.wire) continue
            val raw = decode(row.clocks) ?: continue
            val pending = raw.pendingSetting ?: continue
            val key = AllSettingKeys.list.firstOrNull { it.name == row.rid } ?: continue
            if (!settings.applyRemote(key, pending.value)) continue
            db.syncClockDao().upsert(row.copy(clocks = encode(raw.copy(pendingSetting = null))))
        }
    }

    private fun decode(json: String): RawSyncRecord? =
        runCatching { SyncJson.decodeFromString<RawSyncRecord>(json) }.getOrNull()

    private fun encode(raw: RawSyncRecord): String = SyncJson.encodeToString(raw)
}
