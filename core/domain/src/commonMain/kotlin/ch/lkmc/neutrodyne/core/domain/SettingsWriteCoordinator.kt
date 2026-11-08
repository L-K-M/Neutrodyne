// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.settings.SettingKey

/**
 * The per-key settings write mutex of 01's Sync write ordering (contract owned here; impl in
 * `:core:data` commonMain, one `Mutex` per setting key).
 *
 * A local `set`/`reset` records its durable Room intent and then writes DataStore; a linked
 * restore's historical path records its archive intent (`captureAt`) and then calls
 * `SettingsSyncPort.applyRemote`. Both sequences must be mutually excluded per key: a local
 * write interleaving between the restore's Room capture and its DataStore write would otherwise
 * leave the newer pending literal beside the older stored value.
 */
interface SettingsWriteCoordinator {
    /**
     * Runs [block] mutually excluded with every other settings write of [key]. Not reentrant: a
     * holder must not call it again for the same key. The lock releases on cancellation and on
     * failure. Record the Room intent and leave its transaction before the DataStore file IO
     * inside [block]; never hold a Room write transaction across that IO.
     */
    suspend fun <T> withLock(
        key: SettingKey<*>,
        block: suspend () -> T,
    ): T
}
