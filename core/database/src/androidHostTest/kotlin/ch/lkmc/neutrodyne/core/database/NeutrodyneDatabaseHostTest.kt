// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Robolectric half of the driver tests (spike S4, 02 Testing): `AndroidSQLiteDriver` against
 * Robolectric's host SQLite — `onCreate` singletons, inserts, the cascade delete, and
 * `PRAGMA foreign_keys` on the writer connection. The bundled driver's Android `.so` files cannot
 * load on the host JVM, so the bundled driver is exercised only in `androidDeviceTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeutrodyneDatabaseHostTest {
    @Test
    fun opensAndReadsOnTheFrameworkDriverUnderRobolectric() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                assertNotNull(db.playSessionDao().get())
                assertNotNull(db.syncStateDao().get())

                val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-host"))
                val ep =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = p, identityKey = "g:host")),
                        ).single()
                db.episodeStateDao().upsert(episodeStateEntity(ep, playedAt = 1))
                db.queueDao().insert(queueEntryEntity(ep, orderKey = "a0"))

                db.podcastDao().deleteCascade(p, now = 2)

                assertNull(db.podcastDao().byId(p))
                assertNull(db.episodeStateDao().byEpisode(ep))
                assertEquals(1L, fkOnWriter(db))
            } finally {
                db.close()
            }
        }

    private suspend fun fkOnWriter(db: NeutrodyneDatabase): Long =
        db.useWriterConnection { tx ->
            tx.usePrepared("PRAGMA foreign_keys") { stmt ->
                check(stmt.step())
                stmt.getLong(0)
            }
        }
}
