// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.backup.BackupStager
import ch.lkmc.neutrodyne.core.data.backup.BackupWriter
import ch.lkmc.neutrodyne.core.data.backup.EpisodeMatcher
import ch.lkmc.neutrodyne.core.data.backup.PodcastMatcher
import ch.lkmc.neutrodyne.core.data.backup.RestoreEpisodeMerger
import ch.lkmc.neutrodyne.core.data.backup.RestoreMerger
import ch.lkmc.neutrodyne.core.data.backup.RestoreRunResult
import ch.lkmc.neutrodyne.core.data.backup.RestoreRunner
import ch.lkmc.neutrodyne.core.data.backup.RestoreSettingsMerge
import ch.lkmc.neutrodyne.core.data.backup.RestoreWrites
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.ImportClassifier
import ch.lkmc.neutrodyne.core.data.importing.ImportCommitter
import ch.lkmc.neutrodyne.core.data.importing.ImportSessions
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeSubscriptionWriterImpl
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeDownloadLifecycle
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeImportReportPoster
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.feeds.opml.ExportDocument
import ch.lkmc.neutrodyne.feeds.opml.ExportFeed
import ch.lkmc.neutrodyne.feeds.opml.ExportGroup
import ch.lkmc.neutrodyne.feeds.opml.OpmlLayout
import ch.lkmc.neutrodyne.feeds.opml.OpmlWriter
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * PLAN M3 acceptance 12's journey legs (the `core:database` `SyncInertTest` covers the DAO-level
 * scope note; this class repeats its assertion over the real hosts). With no sync server
 * configured, `sync_outbox`, `sync_clock`, `sync_parked` and `sync_held` stay empty and
 * `sync_state` keeps its `onCreate` default row through a real grouped-OPML
 * `ImportSessions.create` → `ImportCommitter.confirm` and through real `BackupWriter` →
 * `BackupStager` → `RestoreRunner` Merge replays.
 *
 * Identity rules (02 Podcast syncId): every inserted podcast gets a unique lowercase UUIDv4;
 * restore adopts the backup's `syncId` only when no local row uses it and generates a fresh one
 * on a real collision. A matched podcast is never re-inserted, so its local `syncId` is
 * untouched — `feedKey`/alias, not `syncId`, is the merge identity.
 */
