// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.maintenance.DbMaintenanceImpl
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncParkedEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.DbMaintenance
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.importSessionEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.FileSystem
import okio.Path
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * `DbMaintenanceImpl`'s step composition (02 db-maintenance worker) against a real database:
 * retention inside `withApplying` with no outbox writes, orphan sweeps, the credential sweep
 * under `CredentialCommitCoordinator`, linked-only parked expiry, `PRAGMA optimize` +
 * `quick_check`, the `VACUUM` guards and the `diagnostics.maintenance_*` stats record.
 */
class DbMaintenanceTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val settings = FakeSettingsRepository()
    private val credentials = FakeCredentialCommitCoordinator()
    private val artworks = FakeArtworkStore()

    private fun maintenance(usableSpace: Long? = null): DbMaintenance =
        DbMaintenanceImpl(
            db = db,
            factory = FakeDatabaseFactory(usableSpace),
            importSessionCleaner = cleaner(),
            artworkStore = artworks,
            credentials = credentials,
            settings = settings,
            clock = clock,
            io = Dispatchers.Default,
        )

    private fun cleaner(): ImportSessionCleaner {
        val root =
            kotlin.io.path
                .createTempDirectory("nd-maint")
                .toFile()
        return ImportSessionCleaner(
            db,
            PayloadStore(
                storagePaths = storagePathsFor(root),
                fileSystem = FileSystem.SYSTEM,
                payloads = FakePayloadSource(),
                httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
                netErrors = testClassifier,
                io = Dispatchers.Default,
            ),
            clock,
            Dispatchers.Default,
        )
    }

    private suspend fun seedGoneEpisode(
        podcastId: Long,
        key: String,
        sortDate: Long,
    ): Long =
        db.episodeDao().insertStub(
            episodeEntity(podcastId = podcastId, identityKey = key, sortDate = sortDate) {
                copy(inFeed = false, lastSeenAt = clock.now() - 100L * 86_400_000)
            },
        )

    @Test
    fun fullRunSweepsAndRecordsStatsWithoutTouchingTheOutbox() =
        runTest {
            val pid = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k", syncId = "sync-1"))
            db.episodeDao().insertStub(
                episodeEntity(podcastId = pid, identityKey = "newest", sortDate = clock.now()),
            )
            val gone = seedGoneEpisode(pid, "k1", sortDate = 5L)
            db.syncStateDao().upsert(
                SyncStateEntity(enabled = true, serverUrl = "https://sync.example", linkedAt = clock.now()),
            )
            db.syncClockDao().upsert(SyncClockEntity("episode", "sync-1k1", "{}"))
            db
                .syncParkedDao()
                .park(SyncParkedEntity(podcastSyncId = "p", identityKey = "k", record = "{}", receivedAt = 0L))
            db
                .importDao()
                .let {
                    it.insertSession(
                        importSessionEntity(state = ImportState.DONE, createdAt = 0, finishedAt = 0),
                    )
                }
            db.useWriterConnection { conn ->
                conn.usePrepared("INSERT INTO person(ownerType, ownerId, name) VALUES ('EPISODE', 999999, 'x')") {
                    it.step()
                }
            }

            maintenance().run(clock.now())

            assertEquals(1, db.maintenanceDao().episodeCount())
            assertEquals(0, db.syncClockDao().count())
            // D23: never a sync push.
            assertEquals(0, db.syncOutboxDao().count())
            // Step 3a: the 1970-dated parked row expired because the device is linked.
            assertEquals(0, db.syncParkedDao().count())
            // Step 3: the DONE session row is gone.
            assertEquals(0, expiredSessionCount())
            assertEquals(1, credentials.commits)
            assertEquals(1, artworks.collections)

            val at = settings.get(DiagnosticsSettingKeys.MAINTENANCE_LAST_RUN_AT)
            assertEquals(clock.now(), at)
            val stats = Json.parseToJsonElement(settings.get(DiagnosticsSettingKeys.MAINTENANCE_STATS))
            assertEquals(1, stats.jsonObject["episodesDeleted"]?.jsonPrimitive?.int)
            assertEquals("ok", stats.jsonObject["quickCheck"]?.jsonPrimitive?.content)
            assertEquals(1, stats.jsonObject["episodes"]?.jsonPrimitive?.int)
            assertNotEquals(0L, stats.jsonObject["pageCount"]?.jsonPrimitive?.long)
        }

    @Test
    fun aMidSweepFailureKeepsTheCommittedDeleteCount() =
        runTest {
            val pid = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k"))
            db.episodeDao().insertStub(
                episodeEntity(podcastId = pid, identityKey = "newest", sortDate = clock.now()),
            )
            // One full batch of candidates commits, then the doomed row aborts batch two.
            repeat(500) { seedGoneEpisode(pid, "g$it", sortDate = 5L) }
            seedGoneEpisode(pid, "doom", sortDate = 4L)
            db.useWriterConnection { conn ->
                conn.usePrepared(
                    "CREATE TEMP TRIGGER retention_doom AFTER DELETE ON episode" +
                        " WHEN OLD.identityKey = 'doom' BEGIN SELECT RAISE(ABORT, 'doom'); END",
                ) {
                    it.step()
                }
            }

            maintenance().run(clock.now())

            val stats = Json.parseToJsonElement(settings.get(DiagnosticsSettingKeys.MAINTENANCE_STATS))
            assertEquals(
                500,
                stats.jsonObject["episodesDeleted"]?.jsonPrimitive?.int,
                "committed batches must still count when a later batch fails",
            )
            assertEquals(2, db.maintenanceDao().episodeCount(), "newest + aborted doom row remain")
        }

    @Test
    fun parkedRowsSurviveWhileUnlinked() =
        runTest {
            db
                .syncParkedDao()
                .park(SyncParkedEntity(podcastSyncId = "p", identityKey = "k", record = "{}", receivedAt = 0L))

            maintenance().run(clock.now())

            assertEquals(1, db.syncParkedDao().count())
        }

    @Test
    fun deadlineStopsBeforeLaterStepsAndMarksTheRecord() =
        runTest {
            val pid = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k"))
            seedGoneEpisode(pid, "a", sortDate = 4L)
            seedGoneEpisode(pid, "b", sortDate = 3L)
            db.episodeDao().insertStub(
                episodeEntity(podcastId = pid, identityKey = "newest", sortDate = clock.now()),
            )

            // A deadline already passed: step 1 still runs one guarded loop, nothing else.
            maintenance().run(clock.now(), deadlineElapsedMs = clock.elapsedRealtime())

            val stats = Json.parseToJsonElement(settings.get(DiagnosticsSettingKeys.MAINTENANCE_STATS))
            assertEquals("true", stats.jsonObject["stoppedEarly"]?.jsonPrimitive?.content)
            assertEquals(0, artworks.collections)
        }

    @Test
    fun vacuumStaysSkippedBelowTheFragmentationThreshold() =
        runTest {
            maintenance(usableSpace = Long.MAX_VALUE).run(clock.now())
            val stats = Json.parseToJsonElement(settings.get(DiagnosticsSettingKeys.MAINTENANCE_STATS))
            assertTrue(
                stats.jsonObject["vacuum"]
                    ?.jsonPrimitive
                    ?.content
                    ?.startsWith("skipped") == true,
            )
        }

    @Test
    fun vacuumRunsWhenTheGuardsPass() =
        runTest {
            inflateFreelist()
            // The seeded play_session row counts as activity for VACUUM_IDLE_MS after open.
            clock.advanceBy(11.minutes)

            maintenance(usableSpace = Long.MAX_VALUE).run(clock.now())

            val stats = Json.parseToJsonElement(settings.get(DiagnosticsSettingKeys.MAINTENANCE_STATS))
            assertEquals("done", stats.jsonObject["vacuum"]?.jsonPrimitive?.content)
        }

    @Test
    fun aFailedVacuumAttemptRecordsFailedNotSkipped() =
        runTest {
            inflateFreelist()
            clock.advanceBy(11.minutes)

            maintenance(usableSpace = Long.MAX_VALUE)
                .run(clock.now(), vacuumBlocker = { error("probe down") })

            val stats = Json.parseToJsonElement(settings.get(DiagnosticsSettingKeys.MAINTENANCE_STATS))
            assertEquals(
                "failed",
                stats.jsonObject["vacuum"]?.jsonPrimitive?.content,
                "a vacuum attempt that throws must not read as skipped",
            )
        }

    /** Drops ~14 MB of padding so the freelist clears both vacuum size guards. */
    private suspend fun inflateFreelist() =
        db.useWriterConnection { conn ->
            conn.usePrepared("CREATE TABLE pad(x)") { it.step() }
            conn.usePrepared(
                "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < 700)" +
                    " INSERT INTO pad SELECT randomblob(20000) FROM c",
            ) {
                it.step()
            }
            conn.usePrepared("DROP TABLE pad") { it.step() }
        }

    private suspend fun expiredSessionCount(): Int =
        db.useWriterConnection { conn ->
            conn.usePrepared("SELECT COUNT(*) FROM import_session") { stmt ->
                stmt.step()
                stmt.getLong(0).toInt()
            }
        }

    private class FakeDatabaseFactory(
        private val usable: Long?,
    ) : DatabaseFactory {
        override val databasePath: String = ""

        override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> = error("unused")

        override fun exists(): Boolean = error("unused")

        override fun usableSpaceBytes(): Long? = usable

        override fun quarantine(stamp: String) = error("unused")

        override fun pruneQuarantine(now: Long) = error("unused")

        override var quarantineMarker: Boolean
            get() = error("unused")
            set(value) = error("unused")
        override var pendingQuarantine: String?
            get() = error("unused")
            set(value) = error("unused")
    }

    private class FakeArtworkStore : ArtworkStore {
        var collections = 0

        override fun pinnedFile(key: String): Path? = null

        override fun pin(
            ref: ArtworkRef,
            reason: PinReason,
            ownerId: Long,
        ) = Unit

        override fun unpin(
            key: String,
            reason: PinReason,
            ownerId: Long,
        ) = Unit

        override fun contentUri(
            key: String,
            version: Int,
        ): String = ""

        override fun isPinned(key: String): Boolean = false

        override fun pinnedPath(key: String): Path? = null

        override suspend fun collectGarbage(): Int {
            collections++
            return 0
        }
    }
}
