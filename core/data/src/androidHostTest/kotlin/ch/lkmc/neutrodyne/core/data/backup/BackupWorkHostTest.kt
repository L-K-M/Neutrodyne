// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.FakeImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.NoPayloadSource
import ch.lkmc.neutrodyne.core.data.androidBuildInfo
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.importing.WorkManagerImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.requestImportRestore
import ch.lkmc.neutrodyne.core.data.seedImportSession
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.data.testClients
import ch.lkmc.neutrodyne.core.data.testNetErrors
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The auto-backup work host over a real `WorkManager` test backend (05 Snapshot production,
 * the order-200 table): the debounced `backup-auto-snapshot-now` poke, the daily
 * `backup-auto-snapshot` arm, `AutoSnapshotWorker`'s producer outcomes and
 * `AutoBackupInitializer`'s durable-work reconciliation — restore intents and active import
 * sessions re-enqueue at start-up, a disabled flag drops the armed poke, and §9's session
 * sweep still runs while snapshots are off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class BackupWorkHostTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val settings = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val fileSystem = FileSystem.SYSTEM
    private val storagePaths get() = StoragePaths(app)
    private val snapshots get() = WorkManagerSnapshotScheduler(app)
    private val imports get() = WorkManagerImportWorkScheduler(app)

    private var db: NeutrodyneDatabase? = null

    private val workManager get() = WorkManager.getInstance(app)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
        db?.close()
        java.io.File(app.cacheDir, "import").deleteRecursively()
        java.io.File(app.filesDir, "backup").deleteRecursively()
    }

    // --- WorkManagerSnapshotScheduler -----------------------------------------------------------

    @Test
    fun `requestSoon arms the debounced poke and REPLACE keeps only the latest`() =
        runTest {
            snapshots.requestSoon()
            snapshots.requestSoon()

            val info = infoOf(RestoreWorkData.WORK_SNAPSHOT_NOW)
            assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
            assertThat(info.tags).contains(RestoreWorkData.TAG_BACKUP)
            val spec = specOf(info.id)
            assertThat(spec.initialDelay).isEqualTo(WorkManagerSnapshotScheduler.SOON_DELAY_MS)
            assertThat(spec.constraints.requiresBatteryNotLow()).isTrue()
            assertThat(snapshots.soonWorkLive()).isTrue()
        }

    @Test
    fun `armPeriodic lands the daily idle+charging update`() =
        runTest {
            snapshots.armPeriodic()

            val spec = specOf(infoOf(RestoreWorkData.WORK_SNAPSHOT).id)
            assertThat(spec.intervalDuration)
                .isEqualTo(TimeUnit.HOURS.toMillis(WorkManagerSnapshotScheduler.PERIODIC_HOURS))
            assertThat(spec.constraints.requiresDeviceIdle()).isTrue()
            assertThat(spec.constraints.requiresCharging()).isTrue()
        }

    @Test
    fun `cancelScheduled drops both the periodic and the armed poke`() =
        runTest {
            snapshots.armPeriodic()
            snapshots.requestSoon()
            snapshots.cancelScheduled()

            assertThat(infoOf(RestoreWorkData.WORK_SNAPSHOT).state).isEqualTo(WorkInfo.State.CANCELLED)
            assertThat(infoOf(RestoreWorkData.WORK_SNAPSHOT_NOW).state)
                .isEqualTo(WorkInfo.State.CANCELLED)
            assertThat(snapshots.soonWorkLive()).isFalse()
        }

    // --- AutoSnapshotWorker ----------------------------------------------------------------------

    @Test
    fun `the snapshot worker writes and reports success`() =
        runTest {
            val db = openDb()
            seedPodcast(db, "https://snap.test/feed.xml")

            val result = snapshotWorker(producerOf(db)).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
            val target = storagePaths.filesDir.toPath() / "backup" / "auto-snapshot.zip"
            assertThat(fileSystem.exists(target)).isTrue()
        }

    @Test
    fun `a disabled flag and restore-work guard both end the run as success`() =
        runTest {
            val db = openDb()
            settings.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, false)
            val target = storagePaths.filesDir.toPath() / "backup" / "auto-snapshot.zip"

            assertThat(snapshotWorker(producerOf(db)).doWork())
                .isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(fileSystem.exists(target)).isFalse()

            settings.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, true)
            val busy = FakeImportWorkScheduler().apply { restoreWork = true }
            assertThat(snapshotWorker(producerOf(db, busy)).doWork())
                .isInstanceOf(ListenableWorker.Result.Success::class.java)
            assertThat(fileSystem.exists(target)).isFalse()
        }

    // --- AutoBackupInitializer -------------------------------------------------------------------

    @Test
    fun `start-up arms the periodic and reconciles durable work`() =
        runTest {
            val db = openDb()
            val restoreSession =
                db.seedImportSession(
                    options =
                        ImportOptions(
                            restoreMode = RestoreMode.MERGE,
                            restoreCategories = setOf(RestoreCategory.HISTORY),
                        ),
                    format = ImportFormat.NEUTRODYNE_BACKUP,
                    payloadPath = "import/staged.zip",
                )
            assertThat(db.requestImportRestore(restoreSession, clock.now()))
                .isInstanceOf(ch.lkmc.neutrodyne.core.common.Outcome.Success::class.java)
            val fetchSession = db.seedImportSession(state = ImportState.COMMITTED)

            initializer(db).run()
            advanceUntilIdle()

            // The periodic UPDATE landed and the durable intents got their specs back.
            specOf(infoOf(RestoreWorkData.WORK_SNAPSHOT).id)
            awaitReal {
                infoOf(RestoreWorkData.WORK_RESTORE).let {
                    specOf(it.id).input.getLong("sessionId", -1L) ==
                        restoreSession
                }
            }
            awaitReal { infoOf("import-$fetchSession").state == WorkInfo.State.ENQUEUED }
        }

    @Test
    fun `a disabled flag clears the armed poke but keeps the sweep`() =
        runTest {
            val db = openDb()
            settings.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, false)
            snapshots.requestSoon()
            // An expired DONE session — §9's sweep must still run with snapshots off.
            val expired =
                db.seedImportSession(
                    state = ImportState.DONE,
                    createdAt = 0L,
                    finishedAt = 0L,
                )

            initializer(db).run()
            advanceUntilIdle()

            awaitReal { !snapshots.soonWorkLive() }
            specOf(infoOf(RestoreWorkData.WORK_SNAPSHOT).id)
            awaitReal { db.importDao().sessionById(expired) == null }
        }

    @Test
    fun `a library change after start-up pokes the debounced write`() =
        runTest {
            val db = openDb()
            initializer(db).run()
            advanceUntilIdle()
            awaitReal { !snapshots.soonWorkLive() }

            seedPodcast(db, "https://late.test/feed.xml")

            awaitReal { snapshots.soonWorkLive() }
        }

    @Test
    fun `a library change before the watcher's first read still pokes the debounced write`() =
        runTest {
            val db = openDb()
            // The watcher's scope only runs when the test advances it, so the insert below
            // lands before its first query — that emission already contains the new podcast.
            val watcherScheduler = TestCoroutineScheduler()
            val watcherScope = CoroutineScope(StandardTestDispatcher(watcherScheduler) + Job())
            try {
                initializer(db, appScope = watcherScope).run()

                seedPodcast(db, "https://early.test/feed.xml")

                awaitReal {
                    watcherScheduler.advanceUntilIdle()
                    snapshots.soonWorkLive()
                }
            } finally {
                watcherScope.cancel()
            }
        }

    @Test
    fun `start-up completes when the database cannot be read`() =
        runTest {
            val db = openDb()
            db.close()
            this@BackupWorkHostTest.db = null

            // Every database step fails; each is logged and the periodic arm still stands.
            initializer(db).run()
            advanceUntilIdle()

            assertThat(infoOf(RestoreWorkData.WORK_SNAPSHOT).state).isEqualTo(WorkInfo.State.ENQUEUED)
        }

    @Test
    fun `start-up does not schedule intents the restore runner cannot consume`() =
        runTest {
            val db = openDb()
            val corrupt = db.seedImportSession(format = ImportFormat.NEUTRODYNE_BACKUP)
            db.importDao().setOptionsJson(corrupt, "{broken")
            val futureMode =
                db.seedImportSession(
                    format = ImportFormat.NEUTRODYNE_BACKUP,
                    payloadPath = "import/future.bin",
                )
            db.importDao().setOptionsJson(futureMode, """{"restoreRequestedAt":1,"restoreMode":"FUTURE"}""")
            val noPayload =
                db.seedImportSession(
                    format = ImportFormat.NEUTRODYNE_BACKUP,
                    options = ImportOptions(restoreMode = RestoreMode.MERGE, restoreRequestedAt = 1L),
                )

            initializer(db).run()
            advanceUntilIdle()

            assertThat(workManager.getWorkInfosForUniqueWork(RestoreWorkData.WORK_RESTORE).get()).isEmpty()
            assertThat(db.importDao().sessionById(corrupt)?.optionsJson).isEqualTo("{broken")
            assertThat(db.importDao().sessionById(futureMode)?.state).isEqualTo(ImportState.PREVIEW)
            assertThat(db.importDao().sessionById(noPayload)?.state).isEqualTo(ImportState.PREVIEW)
        }

    // -- Fixtures --------------------------------------------------------------------------------

    private fun openDb(): NeutrodyneDatabase = newDb(app, clock).also { db = it }

    private fun TestScope.initializer(
        db: NeutrodyneDatabase,
        appScope: CoroutineScope = backgroundScope,
    ): AutoBackupInitializer {
        val payloads =
            PayloadStore(
                storagePaths = storagePaths,
                fileSystem = fileSystem,
                payloads = NoPayloadSource(),
                httpClients = testClients(),
                netErrors = testNetErrors,
                io = Dispatchers.IO,
            )
        return AutoBackupInitializer(
            snapshots = Provider { snapshots },
            work = Provider { imports },
            db = Provider { db },
            settings = Provider { settings },
            cleaner = Provider { ImportSessionCleaner(db, payloads, clock, Dispatchers.IO) },
            appScope = appScope,
        )
    }

    private fun producerOf(
        db: NeutrodyneDatabase,
        workScheduler: FakeImportWorkScheduler = FakeImportWorkScheduler(),
    ): AutoSnapshotProducer =
        AutoSnapshotProducer(
            db = db,
            writer =
                BackupWriter(
                    db,
                    settings,
                    secrets,
                    ch.lkmc.neutrodyne.feeds.jvm.archive
                        .ZipArchiveWriter(),
                    ch.lkmc.neutrodyne.feeds.jvm.archive
                        .ZipGuard(),
                    OpmlExporter(db, secrets, clock, Dispatchers.Default),
                    androidBuildInfo(),
                    clock,
                    fileSystem,
                    Dispatchers.IO,
                ),
            cleaner =
                ImportSessionCleaner(
                    db,
                    PayloadStore(
                        storagePaths = storagePaths,
                        fileSystem = fileSystem,
                        payloads = NoPayloadSource(),
                        httpClients = testClients(),
                        netErrors = testNetErrors,
                        io = Dispatchers.IO,
                    ),
                    clock,
                    Dispatchers.IO,
                ),
            readers =
                ch.lkmc.neutrodyne.feeds.jvm.archive
                    .ZipGuard(),
            settings = settings,
            snapshots = FakeSnapshotScheduler(),
            workScheduler = workScheduler,
            buildInfo = androidBuildInfo(),
            clock = clock,
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            io = Dispatchers.IO,
        )

    private fun snapshotWorker(producer: AutoSnapshotProducer): AutoSnapshotWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = AutoSnapshotWorker(appContext, workerParameters, producer)
            }
        return TestListenableWorkerBuilder
            .from(app, AutoSnapshotWorker::class.java)
            .setWorkerFactory(factory)
            .build()
    }

    /**
     * Real-time polling for effects that leave the test scheduler: Room's invalidation
     * tracker, the cleaner's IO dispatcher and the WorkManager backend all complete on real
     * threads, not `advanceUntilIdle`.
     */
    private suspend fun awaitReal(check: suspend () -> Boolean) =
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + AWAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (runBlocking { check() }) return@withContext
                Thread.sleep(POLL_MS)
            }
            error("condition not met within ${AWAIT_MS}ms")
        }

    private fun infoOf(uniqueName: String): WorkInfo {
        val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        assertThat(infos).isNotEmpty()
        return infos.last()
    }

    private fun specOf(id: UUID): WorkSpec =
        checkNotNull(
            (workManager as WorkManagerImpl).workDatabase.workSpecDao().getWorkSpec(id.toString()),
        )

    private companion object {
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
