// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
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
 *
 * A sync round takes a key's lock before Room's writer, so a caller inside a Room transaction
 * that asks for a key's lock can deadlock with it. [withLock] fails such a call at once, every
 * time, instead of hanging only when a round happens to overlap.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DataStoreSettingsWriteCoordinator(
    private val openedDatabase: () -> RoomDatabase?,
) : SettingsWriteCoordinator {
    @Inject
    constructor(opener: DatabaseOpener) : this(opener::openedDatabaseOrNull)

    /** For tests without a database: no caller can hold a transaction, so none is checked. */
    constructor() : this({ null })

    private val pool = Mutex()
    private val locks = mutableMapOf<String, Mutex>()

    override suspend fun <T> withLock(
        key: SettingKey<*>,
        block: suspend () -> T,
    ): T {
        checkOutsideTransaction(key)
        val mutex = pool.withLock { locks.getOrPut(key.name) { Mutex() } }
        return mutex.withLock { block() }
    }

    /**
     * Room confines a connection to the coroutine that acquired it, so a reader request from a
     * caller inside a transaction reenters that caller's connection instead of waiting, and from
     * anyone else borrows an idle WAL reader. A database that is not open yet holds no
     * transaction; one that was closed under the caller (a restore swapping files) cannot be
     * probed, and the write proceeds unchecked.
     */
    private suspend fun checkOutsideTransaction(key: SettingKey<*>) {
        val db = openedDatabase() ?: return
        val inTransaction =
            suspendRunCatching { db.useReaderConnection { it.inTransaction() } }
                .onFailure { error -> Log.d(LOG_TAG) { "transaction probe skipped for '${key.name}': $error" } }
                .getOrDefault(false)
        check(!inTransaction) {
            "settings lock for '${key.name}' requested inside a Room transaction; " +
                "take it first, through SettingsRepository.setWithin"
        }
    }

    private companion object {
        const val LOG_TAG = "SettingsLocks"
    }
}
