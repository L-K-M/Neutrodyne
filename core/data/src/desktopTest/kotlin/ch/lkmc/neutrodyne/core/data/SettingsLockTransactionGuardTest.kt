// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A settings key lock taken inside a Room transaction can deadlock with a sync round, which takes
 * the key's lock first. The coordinator fails that call every time instead of hanging only when a
 * round overlaps.
 */
class SettingsLockTransactionGuardTest {
    private val db = TestDb.inMemory(clock = TestClock())
    private val locks = DataStoreSettingsWriteCoordinator { db }

    @AfterTest fun close() = db.close()

    @Test fun aLockInsideAWriteTransactionFails() =
        runBlocking {
            val error =
                assertFailsWith<IllegalStateException> {
                    db.withWriteTransaction { locks.withLock(PlaybackSettingKeys.SPEED) { "ran" } }
                }
            assertTrue(error.message.orEmpty().contains(PlaybackSettingKeys.SPEED.name), error.message)
        }

    @Test fun aLockInsideAReadTransactionFails() =
        runBlocking {
            assertFailsWith<IllegalStateException> {
                db.withReadTransaction { locks.withLock(PlaybackSettingKeys.SPEED) { "ran" } }
            }
            Unit
        }

    @Test fun aLockBeforeTheTransactionRuns() =
        runBlocking {
            val result =
                locks.withLock(PlaybackSettingKeys.SPEED) {
                    db.withWriteTransaction { db.syncStateDao().get() }
                    "ran"
                }
            assertEquals("ran", result)
        }

    @Test fun anotherCoroutinesTransactionOnlyDelaysTheLock() =
        runBlocking {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer =
                async {
                    db.withWriteTransaction {
                        held.complete(Unit)
                        release.await()
                    }
                }
            held.await()
            val lock = async { locks.withLock(PlaybackSettingKeys.SPEED) { "ran" } }
            release.complete(Unit)
            assertEquals("ran", withTimeout(LIMIT_MS) { lock.await() })
            writer.await()
        }

    @Test fun aClosedDatabaseIsNotProbed() =
        runBlocking {
            db.close()
            assertEquals("ran", locks.withLock(PlaybackSettingKeys.SPEED) { "ran" })
        }

    @Test fun anUnopenedDatabaseIsNotProbed() =
        runBlocking {
            val unopened = DataStoreSettingsWriteCoordinator { null }
            assertEquals("ran", unopened.withLock(PlaybackSettingKeys.SPEED) { "ran" })
        }

    private companion object {
        const val LIMIT_MS = 10_000L
    }
}
