// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection

/**
 * 10 Settings capture: while linked, installs the recorder that persists a local synced-key change
 * as a literal `v` outbox intent with a fresh clock **before** DataStore. A failed Room record
 * leaves DataStore unchanged; a failed DataStore write keeps the committed intent for recovery.
 */
class SettingsCapture(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsSyncPort,
) {
    /** Starts recording; called while linked with "Sync playback settings" on. */
    fun install() {
        settings.registerRecorder { key, json ->
            db.withWriteTransaction {
                db
                    .syncOutboxDao()
                    .captureIntent(SyncCollection.SETTING.wire, key.name, "v", json)
            }
        }
    }

    /** Stops recording (unlinked, or the switch off). */
    fun clear() {
        settings.registerRecorder { _, _ -> }
    }
}
