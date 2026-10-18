// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.FakeImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.NoPayloadSource
import ch.lkmc.neutrodyne.core.data.add.PreviewEntry
import ch.lkmc.neutrodyne.core.data.add.PreviewEntryResolver
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.importItem
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkPolicy
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.newRefresher
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.data.requestImportRestore
import ch.lkmc.neutrodyne.core.data.seedImportSession
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.data.testClients
import ch.lkmc.neutrodyne.core.data.testNetErrors
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
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
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertIs

/**
 * `RestoreWorker` over the real `RestoreRunner` (05 Restore): the durable intent decodes from
 * the request's input data, `Completed`/`Failed`/`NotPending`/`Retry` map onto WorkManager
 * results — `Failed` posts the restore-failure notice, `NotPending` posts nothing — and a
 * leftover durable intent re-enqueues through `APPEND_OR_REPLACE` rather than vanishing under
 * `KEEP` while this spec still owns the name. Backup payloads come from the real
 * `BackupWriter`; the session/claim fixtures mirror `desktopTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreWorkerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val settings = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val coordinator = FakeCredentialCommitCoordinator()
    private val channels = FakeGroupChannelSync()
    private val workScheduler = FakeImportWorkScheduler()
    private val refresh = FakeRefreshController()
    private val snapshots = FakeSnapshotScheduler()
    private val reports = FakeImportReportPoster()
    private val fileSystem = FileSystem.SYSTEM
    private val storagePaths get() = StoragePaths(app)

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
        java.io.File(app.cacheDir, PayloadStore.IMPORT_DIR).deleteRecursively()
        java.io.File(app.filesDir, "backup").deleteRecursively()
    }

    @Test
    fun `a session with no restore intent succeeds without posting a report`() =
        runBlocking<Unit> {
            val db = openDb()
            // A preview row without `restoreRequestedAt` — nothing durable to consume.
            val sessionId =
                db.seedImportSession(
                    options = ImportOptions(restoreMode = RestoreMode.MERGE),
                    format = ImportFormat.NEUTRODYNE_BACKUP,
                )

            val result = worker(db, sessionId).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(reports.restoreFailures).isEmpty()
            assertThat(restoreSpecs()).isEmpty()
        }

    @Test
    fun `a corrupt payload cancels the session and posts the failure`() =
        runBlocking<Unit> {
            val db = openDb()
            stash("import/bad.bin") { writeUtf8("not a zip") }
            val sessionId = backupSession(db, payloadPath = "import/bad.bin")

            val result = worker(db, sessionId).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(db.importDao().sessionById(sessionId)?.state).isEqualTo(ImportState.CANCELLED)
            assertThat(reports.restoreFailures.map { it.sessionId }).containsExactly(sessionId)
            assertThat(reports.restoreFailures.single().auto).isFalse()
        }

    @Test
    fun `a real archive restores its library and completes`() =
        runBlocking<Unit> {
            val db = openDb()
            val source = openSourceDb()
            val feedUrl = "https://restore.test/feed.xml"
            seedPodcast(source, feedUrl, feedKey = checkNotNull(UrlNormalizer.forIdentity(feedUrl)))
            val payloadPath = adopt(writeArchive(source))

            val sessionId = backupSession(db, payloadPath = payloadPath)
            val result = worker(db, sessionId).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            // The durable intent is consumed at COMMITTED; the fetch lane takes it to DONE.
            assertThat(db.importDao().sessionById(sessionId)?.state)
                .isEqualTo(ImportState.COMMITTED)
            assertThat(reports.restoreFailures).isEmpty()
            // The restore landed the source library's podcast in the target database and
            // handed the session to the fetch lane through the scheduler seam.
            assertThat(
                db.podcastDao().byFeedKey(checkNotNull(UrlNormalizer.forIdentity(feedUrl))),
            ).isNotNull()
            assertThat(workScheduler.scheduled)
                .containsExactly(sessionId to ImportWorkPolicy.KEEP)
        }

    @Test
    fun `a thrown lane error reschedules`() =
        runBlocking<Unit> {
            val db = openDb()
            val sessionId = backupSession(db, payloadPath = null)
            db.close()
            this@RestoreWorkerTest.db = null

            val result = worker(db, sessionId).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        }

    @Test
    fun `a leftover durable intent re-enqueues behind the running spec`() =
        runBlocking<Unit> {
            val db = openDb()
            // The run consumes one intent; a second durable intent from a killed dispatch
            // must not be swallowed by KEEP — it lands appended behind this spec.
            stash("import/bad.bin") { writeUtf8("not a zip") }
            val first = backupSession(db, payloadPath = "import/bad.bin")
            val second = backupSession(db, payloadPath = "import/bad.bin")

            val result = worker(db, first).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            // The runner consumed `first`'s intent (corrupt → CANCELLED); the leftover
            // durable intent got its own spec through the APPEND_OR_REPLACE follow-up.
            val specs = restoreSpecs()
            assertThat(specs.map { it.input.getLong("sessionId", -1L) })
                .containsExactly(second)
        }

    @Test
    fun `unrunnable intents stay protected without re-enqueueing themselves`() =
        runBlocking<Unit> {
            val db = openDb()
            val corrupt = backupSession(db, payloadPath = "import/unreadable.bin")
            db.importDao().setOptionsJson(corrupt, "{broken")
            val futureMode = backupSession(db, payloadPath = "import/future.bin")
            db.importDao().setOptionsJson(futureMode, """{"restoreRequestedAt":1,"restoreMode":"FUTURE"}""")
            val noPayload = backupSession(db, payloadPath = null)

            assertThat(worker(db, corrupt).doWork())
                .isInstanceOf(ListenableWorker.Result.Success::class.java)

            assertThat(restoreSpecs()).isEmpty()
            assertThat(db.importDao().sessionById(corrupt)?.optionsJson).isEqualTo("{broken")
            assertThat(db.importDao().sessionById(futureMode)?.state).isEqualTo(ImportState.PREVIEW)
            assertThat(db.importDao().sessionById(noPayload)?.state).isEqualTo(ImportState.PREVIEW)
            assertThat(reports.restoreFailures).isEmpty()
        }

    // -- Fixtures --------------------------------------------------------------------------------

    private fun openDb(): NeutrodyneDatabase = newDb(app, clock).also { db = it }

    private fun openSourceDb(): NeutrodyneDatabase = newDb(app, clock).also { sourceDb = it }

    /** A claimed `NEUTRODYNE_BACKUP` preview — the durable restore intent, stamped. */
    private suspend fun backupSession(
        db: NeutrodyneDatabase,
        payloadPath: String?,
        fromAndroidBackup: Boolean = false,
    ): Long {
        val sessionId =
            db.seedImportSession(
                options =
                    ImportOptions(
                        restoreMode = RestoreMode.MERGE,
                        restoreCategories = setOf(RestoreCategory.HISTORY),
                        fromAndroidBackup = fromAndroidBackup,
                    ),
                format = ImportFormat.NEUTRODYNE_BACKUP,
                payloadPath = payloadPath,
            )
        assertIs<Outcome.Success<*>>(db.requestImportRestore(sessionId, clock.now()))
        return sessionId
    }

    private fun stash(
        name: String,
        content: okio.BufferedSink.() -> Unit,
    ): String {
        val target = storagePaths.cacheDir.toPath() / name
        fileSystem.createDirectories(checkNotNull(target.parent))
        fileSystem.write(target) { content() }
        return name
    }

    /** `BackupWriter` on the source database → a copy stashed under the target's import cache. */
    private suspend fun writeArchive(source: NeutrodyneDatabase): okio.Path {
        val archive = storagePaths.cacheDir.toPath() / "source-archive.zip"
        val writer =
            BackupWriter(
                source,
                settings,
                secrets,
                ZipArchiveWriter(),
                ZipGuard(),
                OpmlExporter(source, secrets, clock, Dispatchers.Default),
                ch.lkmc.neutrodyne.core.data
                    .androidBuildInfo(),
                clock,
                fileSystem,
                Dispatchers.IO,
            )
        assertIs<Outcome.Success<*>>(writer.write(BackupKind.MANUAL, archive, includeCredentials = false))
        return archive
    }

    private fun adopt(archive: okio.Path): String {
        val target = storagePaths.cacheDir.toPath() / "import/restored.zip"
        fileSystem.createDirectories(checkNotNull(target.parent))
        fileSystem.copy(archive, target)
        return "import/restored.zip"
    }

    private fun runnerOf(db: NeutrodyneDatabase): RestoreRunner {
        val merger =
            RestoreMerger(
                db = db,
                podcasts = PodcastMatcher(db),
                writes = RestoreWrites(db),
                groupMerger = GroupMerger(db, clock, channels),
                unsubscribe =
                    UnsubscribeUseCase(
                        PodcastRepositoryImpl(
                            db = db,
                            refresher = newRefresher(app, db, emptyMap(), clock),
                            scheduler = FakeRefreshScheduler(),
                            sanitizer = JsoupShowNotesSanitizer(),
                            clock = clock,
                            merger = PodcastMerger(db, coordinator, clock),
                            resolver = unusedPreviewResolver,
                            secrets = secrets,
                            coordinator = coordinator,
                            fetcher = Provider { error("setCredentials is not wired in this fixture") },
                            tempFiles = FeedTempFiles(storagePaths, fileSystem),
                        ),
                        FakeNewEpisodePoster(),
                    ),
                secrets = secrets,
                coordinator = coordinator,
                channelSync = channels,
                orderKeys = ProtocolOrderKeys,
                clock = clock,
            )
        return RestoreRunner(
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
            payloads =
                PayloadStore(
                    storagePaths = storagePaths,
                    fileSystem = fileSystem,
                    payloads = NoPayloadSource(),
                    httpClients = testClients(),
                    netErrors = testNetErrors,
                    io = Dispatchers.IO,
                ),
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            workScheduler = workScheduler,
            refresh = refresh,
            channels = channels,
            snapshots = snapshots,
            clock = clock,
            io = Dispatchers.IO,
        )
    }

    private fun worker(
        db: NeutrodyneDatabase,
        sessionId: Long?,
        auto: Boolean = false,
    ): RestoreWorker {
        val scheduler = importWorkScheduler()
        val runner = runnerOf(db)
        val factory =
            object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = RestoreWorker(appContext, workerParameters, runner, scheduler, reports, db)
            }
        val builder =
            TestListenableWorkerBuilder
                .from(app, RestoreWorker::class.java)
                .setWorkerFactory(factory)
        if (sessionId != null) {
            builder.setInputData(RestoreWorkData.dataOf(sessionId, auto))
        }
        return builder.build()
    }

    /** The real scheduler; kept as a helper type so the fixture reads like the graph's wiring. */
    private fun importWorkScheduler() =
        ch.lkmc.neutrodyne.core.data.importing
            .WorkManagerImportWorkScheduler(app)

    private fun restoreSpecs(): List<androidx.work.impl.model.WorkSpec> =
        workManager
            .getWorkInfosForUniqueWork(RestoreWorkData.WORK_RESTORE)
            .get()
            .map {
                checkNotNull(
                    (workManager as WorkManagerImpl)
                        .workDatabase
                        .workSpecDao()
                        .getWorkSpec(it.id.toString()),
                )
            }

    private companion object {
        val unusedPreviewResolver =
            object : PreviewEntryResolver {
                override suspend fun probe(input: String): Outcome<PreviewEntry, AddPodcastError> =
                    error("Unexpected feed probe")

                override suspend fun resolveEntry(feedUrl: String): Outcome<PreviewEntry, AddPodcastError> =
                    error("Unexpected preview resolution")
            }
    }
}
