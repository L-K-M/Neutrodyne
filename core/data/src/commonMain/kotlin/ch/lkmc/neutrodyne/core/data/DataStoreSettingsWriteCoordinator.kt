// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The per-key settings write mutex of 01's Sync write ordering. One process opens the database
 * and the DataStore files (02 Platform constraints), so one `Mutex` per setting key suffices, and
 * the keys are the fixed [ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys] registry, so the
 * map stays bounded. The pool mutex is held only for the map lookup, never across a caller's
 * block.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DataStoreSettingsWriteCoordinator : SettingsWriteCoordinator {
    private val pool = Mutex()
    private val locks = mutableMapOf<String, Mutex>()

    override suspend fun <T> withLock(
        key: SettingKey<*>,
        block: suspend () -> T,
    ): T {
        val mutex = pool.withLock { locks.getOrPut(key.name) { Mutex() } }
        return mutex.withLock { block() }
    }
}
