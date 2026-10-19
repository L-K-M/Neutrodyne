// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.FakeImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.NoPayloadSource
import ch.lkmc.neutrodyne.core.data.add.PreviewEntry
import ch.lkmc.neutrodyne.core.data.add.PreviewEntryResolver
import ch.lkmc.neutrodyne.core.data.androidBuildInfo
import ch.lkmc.neutrodyne.core.data.exporting.ExportDestination
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.importing.ImportClassifier
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.ImportSessions
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.importing.hasRestoreIntent
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.newRefresher
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.data.seedImportSession
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.data.testClients
import ch.lkmc.neutrodyne.core.data.testNetErrors
import ch.lkmc.neutrodyne.core.database.AndroidDatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeImportReportPoster
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertIs

/**
 * `FirstLaunchRestoreInitializer` (order 110, 05 First-launch restore) over the real
 * `DatabaseOpener`/`AndroidDatabaseFactory` open — `OpenResult.created` is the signal,
 * including the quarantine replacement — the real `BackupRepositoryImpl` adoption lane
 * and a real `WorkManager` test backend: a created database with a snapshot becomes a
 * `NEUTRODYNE_BACKUP` session whose durable intent asks MERGE for HISTORY and UP_NEXT and
 * whose `backup-restore` spec carries `auto`; an existing database only posts the
 * foreign-snapshot offer. `run()` never awaits the restore itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstLaunchRestoreInitializerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val settings = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val reports = FakeImportReportPoster()
    private val fileSystem = FileSystem.SYSTEM
    private val storagePaths get() = StoragePaths(app)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var db: NeutrodyneDatabase? = null
    private var sourceDb: NeutrodyneDatabase? = null

    private val workManager get() = WorkManager.getInstance(app)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
        db?.close()
        sourceDb?.close()
        appScope.cancel()
        File(app.cacheDir, "import").deleteRecursively()
        File(app.filesDir, "backup").deleteRecursively()
        app.getDatabasePath(NeutrodyneDatabase.FILE_NAME).delete()
    }

    @Test
    fun `a created database adopts the snapshot and requests a merge restore`() =
        runTest {
            writeSnapshot()
            val opener = openDatabase(created = true)
            val db = checkNotNull(this@FirstLaunchRestoreInitializerTest.db)
            val scheduler = FakeImportWorkScheduler()

            initializer(opener, db, scheduler).run()

            // The real adopt pipeline staged the snapshot into a NEUTRODYNE_BACKUP session.
            val session = db.importDao().backupPreviewSessions().single()
            assertThat(session.sourceFormat).isEqualTo(ImportFormat.NEUTRODYNE_BACKUP)
            val options = ImportOptionsJson.decode(checkNotNull(session.optionsJson))
            assertThat(options.fromAndroidBackup).isTrue()
            assertThat(session.hasRestoreIntent()).isTrue()
            // 05: MERGE, never SETTINGS or REPLACE; UP_NEXT rides with HISTORY.
            assertThat(options.restoreMode).isEqualTo(RestoreMode.MERGE)
            assertThat(options.restoreCategories)
                .containsExactly(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT)
            // The owed enqueue went out with `auto` for RestoreWorker's wording.
            assertThat(scheduler.restores).containsExactly(session.id to true)
            // The restore did not run: the session still sits in PREVIEW and the snapshot
            // file stands until a successful restore retires it.
            assertThat(session.state).isEqualTo(ImportState.PREVIEW)
            assertThat(fileSystem.exists(snapshotFile())).isTrue()
            assertThat(reports.foreignPosts).isEqualTo(0)
        }

    @Test
    fun `a created database without a snapshot does nothing`() =
        runTest {
            val opener = openDatabase(created = true)
            val db = checkNotNull(this@FirstLaunchRestoreInitializerTest.db)
            val scheduler = FakeImportWorkScheduler()

            initializer(opener, db, scheduler).run()

            assertThat(db.importDao().backupPreviewSessions()).isEmpty()
            assertThat(scheduler.restores).isEmpty()
            assertThat(reports.foreignPosts).isEqualTo(0)
        }

    @Test
    fun `an existing database with a foreign snapshot offers the notification only`() =
        runTest {
            writeSnapshot()
            val opener = openDatabase(created = false)
            val db = checkNotNull(this@FirstLaunchRestoreInitializerTest.db)
            val scheduler = FakeImportWorkScheduler()

            initializer(opener, db, scheduler).run()

            // Nothing was adopted or requested; the offer is banner/status plus 3004.
            assertThat(db.importDao().backupPreviewSessions()).isEmpty()
            assertThat(scheduler.restores).isEmpty()
            assertThat(reports.foreignPosts).isEqualTo(1)
            assertThat(fileSystem.exists(snapshotFile())).isTrue()
        }

    @Test
    fun `an existing database without a snapshot does nothing`() =
        runTest {
            val opener = openDatabase(created = false)
            val db = checkNotNull(this@FirstLaunchRestoreInitializerTest.db)
            val scheduler = FakeImportWorkScheduler()

            initializer(opener, db, scheduler).run()

            assertThat(db.importDao().backupPreviewSessions()).isEmpty()
            assertThat(scheduler.restores).isEmpty()
            assertThat(reports.foreignPosts).isEqualTo(0)
        }

    @Test
    fun `a pending restore intent is reused rather than duplicated`() =
        runTest {
            writeSnapshot()
            val opener = openDatabase(created = false)
            val db = checkNotNull(this@FirstLaunchRestoreInitializerTest.db)
            // A durable intent left by a claimed restore whose process died before or
            // during the enqueue — band 200 replays it, not this initializer.
            db.seedImportSession(
                options =
                    ImportOptions(
                        restoreMode = RestoreMode.MERGE,
                        restoreCategories = setOf(RestoreCategory.HISTORY),
                        fromAndroidBackup = true,
                        restoreRequestedAt = 1L,
                    ),
                format = ImportFormat.NEUTRODYNE_BACKUP,
                payloadPath = "import/staged.bin",
            )
            val scheduler = FakeImportWorkScheduler()

            initializer(opener, db, scheduler).run()

            // The durable intent's replay is band 200's reconciliation — this initializer
            // neither stamps a second intent nor drops the pending offer.
            assertThat(db.importDao().backupPreviewSessions().count { it.hasRestoreIntent() })
                .isEqualTo(1)
            assertThat(scheduler.restores).isEmpty()
            assertThat(reports.foreignPosts).isEqualTo(1)
        }

    // -- Fixtures --------------------------------------------------------------------------------

    /**
     * A real open through `DatabaseOpener`/`AndroidDatabaseFactory`: `created = false` is
     * a second opener over the file the first open created — what the next process start
     * sees. `assertThat(result.created)` proves the signal the initializer reads.
     */
    private suspend fun openDatabase(created: Boolean): DatabaseOpener {
        if (!created) {
            val first =
                DatabaseOpener(
                    factory = AndroidDatabaseFactory(app),
                    driver = AndroidSQLiteDriver(),
                    io = Dispatchers.IO,
                    clock = clock,
                    strictMigrations = false,
                )
            first.awaitOpen()
            first.requireDatabase().close()
        }
        val opener =
            DatabaseOpener(
                factory = AndroidDatabaseFactory(app),
                driver = AndroidSQLiteDriver(),
                io = Dispatchers.IO,
                clock = clock,
                strictMigrations = false,
            )
        val result = opener.awaitOpen()
        assertThat(result.created).isEqualTo(created)
        db = opener.requireDatabase()
        return opener
    }

    /** A real archive written by `BackupWriter`; the empty settings keep it foreign. */
    private suspend fun writeSnapshot() {
        val source = newDb(app, clock).also { sourceDb = it }
        seedPodcast(source, "https://first-launch.test/feed.xml")
        val writer =
            BackupWriter(
                source,
                FakeSettingsRepository(),
                secrets,
                ZipArchiveWriter(),
                ZipGuard(),
                OpmlExporter(source, secrets, clock, Dispatchers.Default),
                androidBuildInfo(),
                clock,
                fileSystem,
                Dispatchers.IO,
            )
        val target = snapshotFile()
        fileSystem.createDirectories(checkNotNull(target.parent))
        assertIs<Outcome.Success<*>>(
            writer.write(BackupKind.AUTO_SNAPSHOT, target, includeCredentials = false),
        )
    }

    private fun snapshotFile(): Path = storagePaths.filesDir.toPath() / "backup" / AUTO_SNAPSHOT_FILE

    private fun initializer(
        opener: DatabaseOpener,
        db: NeutrodyneDatabase,
        scheduler: FakeImportWorkScheduler,
    ): FirstLaunchRestoreInitializer =
        FirstLaunchRestoreInitializer(
            opener = Provider { opener },
            backups = Provider { repository(db, scheduler) },
            producer = Provider { producer(db, scheduler) },
            reports = Provider { reports },
            fileSystem = fileSystem,
            io = Dispatchers.IO,
        )

    private fun repository(
        db: NeutrodyneDatabase,
        scheduler: FakeImportWorkScheduler,
    ): BackupRepositoryImpl {
        val payloads = payloads()
        val merger = restoreMerger(db)
        return BackupRepositoryImpl(
            db = db,
            writer = writer(db),
            stager = BackupStager(db, ZipGuard(), merger, fileSystem, Dispatchers.IO),
            runner = restoreRunner(db, merger, payloads, scheduler),
            sessions =
                ImportSessions(
                    db = db,
                    payloads = payloads,
                    classifier = ImportClassifier(db, YouTubeUrlClassifier()),
                    opmlReader = XmlPullOpmlReader.discovered(),
                    archives = ZipGuard(),
                    clock = clock,
                    reports = FakeImportReportPoster(),
                    appScope = appScope,
                    io = Dispatchers.IO,
                ),
            producer = producer(db, scheduler),
            destination = NoExportDestination,
            payloads = payloads,
            settings = settings,
            workScheduler = scheduler,
            clock = clock,
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            io = Dispatchers.IO,
        )
    }

    private fun writer(db: NeutrodyneDatabase) =
        BackupWriter(
            db,
            settings,
            secrets,
            ZipArchiveWriter(),
            ZipGuard(),
            OpmlExporter(db, secrets, clock, Dispatchers.Default),
            androidBuildInfo(),
            clock,
            fileSystem,
            Dispatchers.IO,
        )

    private fun producer(
        db: NeutrodyneDatabase,
        scheduler: FakeImportWorkScheduler,
    ) = AutoSnapshotProducer(
        db = db,
        writer = writer(db),
        cleaner = ImportSessionCleaner(db, payloads(), clock, Dispatchers.IO),
        readers = ZipGuard(),
        settings = settings,
        snapshots = FakeSnapshotScheduler(),
        workScheduler = scheduler,
        buildInfo = androidBuildInfo(),
        clock = clock,
        storagePaths = storagePaths,
        fileSystem = fileSystem,
        io = Dispatchers.IO,
    )

    private fun payloads() =
        PayloadStore(
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            payloads = NoPayloadSource(),
            httpClients = testClients(),
            netErrors = testNetErrors,
            io = Dispatchers.IO,
        )

    private fun restoreMerger(db: NeutrodyneDatabase): RestoreMerger =
        RestoreMerger(
            db = db,
            podcasts = PodcastMatcher(db),
            writes = RestoreWrites(db),
            groupMerger = GroupMerger(db, clock, FakeGroupChannelSync()),
            unsubscribe = unsubscribeUseCase(db),
            secrets = secrets,
            coordinator = FakeCredentialCommitCoordinator(),
            channelSync = FakeGroupChannelSync(),
            orderKeys = ProtocolOrderKeys,
            clock = clock,
        )

    private fun restoreRunner(
        db: NeutrodyneDatabase,
        merger: RestoreMerger,
        payloads: PayloadStore,
        scheduler: FakeImportWorkScheduler,
    ) = RestoreRunner(
        db = db,
        stager = BackupStager(db, ZipGuard(), merger, fileSystem, Dispatchers.IO),
        merger = merger,
        episodes =
            RestoreEpisodeMerger(
                db = db,
                episodes = EpisodeMatcher(db),
                writes = RestoreWrites(db),
                orderKeys = ProtocolOrderKeys,
                clock = clock,
            ),
        settingsMerge = RestoreSettingsMerge(settings),
        payloads = payloads,
        storagePaths = storagePaths,
        fileSystem = fileSystem,
        workScheduler = scheduler,
        refresh = FakeRefreshController(),
        channels = FakeGroupChannelSync(),
        snapshots = FakeSnapshotScheduler(),
        clock = clock,
        io = Dispatchers.IO,
    )

    private fun unsubscribeUseCase(db: NeutrodyneDatabase) =
        UnsubscribeUseCase(
            PodcastRepositoryImpl(
                db = db,
                refresher = newRefresher(app, db, emptyMap(), clock),
                scheduler = FakeRefreshScheduler(),
                sanitizer = JsoupShowNotesSanitizer(),
                clock = clock,
                merger = PodcastMerger(db, FakeCredentialCommitCoordinator(), clock),
                resolver = NoPreviewResolver,
                secrets = secrets,
                coordinator = FakeCredentialCommitCoordinator(),
                fetcher = Provider { error("setCredentials is not wired in this fixture") },
                tempFiles = FeedTempFiles(storagePaths, fileSystem),
            ),
            FakeNewEpisodePoster(),
        )

    /** `createBackup` is out of scope here; the seam only has to exist. */
    private object NoExportDestination : ExportDestination {
        override fun shareUri(fileName: String): String? = null

        override suspend fun save(
            source: Path,
            destinationUri: String,
        ): Outcome<Unit, ExportError> = Outcome.Failure(ExportError.DestinationUnavailable)
    }

    private companion object {
        val NoPreviewResolver =
            object : PreviewEntryResolver {
                override suspend fun probe(input: String): Outcome<PreviewEntry, AddPodcastError> =
                    error("Unexpected feed probe")

                override suspend fun resolveEntry(feedUrl: String): Outcome<PreviewEntry, AddPodcastError> =
                    error("Unexpected preview resolution")
            }
    }
}
