// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.OwnerType
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity

/**
 * The GMD half of `SchemaSmokeTest` and `UnsubscribeCascadeTest` (02 Testing): the framework and
 * bundled drivers on a real device — `onCreate` singletons, every DAO read, `PRAGMA
 * foreign_keys = 1` on writer and reader connections, and the unsubscribe cascade.
 */
@RunWith(AndroidJUnit4::class)
class NeutrodyneDatabaseDeviceTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun opensAndReadsEveryDaoOnTheFrameworkDriver() = runTest {
        smoke(AndroidSQLiteDriver())
    }

    @Test
    fun opensAndReadsEveryDaoOnTheBundledDriver() = runTest {
        smoke(BundledSQLiteDriver())
    }

    @Test
    fun deleteCascadeRemovesThePodcastAndItsRows() = runTest {
        for (driver in listOf(AndroidSQLiteDriver(), BundledSQLiteDriver())) {
            val db = TestDb.inMemory(context, driver)
            try {
                val group = db.groupDao().insert(groupEntity(orderKey = "a0"))
                val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-cascade"))
                db.podcastDao().insertAlias(
                    PodcastUrlAliasEntity("https://old/$p", p, AliasReason.REDIRECT, addedAt = 1),
                )
                db.scopeSettingsDao()
                    .upsertPodcast(PodcastSettingsEntity(podcastId = p, o = ScopeOverrides()))
                db.groupDao().insertMember(memberEntity(group, p, orderKey = "a1"))

                val ep =
                    db.ingestDao().insertEpisodes(
                        listOf(episodeEntity(podcastId = p, identityKey = "g:1")),
                    ).single()
                db.episodeStateDao().upsert(episodeStateEntity(ep, playedAt = 1))
                db.queueDao().insert(queueEntryEntity(ep, orderKey = "a0"))
                db.downloadDao().insert(downloadEntity(ep))
                val persons =
                    listOf(PersonEntity(ownerType = OwnerType.PODCAST, ownerId = p, name = "Host"))
                val funding =
                    listOf(FundingEntity(ownerType = OwnerType.PODCAST, ownerId = p, url = "https://fund"))
                db.ingestDao().replacePodcastChildren(podcastId = p, persons = persons, funding = funding)

                db.podcastDao().deleteCascade(p, now = 2)

                assertNull(db.podcastDao().byId(p))
                assertTrue(db.ingestDao().existing(p).isEmpty())
                assertNull(db.episodeStateDao().byEpisode(ep))
                assertTrue(db.queueDao().entries().isEmpty())
                assertNull(db.downloadDao().byEpisode(ep))
                assertTrue(db.groupDao().membersOf(group).isEmpty())
            } finally {
                db.close()
            }
        }
    }

    private suspend fun smoke(driver: SQLiteDriver) {
        val db = TestDb.inMemory(context, driver)
        try {
            val session = assertNotNull(db.playSessionDao().get())
            assertEquals(0, session.id)
            assertNull(session.currentEpisodeId)

            val sync = assertNotNull(db.syncStateDao().get())
            assertEquals(0, sync.id)
            assertFalse(sync.enabled)

            assertNull(db.podcastDao().byId(1))
            assertNull(db.episodeDao().byId(1))
            assertTrue(db.ingestDao().existing(1).isEmpty())
            assertNull(db.groupDao().groupById(1))
            assertNull(db.scopeSettingsDao().forPodcast(1))
            assertNull(db.episodeStateDao().byEpisode(1))
            assertNull(db.positionDao().byEpisode(1))
            assertTrue(db.queueDao().entries().isEmpty())
            assertNull(db.downloadDao().byEpisode(1))
            assertNull(db.artworkDao().byKey("u-x"))
            assertTrue(db.chapterDao().ofSource(1, ChapterSource.PSC).isEmpty())
            assertTrue(db.credentialDao().byOrigin("https://example.com").isEmpty())
            assertTrue(db.importDao().itemsOf(1).isEmpty())
            assertTrue(db.syncOutboxDao().rows().isEmpty())
            assertNull(db.syncClockDao().get("podcast", "rid"))
            assertTrue(db.syncParkedDao().forPodcast("rid").isEmpty())
            assertEquals(0, db.syncHeldDao().count())

            assertEquals(1L, db.useWriterConnection { it.pragmaLong() })
            assertEquals(1L, db.useReaderConnection { it.pragmaLong() })
        } finally {
            db.close()
        }
    }

    private suspend fun androidx.room3.PooledConnection.pragmaLong(): Long =
        usePrepared("PRAGMA foreign_keys") { stmt ->
            check(stmt.step())
            stmt.getLong(0)
        }
}
