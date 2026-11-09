// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The device sync switches as a synchronous cache (10 Settings capture). `SyncServerAddress` is
 * suspend (DataStore); the engine's `ChangeBuilder` reads [sharePasswords] at push time, so the
 * volatile fields must reflect the persisted switches before the first auth consumer of a run —
 * they are republished after every switch write and hydrated at start-up, before each engine
 * round and before the first-link apply steps.
 *
 * [preferenceMutex] serializes the whole local preference commit: a persisted write and its
 * cache publication hold it together, and a refresh's reads hold it too — so a snapshot an
 * older async reload took can never overwrite a newer saved toggle.
 */
@Inject
@SingleIn(AppScope::class)
class SyncFlags {
    @Volatile
    var sharePasswords: Boolean = false

    @Volatile
    var syncPlaybackSettings: Boolean = true

    private val preferenceMutex = Mutex()

    /**
     * Runs [block] under the one preference-commit guard the switch writes and the refresh
     * share (10 Settings capture: store write, cache publication and persisted re-read are one
     * serialized span).
     */
    internal suspend fun <T> withPreferenceCommit(block: suspend () -> T): T = preferenceMutex.withLock { block() }

    /**
     * Re-primes the cache from the persisted switches. The reads run under the commit guard,
     * so the snapshot can only ever be as old as the last fully committed write — never an
     * intermediate one a concurrent setter has already superseded.
     */
    internal suspend fun refreshFromStore(address: SyncServerAddress) {
        preferenceMutex.withLock {
            val share = address.shareFeedPasswords()
            val playback = address.syncPlaybackSettings()
            sharePasswords = share
            syncPlaybackSettings = playback
        }
    }
}