class SyncInertTest {
    private val clock = TestClock()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var root: File
    private lateinit var storagePaths: StoragePaths
    private var nextArchive = 0

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("nd-sync-inert").toFile()
        storagePaths = storagePathsFor(root)
    }

    @AfterTest
    fun tearDown() {
        appScope.cancel()
        root.deleteRecursively()
    }

    private class Harness(
        val db: NeutrodyneDatabase,
        val payloads: FakePayloadSource,
        val sessions: ImportSessions,
        val committer: ImportCommitter,
        val runner: RestoreRunner,
    )

    private fun newHarness(): Harness {
        val db = TestDb.inMemory(clock = clock)
        val secrets = FakeSecretStore()
        val coordinator = FakeCredentialCommitCoordinator()
        val channels = FakeGroupChannelSync()
        val workScheduler = FakeImportWorkScheduler()
        val payloads = FakePayloadSource()
        val payloadStore =
            PayloadStore(
                storagePaths = storagePaths,
                fileSystem = FileSystem.SYSTEM,
                payloads = payloads,
                httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
                netErrors = testClassifier,
                io = Dispatchers.Default,
            )
        val sessions =
            ImportSessions(
                db = db,
                payloads = payloadStore,
                classifier = ImportClassifier(db, YouTubeUrlClassifier()),
                opmlReader = XmlPullOpmlReader.discovered(),
                archives = ZipGuard(),
                clock = clock,
                reports = FakeImportReportPoster(),
                appScope = appScope,
                io = Dispatchers.Default,
            )
        val committer =
            ImportCommitter(
                db = db,
                secrets = secrets,
                coordinator = coordinator,
                workScheduler = workScheduler,
                refresh = FakeRefreshController(),
                channels = channels,
                orderKeys = ProtocolOrderKeys,
                clock = clock,
                channelWriter = YouTubeSubscriptionWriterImpl(db, clock, ProtocolOrderKeys),
                newSyncId = { Uuid.random().toString() },
            )
        val merger =
            RestoreMerger(
                db = db,
                podcasts = PodcastMatcher(db),
                writes = RestoreWrites(db),
                groupMerger = GroupMerger(db, clock, channels),
                unsubscribe = newUnsubscribe(db, FakeRefreshScheduler(), clock, secrets, coordinator),
                secrets = secrets,
                coordinator = coordinator,
                channelSync = channels,
                orderKeys = ProtocolOrderKeys,
                clock = clock,
                downloads = Provider { FakeDownloadLifecycle { db.downloadDao().removeUnclaimed(it) } },
            )
        val runner =
            RestoreRunner(
                db = db,
                stager = BackupStager(db, ZipGuard(), merger, FileSystem.SYSTEM, Dispatchers.IO),
                merger = merger,
                episodes =
                    RestoreEpisodeMerger(
                        db = db,
                        episodes = EpisodeMatcher(db),
                        writes = RestoreWrites(db),
                        orderKeys = ProtocolOrderKeys,
                        clock = clock,
                    ),
                settingsMerge = RestoreSettingsMerge(FakeSettingsRepository()),
                payloads = payloadStore,
                storagePaths = storagePaths,
                fileSystem = FileSystem.SYSTEM,
                workScheduler = workScheduler,
                refresh = FakeRefreshController(),
                channels = channels,
                snapshots = FakeSnapshotScheduler(),
                clock = clock,
                io = Dispatchers.IO,
            )
        return Harness(db, payloads, sessions, committer, runner)
    }

    private suspend fun assertSyncInert(db: NeutrodyneDatabase) {
        assertEquals(0, db.syncOutboxDao().count(), "sync_outbox")
        assertEquals(0, db.syncClockDao().count(), "sync_clock")
        assertEquals(0, db.syncParkedDao().count(), "sync_parked")
        assertEquals(0, db.syncHeldDao().count(), "sync_held")
        assertEquals(SyncStateEntity(), db.syncStateDao().get(), "sync_state stays the unlinked default")
    }

    /** A grouped OPML through the real writer, straight out of an [ExportDocument]. */
    private fun opmlBytes(
        groups: List<String>,
        feeds: List<Pair<String, List<String>>>,
    ): ByteArray {
        val groupModels = groups.mapIndexed { i, name -> ExportGroup("k$i", name, null, null) }
        val document =
            ExportDocument(
                title = "Sync inert fixture",
                createdAt = 1_700_000_000_000L,
                groups = groupModels,
                feeds =
                    feeds.mapIndexed { i, (url, groupNames) ->
                        ExportFeed(
                            displayTitle = "Show $i",
                            feedTitle = "Show $i",
                            xmlUrl = url,
                            htmlUrl = null,
                            language = null,
                            groupKeys = groupNames.map { name -> groupModels[groups.indexOf(name)].key },
                            youtube = false,
                            ytVariants = null,
                        )
                    },
            )
        val buffer = Buffer()
        OpmlWriter.write(buffer, document, OpmlLayout.GROUPED)
        return buffer.readByteArray()
    }

    private suspend fun createAndConfirm(
        harness: Harness,
        bytes: ByteArray,
    ) {
        harness.payloads.register(OPML_URI, bytes = bytes, displayName = "feeds.opml")
        val created = harness.sessions.create(ImportSource(OPML_URI, "feeds.opml"), CreateImportOptions())
        val sessionId = assertIs<Outcome.Success<Long>>(created).value
        assertIs<Outcome.Success<*>>(harness.committer.confirm(sessionId))
    }

    /** A real `BackupWriter` archive holding one podcast with the given staged `syncId`. */
    private suspend fun archiveWithSyncId(
        feedUrl: String,
        syncId: String,
    ): Path {
        val sourceDb = TestDb.inMemory(clock = clock)
        try {
            sourceDb
                .podcastDao()
                .insertPodcast(
                    podcastEntity(feedUrl = feedUrl, feedKey = feedKey(feedUrl), title = "Show", syncId = syncId),
                )
            val archive = root.absolutePath.toPath() / "archive-${nextArchive++}.zip"
            val writer =
                BackupWriter(
                    sourceDb,
                    FakeSettingsRepository(),
                    FakeSecretStore(),
                    ZipArchiveWriter(),
                    ZipGuard(),
                    OpmlExporter(sourceDb, FakeSecretStore(), clock, Dispatchers.Default),
                    testBuildInfo(),
                    clock,
                    FileSystem.SYSTEM,
                    Dispatchers.IO,
                )
            assertIs<Outcome.Success<*>>(writer.write(BackupKind.MANUAL, archive, includeCredentials = false))
            return archive
        } finally {
            sourceDb.close()
        }
    }

    private suspend fun restoreMerge(
        harness: Harness,
        archive: Path,
    ) {
        val payloadPath = "import/restore-$nextArchive.bin"
        val staged = storagePaths.cacheDir.toPath() / payloadPath
        FileSystem.SYSTEM.createDirectories(checkNotNull(staged.parent))
        FileSystem.SYSTEM.copy(archive, staged)
        val sessionId =
            harness.db.seedImportSession(
                options =
                    ImportOptions(
                        restoreMode = RestoreMode.MERGE,
                        restoreCategories = setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT),
                    ),
                format = ImportFormat.NEUTRODYNE_BACKUP,
                payloadPath = payloadPath,
            )
        assertIs<Outcome.Success<*>>(harness.db.requestImportRestore(sessionId, clock.now()))
        assertIs<RestoreRunResult.Completed>(harness.runner.run(sessionId))
    }

    @Test
    fun importCommitAssignsUniqueSyncIdsAndStaysInert() =
        runTest {
            val harness = newHarness()
            try {
                // An already-subscribed row with its own syncId — matched, never re-inserted.
                val existing =
                    harness.db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                feedUrl = FEED_ONE,
                                feedKey = feedKey(FEED_ONE),
                                syncId = LOCAL_SYNC_ID,
                            ),
                        )

                createAndConfirm(
                    harness,
                    opmlBytes(
                        groups = listOf("news", "tech"),
                        feeds =
                            listOf(
                                FEED_ONE to listOf("news"),
                                FEED_TWO to listOf("news", "tech"),
                                FEED_THREE to listOf("news"),
                            ),
                    ),
                )

                // feedKey match kept the local row — id and syncId untouched.
                val matched = assertNotNull(harness.db.podcastDao().byFeedKey(feedKey(FEED_ONE)))
                assertEquals(existing, matched.id)
                assertEquals(LOCAL_SYNC_ID, matched.syncId)

                // Every inserted podcast gets its own fresh, lowercase UUIDv4.
                val inserted =
                    listOf(FEED_TWO, FEED_THREE).map {
                        assertNotNull(harness.db.podcastDao().byFeedKey(feedKey(it))).syncId
                    }
                assertEquals(2, inserted.toSet().size, "a confirm must not reuse a generated syncId")
                for (syncId in inserted) {
                    assertTrue(syncId.matches(UUID_V4), "syncId $syncId is not a lowercase UUIDv4")
                    assertNotEquals(LOCAL_SYNC_ID, syncId)
                }

                // The membership landed on the group the file named.
                val tech =
                    harness.db
                        .groupDao()
                        .groupsInOrder()
                        .single { it.name == "tech" }
                assertEquals(
                    listOf(feedKey(FEED_TWO)),
                    harness.db.groupDao().membersOf(tech.id).map {
                        checkNotNull(harness.db.podcastDao().byId(it.podcastId)).feedKey
                    },
                )

                assertSyncInert(harness.db)
            } finally {
                harness.db.close()
            }
        }

    @Test
    fun mergeRestoreAdoptsUnusedBackupSyncIdsAndStaysInert() =
        runTest {
            val harness = newHarness()
            try {
                val archive = archiveWithSyncId(FEED_ONE, STAGED_SYNC_ID)
                restoreMerge(harness, archive)

                val restored = assertNotNull(harness.db.podcastDao().byFeedKey(feedKey(FEED_ONE)))
                assertEquals(STAGED_SYNC_ID, restored.syncId, "unused staged syncId is adopted")
                assertSyncInert(harness.db)
            } finally {
                harness.db.close()
            }
        }

    @Test
    fun mergeRestoreGeneratesFreshSyncIdOnCollisionAndStaysInert() =
        runTest {
            val harness = newHarness()
            try {
                // A local podcast (different feedKey → no match) already holds the staged id.
                val holder =
                    harness.db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                feedUrl = FEED_TWO,
                                feedKey = feedKey(FEED_TWO),
                                syncId = STAGED_SYNC_ID,
                            ),
                        )

                restoreMerge(harness, archiveWithSyncId(FEED_ONE, STAGED_SYNC_ID))

                val restored = assertNotNull(harness.db.podcastDao().byFeedKey(feedKey(FEED_ONE)))
                assertNotEquals(STAGED_SYNC_ID, restored.syncId, "collision must generate a fresh syncId")
                assertTrue(restored.syncId.matches(UUID_V4))
                // The holder keeps the id — syncIds stay unique across the table.
                assertEquals(STAGED_SYNC_ID, assertNotNull(harness.db.podcastDao().byId(holder)).syncId)
                assertEquals(
                    2,
                    harness.db
                        .podcastDao()
                        .allForExport()
                        .map { it.syncId }
                        .toSet()
                        .size,
                    "syncIds unique per podcast",
                )
                assertSyncInert(harness.db)
            } finally {
                harness.db.close()
            }
        }

    private fun feedKey(feedUrl: String): String = checkNotNull(UrlNormalizer.forIdentity(feedUrl))

    private companion object {
        const val OPML_URI = "file:///feeds.opml"
        const val FEED_ONE = "https://one.example.com/feed.xml"
        const val FEED_TWO = "https://two.example.com/feed.xml"
        const val FEED_THREE = "https://three.example.com/feed.xml"
        const val LOCAL_SYNC_ID = "bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb"
        const val STAGED_SYNC_ID = "dddddddd-2222-4222-8222-dddddddddddd"
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
