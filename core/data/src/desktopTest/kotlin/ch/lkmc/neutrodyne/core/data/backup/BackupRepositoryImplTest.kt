// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.CommitCancellingDriver
import ch.lkmc.neutrodyne.core.data.FakeImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.FakePayloadSource
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.TestOrderKeys
import ch.lkmc.neutrodyne.core.data.exporting.ExportDestination
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.exporting.exportDirOf
import ch.lkmc.neutrodyne.core.data.importing.ImportClassifier
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.ImportSessions
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.LaneImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.importing.RestoreEnqueue
import ch.lkmc.neutrodyne.core.data.newNetworkClients
import ch.lkmc.neutrodyne.core.data.newUnsubscribe
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.data.requestImportRestore
import ch.lkmc.neutrodyne.core.data.seedImportSession
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.data.storagePathsFor
import ch.lkmc.neutrodyne.core.data.testBuildInfo
import ch.lkmc.neutrodyne.core.data.testClassifier
import ch.lkmc.neutrodyne.core.data.testUserAgent
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.StoredSecret
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.PreparedBackup
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import ch.lkmc.neutrodyne.core.model.SnapshotWrite
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.CredentialV1
import ch.lkmc.neutrodyne.feeds.identity.PrivateFeedUrls
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * B12c's [BackupRepositoryImpl] over real Room, filesystem, `BackupWriter`/`ZipGuard`/
 * `BackupStager`/`RestoreRunner`/`ImportSessions` and recording platform seams: the manual
 * write → destination copy → owned-artifact sweep, the stager preview that writes nothing,
 * the durable restore claim with its arbitration/rollback/commit-cancellation contract,
 * and the Android snapshot lane's adoption, controls and progress wiring.
 */
class BackupRepositoryImplTest {
    @Test
    fun retainedFailureKeepsItsSessionWhileAnotherRestoreIsQueued() =
        runTest {
            val first = backupSession()
            assertIs<Outcome.Success<*>>(repository.restore(first, RestoreRequest(RestoreMode.MERGE, emptySet())))
            val path = checkNotNull(db.importDao().sessionById(first)?.payloadPath)
            fileSystem.delete(payloads.payloadFile(path))
            assertIs<RestoreRunResult.Failed>(runner.run(first))
            assertEquals(ImportState.CANCELLED, db.importDao().sessionById(first)?.state)
            assertEquals(RestoreProgress.Failed(first, BackupError.Io), repository.observeRestore().first())

            val second = backupSession()
            assertIs<Outcome.Success<*>>(repository.restore(second, RestoreRequest(RestoreMode.MERGE, emptySet())))
            val pending = checkNotNull(db.importDao().sessionById(second))
            assertEquals(ImportState.PREVIEW, pending.state)
            assertNotNull(ImportOptionsJson.decode(checkNotNull(pending.optionsJson)).restoreRequestedAt)
            assertEquals(
                RestoreProgress.Failed(first, BackupError.Io),
                repository.observeRestore().first(),
                "A retained failure must still identify the first session while the second is queued",
            )

            assertIs<RestoreRunResult.Completed>(runner.run(second))
            assertEquals(RestoreProgress.Finished(second, auto = false), repository.observeRestore().first())
        }

    private val driver = CommitCancellingDriver()
    private val clock = TestClock()
    private val db = TestDb.inMemory(driver = driver, clock = clock)
    private val settings = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val workScheduler = FakeImportWorkScheduler()
    private val snapshots = FakeSnapshotScheduler()
    private val fileSystem = FileSystem.SYSTEM
    private val root = createTempDirectory("nd-backup-repo").toFile()
    private val storagePaths = storagePathsFor(root)
    private val payloadsSource = FakePayloadSource()
    private val codec = BackupCodec()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val coordinator = FakeCredentialCommitCoordinator()
    private val channels = FakeGroupChannelSync()
    private val fetchScheduler = FakeRefreshScheduler()
    private val refresh = FakeRefreshController()

    private val payloads =
        PayloadStore(
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            payloads = payloadsSource,
            httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
            netErrors = testClassifier,
            io = Dispatchers.Default,
        )
    private val cleaner = ImportSessionCleaner(db, payloads, clock, Dispatchers.IO)
    private val exporter = OpmlExporter(db, secrets, clock, Dispatchers.Default)
    private val androidInfo = testBuildInfo().copy(platform = BuildInfo.Platform.ANDROID)

    private val writer =
        BackupWriter(
            db,
            settings,
            secrets,
            ZipArchiveWriter(),
            ZipGuard(),
            exporter,
            androidInfo,
            clock,
            fileSystem,
            Dispatchers.IO,
        )

    private val merger =
        RestoreMerger(
            db = db,
            podcasts = PodcastMatcher(db),
            writes = RestoreWrites(db),
            groupMerger = GroupMerger(db, clock, channels),
            unsubscribe = newUnsubscribe(db, fetchScheduler, clock, secrets, coordinator),
            secrets = secrets,
            coordinator = coordinator,
            channelSync = channels,
            orderKeys = TestOrderKeys,
            clock = clock,
        )
    private val episodeMerger =
        RestoreEpisodeMerger(
            db = db,
            episodes = EpisodeMatcher(db),
            writes = RestoreWrites(db),
            orderKeys = TestOrderKeys,
            clock = clock,
        )
    private val stager = BackupStager(db, ZipGuard(), merger, fileSystem, Dispatchers.IO)
    private val runner =
        RestoreRunner(
            db = db,
            stager = stager,
            merger = merger,
            episodes = episodeMerger,
            settingsMerge = RestoreSettingsMerge(settings),
            payloads = payloads,
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            workScheduler = workScheduler,
            refresh = refresh,
            channels = channels,
            snapshots = snapshots,
            clock = clock,
            io = Dispatchers.IO,
        )
    private val sessions =
        ImportSessions(
            db = db,
            payloads = payloads,
            classifier = ImportClassifier(db, YouTubeUrlClassifier()),
            opmlReader = XmlPullOpmlReader.discovered(),
            archives = ZipGuard(),
            clock = clock,
            appScope = appScope,
            io = Dispatchers.Default,
        )
    private val destination = RecordingExportDestination()

    private fun producerOf(buildInfo: BuildInfo = androidInfo) =
        AutoSnapshotProducer(
            db = db,
            writer = writer,
            cleaner = cleaner,
            readers = ZipGuard(),
            settings = settings,
            snapshots = snapshots,
            workScheduler = workScheduler,
            buildInfo = buildInfo,
            clock = clock,
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            io = Dispatchers.IO,
        )

    private fun repositoryOf(
        producer: AutoSnapshotProducer = producerOf(),
        scheduler: ImportWorkScheduler = workScheduler,
        repositoryFileSystem: FileSystem = fileSystem,
        repositorySettings: SettingsRepository = settings,
    ) = BackupRepositoryImpl(
        db = db,
        writer = writer,
        stager = stager,
        runner = runner,
        sessions = sessions,
        producer = producer,
        destination = destination,
        payloads = payloads,
        settings = repositorySettings,
        workScheduler = scheduler,
        clock = clock,
        storagePaths = storagePaths,
        fileSystem = repositoryFileSystem,
        io = Dispatchers.IO,
    )

    /** Android producer by default — the desktop lane swaps it via [repositoryOf]. */
    private val androidProducer = producerOf()
    private val repository = repositoryOf(androidProducer)

    @AfterTest
    fun tearDown() {
        db.close()
        appScope.cancel()
        root.deleteRecursively()
    }

    // --- Manual backup --------------------------------------------------------------------------

    @Test
    fun aManualBackupCopiesThePreparedArtifactAndStampsTheTime() =
        runTest {
            seedPodcast(db, "https://a.example.com/f")

            val result = repository.createBackup("dest://keep.zip", includePasswords = false)

            val summary = assertIs<Outcome.Success<BackupSummary>>(result).value
            val copied = destination.copied.single()
            assertEquals("dest://keep.zip", copied.first)
            val archive = tempArchive(copied.second)
            reader(archive).use { assertEquals(BackupKind.MANUAL, it.manifest().kind) }
            assertEquals(1, summary.podcasts)
            assertEquals(clock.now(), settings.get(BackupSettingKeys.LAST_MANUAL_BACKUP_AT))
            assertEquals(
                emptyList(),
                fileSystem.list(exportDirOf(storagePaths)),
                "the prepared cache artifact is swept after the copy",
            )
        }

    @Test
    fun aDeclinedCopyReportsTheErrorAndSweeps() =
        runTest {
            seedPodcast(db, "https://a.example.com/f")
            destination.failWith = ExportError.DestinationUnavailable

            val result = repository.createBackup("dest://gone", includePasswords = false)

            assertEquals(
                Outcome.Failure(BackupError.DestinationUnavailable),
                result,
            )
            assertEquals(0L, settings.get(BackupSettingKeys.LAST_MANUAL_BACKUP_AT))
            assertEquals(emptyList(), fileSystem.list(exportDirOf(storagePaths)))
        }

    @Test
    fun aThrowingDestinationReportsIoAndSweeps() =
        runTest {
            seedPodcast(db, "https://a.example.com/f")
            destination.explode = true

            val result = repository.createBackup("dest://boom", includePasswords = false)

            assertEquals(Outcome.Failure(BackupError.Io), result)
            assertEquals(emptyList(), fileSystem.list(exportDirOf(storagePaths)))
        }

    @Test
    fun aFailingWriterReportsWithoutACopy() =
        runTest {
            // No rows at all still writes a valid empty archive, so use a broken filesystem
            // seam instead: make the export directory a file so staging cannot open.
            val export = exportDirOf(storagePaths)
            fileSystem.createDirectories(checkNotNull(export.parent))
            fileSystem.write(export) { writeUtf8("not a directory") }

            val result = repository.createBackup("dest://never", includePasswords = false)

            assertIs<Outcome.Failure<*>>(result)
            assertTrue(destination.copied.isEmpty())
        }

    @Test
    fun aWriterFailureLeavesAbandonedLeavesToTheCleaner() =
        runTest {
            seedPodcast(db, "https://a.example.com/f")
            assertIs<Outcome.Success<*>>(repository.createBackup("dest://first", false))
            val abandoned = destination.sources.single()
            fileSystem.write(abandoned) { writeUtf8("abandoned private backup") }
            settings.reset(BackupSettingKeys.INSTALLATION_ID)
            settings.failNextSet = ch.lkmc.neutrodyne.core.domain.SettingsError.WriteFailed

            assertEquals(
                Outcome.Failure(BackupError.Storage),
                repository.createBackup("dest://never", false),
            )

            // Prepared leaves are unique per call and owned by their descriptor: a failed
            // write sweeps its own stage but never another flow's artifact — an abandoned
            // leaf is ExportFilesCleaner's to retire.
            assertTrue(fileSystem.exists(abandoned), "an abandoned leaf is the cleaner's, not this call's")
            assertEquals(
                listOf(abandoned),
                fileSystem.list(exportDirOf(storagePaths)),
                "the failed call left neither its leaf nor its stage behind",
            )
            assertEquals(1, destination.copied.size)
        }

    @Test
    fun concurrentBackupsNeverShareThePreparedArtifact() =
        runTest {
            // Same clock => same-minute file name: only the repository's serialization keeps
            // the opted-in password write out of the password-free caller's artifact.
            seedCredentialPodcast()

            val withPasswords = async { repository.createBackup("dest://private", includePasswords = true) }
            val without = async { repository.createBackup("dest://public", includePasswords = false) }
            assertIs<Outcome.Success<*>>(withPasswords.await())
            assertIs<Outcome.Success<*>>(without.await())

            val byUri = destination.copied.toMap()
            val privateCopy = tempArchive(checkNotNull(byUri["dest://private"]))
            val publicCopy = tempArchive(checkNotNull(byUri["dest://public"]))
            reader(privateCopy).use { reader ->
                assertEquals(
                    listOf(CredentialV1(CREDENTIAL_ORIGIN, CREDENTIAL_USER, CREDENTIAL_PASS)),
                    reader.library().credentials,
                )
            }
            reader(publicCopy).use { assertTrue(it.library().credentials.isEmpty()) }
        }

    // --- Prepared backup (05 Writing's frozen-consent flow) ----------------------------------------

    @Test
    fun preparedBackupSavesItsFrozenBytesAndCountsDespiteLaterChanges() =
        runTest {
            seedPodcast(db, "https://public.example.com/feed")
            seedPodcast(db, "https://feeds.patreon.com/member-only")
            seedCredentialPodcast()

            val prepared =
                assertIs<Outcome.Success<PreparedBackup>>(repository.prepareBackup(includePasswords = true)).value
            // Counts and bytes come from the same write — this is what the warning shows.
            assertEquals(PrivacyCounts(privateLinks = 1, passwords = 1), prepared.privacy)
            assertEquals(3, prepared.summary.podcasts)
            assertTrue(prepared.fileName.startsWith("neutrodyne-backup-"))
            assertTrue(prepared.fileName.endsWith(".zip"))
            val source = (prepared as PreparedBackupFile).source
            val frozenBytes = fileSystem.read(source) { readByteArray() }

            // A private subscription and a credential change after consent must not
            // reach the artifact the user was warned about.
            seedPodcast(db, "https://feeds.patreon.com/late-arrival")
            secrets.remove(CREDENTIAL_ORIGIN)

            assertIs<Outcome.Success<*>>(repository.savePreparedBackup(prepared, "dest://frozen.zip"))
            val copied = destination.copied.single()
            assertEquals("dest://frozen.zip", copied.first)
            assertContentEquals(frozenBytes, copied.second)
            reader(tempArchive(copied.second)).use { r ->
                val library = r.library()
                assertEquals(3, library.podcasts.size)
                assertEquals(1, library.podcasts.count { PrivateFeedUrls.looksPrivate(it.feedUrl) })
                assertEquals(
                    listOf(CredentialV1(CREDENTIAL_ORIGIN, CREDENTIAL_USER, CREDENTIAL_PASS)),
                    library.credentials,
                )
            }
        }

    @Test
    fun sameMinutePreparesKeepIndependentSources() =
        runTest {
            seedCredentialPodcast()

            val withPasswords =
                assertIs<Outcome.Success<PreparedBackup>>(repository.prepareBackup(includePasswords = true)).value
            val without =
                assertIs<Outcome.Success<PreparedBackup>>(repository.prepareBackup(includePasswords = false)).value

            // Same clock => the same friendly minute name; the owned leaves still differ.
            assertEquals(withPasswords.fileName, without.fileName)
            assertNotEquals(
                (withPasswords as PreparedBackupFile).source,
                (without as PreparedBackupFile).source,
            )
            assertEquals(1, withPasswords.privacy.passwords)
            assertEquals(0, without.privacy.passwords)

            assertIs<Outcome.Success<*>>(repository.savePreparedBackup(without, "dest://public"))
            assertIs<Outcome.Success<*>>(repository.savePreparedBackup(withPasswords, "dest://private"))
            val byUri = destination.copied.toMap()
            reader(tempArchive(checkNotNull(byUri["dest://private"]))).use {
                assertEquals(
                    listOf(CredentialV1(CREDENTIAL_ORIGIN, CREDENTIAL_USER, CREDENTIAL_PASS)),
                    it.library().credentials,
                )
            }
            reader(tempArchive(checkNotNull(byUri["dest://public"]))).use {
                assertTrue(it.library().credentials.isEmpty())
            }
        }

    @Test
    fun saveRefusesAForeignDescriptorBeforeTouchingTheDestination() =
        runTest {
            seedPodcast(db, "https://a.example.com/f")
            val foreign =
                object : PreparedBackup {
                    override val fileName = "neutrodyne-backup-2026-01-01-0000.zip"
                    override val summary = BackupSummary(1, 1, 1)
                    override val privacy = PrivacyCounts(9, 9)
                }

            assertEquals(
                Outcome.Failure(BackupError.PreparedExpired),
                repository.savePreparedBackup(foreign, "dest://never"),
            )
            assertTrue(destination.sources.isEmpty(), "the destination seam is never reached")
        }

    @Test
    fun saveRejectsForgedJvmDescriptorBeforeAnyFilesystemAccess() =
        runTest {
            val outside = root.absolutePath.toPath() / "outside-cache.txt"
            fileSystem.write(outside) { writeUtf8("private file, not a backup") }
            val constructor =
                Class
                    .forName("ch.lkmc.neutrodyne.core.data.backup.PreparedBackupFile")
                    .constructors
                    .single()
            val forged =
                constructor.newInstance(
                    "harmless.zip",
                    BackupSummary(0, 0, 0),
                    PrivacyCounts(0, 0),
                    outside,
                ) as PreparedBackup
            val accessed = mutableListOf<Path>()
            val tracking =
                object : ForwardingFileSystem(fileSystem) {
                    override fun onPathParameter(
                        path: Path,
                        functionName: String,
                        parameterName: String,
                    ): Path {
                        accessed += path
                        return path
                    }
                }
            val repo = repositoryOf(repositoryFileSystem = tracking)

            assertEquals(
                Outcome.Failure(BackupError.PreparedExpired),
                repo.savePreparedBackup(forged, "dest://forged"),
                "an unissued JVM descriptor must expire",
            )
            assertTrue(accessed.isEmpty(), "unknown descriptors must not reach the filesystem")
            assertTrue(destination.sources.isEmpty())
            assertEquals("private file, not a backup", fileSystem.read(outside) { readUtf8() })
            assertEquals(0L, settings.get(BackupSettingKeys.LAST_MANUAL_BACKUP_AT))
        }

    @Test
    fun saveRejectsAnotherRepositorysDescriptorBeforeAnyFilesystemAccess() =
        runTest {
            val prepared = assertIs<Outcome.Success<PreparedBackup>>(repository.prepareBackup(false)).value
            val accessed = mutableListOf<Path>()
            val tracking =
                object : ForwardingFileSystem(fileSystem) {
                    override fun onPathParameter(
                        path: Path,
                        functionName: String,
                        parameterName: String,
                    ): Path {
                        accessed += path
                        return path
                    }
                }
            val other = repositoryOf(repositoryFileSystem = tracking)

            assertEquals(
                Outcome.Failure(BackupError.PreparedExpired),
                other.savePreparedBackup(prepared, "dest://foreign"),
                "only the issuing repository may save a descriptor",
            )
            assertTrue(accessed.isEmpty(), "foreign descriptors must not reach the filesystem")
            assertTrue(destination.sources.isEmpty())
            assertIs<Outcome.Success<*>>(repository.savePreparedBackup(prepared, "dest://issuer"))
        }

    @Test
    fun saveRejectsAnIssuedLeafSymlinkOutsideThePrivateCache() =
        runTest {
            val prepared = assertIs<Outcome.Success<PreparedBackup>>(repository.prepareBackup(false)).value
            val source = (prepared as PreparedBackupFile).source
            val outside = root.absolutePath.toPath() / "outside-cache.txt"
            fileSystem.write(outside) { writeUtf8("private file, not a backup") }
            fileSystem.delete(source)
            fileSystem.createSymlink(source, outside)

            assertEquals(
                Outcome.Failure(BackupError.PreparedExpired),
                repository.savePreparedBackup(prepared, "dest://symlink"),
                "the resolved source must stay inside the private cache",
            )
            assertTrue(destination.sources.isEmpty())
            assertEquals("private file, not a backup", fileSystem.read(outside) { readUtf8() })
            assertEquals(0L, settings.get(BackupSettingKeys.LAST_MANUAL_BACKUP_AT))
        }

    @Test
    fun successfulSaveConsumesDescriptorEvenWhenLeafDeletionFails() =
        runTest {
            val failing =
                object : ForwardingFileSystem(fileSystem) {
                    override fun delete(
                        path: Path,
                        mustExist: Boolean,
                    ): Unit = throw IOException("delete denied")
                }
            val repo = repositoryOf(repositoryFileSystem = failing)
            val prepared = assertIs<Outcome.Success<PreparedBackup>>(repo.prepareBackup(false)).value
            val source = (prepared as PreparedBackupFile).source
            assertIs<Outcome.Success<*>>(repo.savePreparedBackup(prepared, "dest://first"))
            assertTrue(fileSystem.exists(source), "exercise failed deletion, not missing-source expiry")

            assertEquals(
                Outcome.Failure(BackupError.PreparedExpired),
                repo.savePreparedBackup(prepared, "dest://second"),
                "a successful descriptor must expire independently of deletion",
            )
            assertEquals(1, destination.copied.size)
            assertEquals(1, settings.calls.count { it == "set(${BackupSettingKeys.LAST_MANUAL_BACKUP_AT.name})" })
        }

    @Test
    fun cancellationAfterTimestampCommitStillCleansAndConsumesDescriptor() =
        runTest {
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var pauseNextStamp = true
            val gated =
                object : SettingsRepository by settings {
                    override suspend fun <T : Any> set(
                        key: SettingKey<T>,
                        value: T,
                    ): Outcome<Unit, SettingsError> {
                        val result = settings.set(key, value)
                        if (key.name == BackupSettingKeys.LAST_MANUAL_BACKUP_AT.name && pauseNextStamp) {
                            pauseNextStamp = false
                            committed.complete(Unit)
                            release.await()
                        }
                        return result
                    }
                }
            val repo = repositoryOf(repositorySettings = gated)
            val prepared = assertIs<Outcome.Success<PreparedBackup>>(repo.prepareBackup(false)).value
            val source = (prepared as PreparedBackupFile).source
            val first = launch { repo.savePreparedBackup(prepared, "dest://first") }
            committed.await()
            first.cancel()
            release.complete(Unit)
            first.join()

            assertFalse(fileSystem.exists(source), "cancellation after timestamp commit must still clean the leaf")
            assertEquals(
                Outcome.Failure(BackupError.PreparedExpired),
                repo.savePreparedBackup(prepared, "dest://second"),
            )
            assertEquals(1, destination.copied.size)
            assertEquals(1, settings.calls.count { it == "set(${BackupSettingKeys.LAST_MANUAL_BACKUP_AT.name})" })
        }

    @Test
    fun saveFailsExplicitlyWhenTheOwnedSourceIsGone() =
        runTest {
            seedPodcast(db, "https://a.example.com/f")
            val swept =
                assertIs<Outcome.Success<PreparedBackup>>(repository.prepareBackup(includePasswords = false)).value
            // What ExportFilesCleaner does to an abandoned leaf.
            fileSystem.delete((swept as PreparedBackupFile).source)

            assertEquals(
                Outcome.Failure(BackupError.PreparedExpired),
                repository.savePreparedBackup(swept, "dest://late"),
            )
            assertTrue(destination.sources.isEmpty())

            // A consumed descriptor expires too — the same bytes are never saved twice.
            val prepared =
                assertIs<Outcome.Success<PreparedBackup>>(repository.prepareBackup(includePasswords = false)).value
            assertIs<Outcome.Success<*>>(repository.savePreparedBackup(prepared, "dest://first"))
            assertEquals(
                Outcome.Failure(BackupError.PreparedExpired),
                repository.savePreparedBackup(prepared, "dest://again"),
            )
        }

    @Test
    fun aFailedSaveKeepsTheSourceForAWarnedRetry() =
        runTest {
            seedCredentialPodcast()
            val prepared =
                assertIs<Outcome.Success<PreparedBackup>>(repository.prepareBackup(includePasswords = true)).value
            val source = (prepared as PreparedBackupFile).source
            val frozenBytes = fileSystem.read(source) { readByteArray() }

            destination.failWith = ExportError.DestinationUnavailable
            assertEquals(
                Outcome.Failure(BackupError.DestinationUnavailable),
                repository.savePreparedBackup(prepared, "dest://retry"),
            )
            assertTrue(fileSystem.exists(source), "a failed save preserves the owned artifact")
            assertEquals(0L, settings.get(BackupSettingKeys.LAST_MANUAL_BACKUP_AT))

            destination.failWith = null
            assertIs<Outcome.Success<*>>(repository.savePreparedBackup(prepared, "dest://retry"))
            assertContentEquals(frozenBytes, destination.copied.single().second)
            assertFalse(fileSystem.exists(source), "a landed copy consumes the leaf")
            assertEquals(clock.now(), settings.get(BackupSettingKeys.LAST_MANUAL_BACKUP_AT))
            assertEquals(
                emptyList(),
                fileSystem.list(exportDirOf(storagePaths)),
                "no staged or spent leaf remains",
            )
        }

    @Test
    fun savingAPreparedBackupNeverRereadsTheLibraryOrSecrets() =
        runTest {
            val countingSecrets = CountingSecretStore(secrets)
            val repo =
                BackupRepositoryImpl(
                    db = db,
                    writer =
                        BackupWriter(
                            db,
                            settings,
                            countingSecrets,
                            ZipArchiveWriter(),
                            ZipGuard(),
                            exporter,
                            androidInfo,
                            clock,
                            fileSystem,
                            Dispatchers.IO,
                        ),
                    stager = stager,
                    runner = runner,
                    sessions = sessions,
                    producer = androidProducer,
                    destination = destination,
                    payloads = payloads,
                    settings = settings,
                    workScheduler = workScheduler,
                    clock = clock,
                    storagePaths = storagePaths,
                    fileSystem = fileSystem,
                    io = Dispatchers.IO,
                )
            seedCredentialPodcast()

            val prepared =
                assertIs<Outcome.Success<PreparedBackup>>(repo.prepareBackup(includePasswords = true)).value
            val readsAtPrepare = countingSecrets.forPodcastCalls
            assertEquals(1, readsAtPrepare)

            // Mutating after the write would be caught by any re-read — byte equality is
            // proven by the frozen-bytes test; here the counters alone condemn a reread.
            seedPodcast(db, "https://feeds.patreon.com/late")
            secrets.remove(CREDENTIAL_ORIGIN)
            assertIs<Outcome.Success<*>>(repo.savePreparedBackup(prepared, "dest://saved"))

            assertEquals(readsAtPrepare, countingSecrets.forPodcastCalls, "save must not touch secrets")
            assertEquals(1, destination.copied.size)
        }

    // --- Inspect ----------------------------------------------------------------------------------

    @Test
    fun inspectReturnsThePreviewAndWritesNothing() =
        runTest {
            seedPodcast(db, "https://a.example.com/f")
            val sessionId = backupSession()
            val before = db.importDao().sessionById(sessionId)

            val result = repository.inspect(sessionId)

            val preview = assertIs<Outcome.Success<BackupPreview>>(result, "inspect: $result").value
            assertEquals(1, preview.podcasts)
            // No restore writes: the row is untouched and the library unchanged.
            assertEquals(before, db.importDao().sessionById(sessionId))
            assertEquals(1, db.podcastDao().allForExport().size)
        }

    @Test
    fun inspectRejectsMissingAndNonBackupSessions() =
        runTest {
            assertEquals(Outcome.Failure(BackupError.NotABackup), repository.inspect(9_999L))

            val opml = db.seedImportSession(format = ImportFormat.OPML)
            assertEquals(Outcome.Failure(BackupError.NotABackup), repository.inspect(opml))
        }

    // --- The durable restore request ---------------------------------------------------------------

    @Test
    fun restoreClaimsTheSessionAndEnqueuesTheHost() =
        runTest {
            val sessionId = backupSession()
            val request =
                RestoreRequest(
                    mode = RestoreMode.REPLACE,
                    categories = setOf(RestoreCategory.HISTORY),
                )

            val result = repository.restore(sessionId, request)

            assertIs<Outcome.Success<*>>(result)
            val session = checkNotNull(db.importDao().sessionById(sessionId))
            val options = ImportOptionsJson.decode(checkNotNull(session.optionsJson))
            assertEquals(RestoreMode.REPLACE, options.restoreMode)
            assertEquals(setOf(RestoreCategory.HISTORY), options.restoreCategories)
            assertEquals(clock.now(), options.restoreRequestedAt)
            assertEquals(listOf(sessionId to false), workScheduler.restores)
        }

    @Test
    fun aMissingPayloadCannotBecomeAPhantomRestore() =
        runTest {
            for (path in listOf(null, "")) {
                val sessionId =
                    db.seedImportSession(
                        format = ImportFormat.NEUTRODYNE_BACKUP,
                        payloadPath = path,
                    )

                assertEquals(
                    Outcome.Failure(BackupError.Io),
                    repository.restore(sessionId, RestoreRequest(RestoreMode.MERGE, emptySet())),
                )
                val row = checkNotNull(db.importDao().sessionById(sessionId))
                assertNull(ImportOptionsJson.decode(checkNotNull(row.optionsJson)).restoreRequestedAt)
            }
            assertTrue(workScheduler.restores.isEmpty())
        }

    @Test
    fun aFailedIntentWriteReturnsStorageWithoutEnqueuing() =
        runTest {
            val sessionId = backupSession()
            driver.armOnStep("UPDATE import_session SET optionsJson") { throw IOException("intent write failed") }

            assertEquals(
                Outcome.Failure(BackupError.Storage),
                repository.restore(sessionId, RestoreRequest(RestoreMode.MERGE, emptySet())),
            )

            val row = checkNotNull(db.importDao().sessionById(sessionId))
            assertNull(ImportOptionsJson.decode(checkNotNull(row.optionsJson)).restoreRequestedAt)
            assertTrue(workScheduler.restores.isEmpty())
        }

    @Test
    fun aSecondRequestOnTheSameSessionIsDeclined() =
        runTest {
            val sessionId = backupSession()
            val request = RestoreRequest(mode = RestoreMode.MERGE, categories = emptySet())
            assertIs<Outcome.Success<*>>(repository.restore(sessionId, request))

            assertEquals(
                Outcome.Failure(BackupError.RestoreRunning),
                repository.restore(sessionId, request),
            )
            // Only the first claim ever reached the scheduler.
            assertEquals(1, workScheduler.restores.size)
        }

    @Test
    fun aPendingIntentOnAnotherSessionIsDeclined() =
        runTest {
            val claimed = backupSession()
            assertIs<Outcome.Success<*>>(
                db.requestImportRestore(claimed, clock.now()),
            )
            val other = backupSession()

            assertEquals(
                Outcome.Failure(BackupError.RestoreRunning),
                repository.restore(other, RestoreRequest(RestoreMode.MERGE, emptySet())),
            )
            // The foreign intent is untouched.
            val options =
                ImportOptionsJson.decode(
                    checkNotNull(checkNotNull(db.importDao().sessionById(claimed)).optionsJson),
                )
            assertEquals(clock.now(), options.restoreRequestedAt)
        }

    /**
     * The claim path against the real `LaneImportWorkScheduler` (C3b): `restore` writes the
     * durable intent *before* calling `scheduleRestore`, so the scheduler plainly sees the
     * request's own intent — answering `ALREADY_RUNNING` there would roll the stamp back and
     * deny queued work. The durable row is the queue; the poke only wakes the lane.
     */
    @Test
    fun theLaneSchedulerAcknowledgesTheRequestItIsWokenFor() =
        runTest {
            val pokes = mutableListOf<String>()
            val laneScheduler =
                LaneImportWorkScheduler(db, Provider { JobLanePoker { pokes += it } })
            val realRepo = repositoryOf(scheduler = laneScheduler)
            val sessionId = backupSession()

            val result = realRepo.restore(sessionId, RestoreRequest(RestoreMode.MERGE, emptySet()))

            assertIs<Outcome.Success<*>>(result)
            assertEquals(listOf("import-backup"), pokes)
            assertTrue(laneScheduler.hasRestoreWork(), "the durable intent is restore work")

            // And the same durable check gates a second request — no fake was involved.
            assertEquals(
                Outcome.Failure(BackupError.RestoreRunning),
                realRepo.restore(sessionId, RestoreRequest(RestoreMode.MERGE, emptySet())),
            )
            assertEquals(1, pokes.size, "the declined request never pokes")
        }

    @Test
    fun nativeRestoreWorkOrAnUnknownCheckFailsClosed() =
        runTest {
            val busy = backupSession()
            workScheduler.restoreWork = true
            assertEquals(
                Outcome.Failure(BackupError.RestoreRunning),
                repository.restore(busy, RestoreRequest(RestoreMode.MERGE, emptySet())),
            )

            // A scheduler that cannot answer the check is not proof of an idle lane.
            workScheduler.restoreWork = false
            workScheduler.fail = true
            assertEquals(
                Outcome.Failure(BackupError.RestoreRunning),
                repository.restore(busy, RestoreRequest(RestoreMode.MERGE, emptySet())),
            )
            val session = checkNotNull(db.importDao().sessionById(busy))
            assertNull(ImportOptionsJson.decode(checkNotNull(session.optionsJson)).restoreRequestedAt)
        }

    @Test
    fun aNonPreviewSessionIsDeclined() =
        runTest {
            val committed = backupSession(state = ImportState.COMMITTED)
            assertEquals(
                Outcome.Failure(BackupError.RestoreRunning),
                repository.restore(committed, RestoreRequest(RestoreMode.MERGE, emptySet())),
            )
            val done = backupSession(state = ImportState.DONE)
            assertEquals(
                Outcome.Failure(BackupError.NotABackup),
                repository.restore(done, RestoreRequest(RestoreMode.MERGE, emptySet())),
            )
        }

    @Test
    fun aDeclinedEnqueueRollsBackOnlyThisIntent() =
        runTest {
            val sessionId = backupSession()
            workScheduler.restoreAck = RestoreEnqueue.ALREADY_RUNNING

            val result = repository.restore(sessionId, RestoreRequest(RestoreMode.REPLACE, emptySet()))

            assertEquals(Outcome.Failure(BackupError.RestoreRunning), result)
            assertEquals(listOf(sessionId to false), workScheduler.restores)
            // This stamp was provably never consumed — it is the only thing rolled back.
            val options =
                ImportOptionsJson.decode(
                    checkNotNull(checkNotNull(db.importDao().sessionById(sessionId)).optionsJson),
                )
            assertNull(options.restoreRequestedAt)
            assertEquals(RestoreMode.REPLACE, options.restoreMode, "only the dispatch stamp is undone")
        }

    @Test
    fun anUnknownEnqueueKeepsTheIntentForReconciliation() =
        runTest {
            val sessionId = backupSession()
            val throwing =
                object : ImportWorkScheduler by workScheduler {
                    override suspend fun scheduleRestore(
                        sessionId: Long,
                        auto: Boolean,
                    ): RestoreEnqueue = error("WorkManager cannot confirm the enqueue")
                }

            val result =
                repositoryOf(scheduler = throwing)
                    .restore(sessionId, RestoreRequest(RestoreMode.MERGE, emptySet()))

            assertEquals(Outcome.Failure(BackupError.Storage), result)
            // The durable intent survives for the native host's start-up reconciliation.
            val options =
                ImportOptionsJson.decode(
                    checkNotNull(checkNotNull(db.importDao().sessionById(sessionId)).optionsJson),
                )
            assertEquals(clock.now(), options.restoreRequestedAt)
            assertEquals(ImportState.PREVIEW, db.importDao().sessionById(sessionId)?.state)
        }

    @Test
    fun cancellationAtTheIntentCommitStillEnqueues() =
        runTest {
            val sessionId = backupSession()

            cancelAtCommit("UPDATE import_session SET optionsJson") {
                repository.restore(sessionId, RestoreRequest(RestoreMode.MERGE, emptySet()))
            }

            // The committed stamp owned an enqueue; cancellation could not strand it.
            assertEquals(listOf(sessionId to false), workScheduler.restores)
            val options =
                ImportOptionsJson.decode(
                    checkNotNull(checkNotNull(db.importDao().sessionById(sessionId)).optionsJson),
                )
            assertEquals(clock.now(), options.restoreRequestedAt)
        }

    @Test
    fun adoptedSessionsEnqueueAsAuto() =
        runTest {
            val sessionId = backupSession(fromAndroidBackup = true)

            assertIs<Outcome.Success<*>>(
                repository.restore(sessionId, RestoreRequest(RestoreMode.MERGE, emptySet())),
            )
            assertEquals(listOf(sessionId to true), workScheduler.restores)
        }

    @Test
    fun observeRestoreDelegatesToTheRunner() {
        assertSame(runner.progress, repository.observeRestore())
    }

    // --- The Android snapshot lane -----------------------------------------------------------------

    @Test
    fun restoreAndroidBackupAdoptsThePendingSnapshot() =
        runTest {
            seedPodcast(db, "https://a.example.com/f")
            assertIs<SnapshotResult.Written>(producerOf().produce())

            val result = repository.restoreAndroidBackup()

            val sessionId = assertIs<Outcome.Success<Long>>(result).value
            val session = checkNotNull(db.importDao().sessionById(sessionId))
            assertEquals(ImportFormat.NEUTRODYNE_BACKUP, session.sourceFormat)
            assertTrue(
                ImportOptionsJson
                    .decode(checkNotNull(session.optionsJson))
                    .fromAndroidBackup,
            )
            assertTrue(fileSystem.exists(payloads.payloadFile(checkNotNull(session.payloadPath))))
            // The staged adoption keeps the snapshot file itself.
            assertTrue(fileSystem.exists(androidProducer.snapshotFile))
        }

    @Test
    fun writeSnapshotNowMapsProducerOutcomes() =
        runTest {
            // Disabled is a status answer, not an error (05's receiver wording).
            settings.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, false)
            val disabled = repository.writeSnapshotNow()
            val disabledPair = assertIs<Outcome.Success<SnapshotWrite>>(disabled).value
            assertFalse(disabledPair.after.enabled)
            assertEquals(disabledPair.before, disabledPair.after)

            settings.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, true)
            val foreign = backupDirOf(storagePaths) / AUTO_SNAPSHOT_FILE
            fileSystem.createDirectories(checkNotNull(foreign.parent))
            fileSystem.write(foreign) { writeUtf8("someone else's snapshot") }
            val guarded = repository.writeSnapshotNow()
            assertTrue(assertIs<Outcome.Success<SnapshotWrite>>(guarded).value.after.foreignPending)

            // A real write moves the publication fields the receiver compares (05 bmgr).
            fileSystem.delete(foreign)
            val written = repository.writeSnapshotNow()
            val pair = assertIs<Outcome.Success<SnapshotWrite>>(written).value
            assertEquals(null, pair.before.lastWrittenAt)
            checkNotNull(pair.after.lastWrittenAt)
        }

    @Test
    fun theDesktopLaneIsUnsupported() =
        runTest {
            val desktop = repositoryOf(producerOf(testBuildInfo()))

            assertEquals(Outcome.Failure(BackupError.Unsupported), desktop.restoreAndroidBackup())
            assertEquals(Outcome.Failure(BackupError.Unsupported), desktop.writeSnapshotNow())
            assertFalse(desktop.observeSnapshotStatus().first().supported)
            // Documented no-ops: they must not throw or touch the filesystem.
            desktop.setSnapshotEnabled(true)
            desktop.discardAndroidBackup()
            assertFalse(fileSystem.exists(backupDirOf(storagePaths)))
        }

    // --- Helpers ----------------------------------------------------------------------------------

    /**
     * A `NEUTRODYNE_BACKUP` `PREVIEW` session whose payload is a real archive written by
     * `BackupWriter` — with no restore intent unless [requested] is set.
     */
    private var fixtureCount = 0

    private suspend fun backupSession(
        state: ImportState = ImportState.PREVIEW,
        fromAndroidBackup: Boolean = false,
        requested: Boolean = false,
    ): Long {
        val archive = root.absolutePath.toPath() / "fixtures" / "archive-${fixtureCount++}.zip"
        fileSystem.createDirectories(checkNotNull(archive.parent))
        assertIs<Outcome.Success<*>>(
            writer.write(BackupKind.MANUAL, archive, includeCredentials = false),
        )
        val sessionId =
            db.seedImportSession(
                options = ImportOptions(fromAndroidBackup = fromAndroidBackup),
                format = ImportFormat.NEUTRODYNE_BACKUP,
                state = state,
                payloadPath = adopt(archive),
            )
        if (requested) assertIs<Outcome.Success<*>>(db.requestImportRestore(sessionId, clock.now()))
        return sessionId
    }

    private fun adopt(archive: Path): String {
        val target = storagePaths.cacheDir.toPath() / "import" / "repo-${archive.name}"
        fileSystem.createDirectories(checkNotNull(target.parent))
        fileSystem.copy(archive, target)
        return "import/repo-${archive.name}"
    }

    private fun tempArchive(bytes: ByteArray): Path {
        val target = root.absolutePath.toPath() / "copied" / "copy-${clock.now()}-${bytes.size}.zip"
        fileSystem.createDirectories(checkNotNull(target.parent))
        fileSystem.write(target) { write(bytes) }
        return target
    }

    private fun reader(path: Path): ArchiveReader = ZipGuard().open(path, ArchiveCaps.BACKUP)

    private fun ArchiveReader.manifest(): BackupManifest =
        open(BackupCodec.ENTRY_MANIFEST).use { codec.decodeManifest(it) }

    private fun ArchiveReader.library() = open(BackupCodec.ENTRY_LIBRARY).use { codec.decodeLibrary(it) }

    private suspend fun seedCredentialPodcast() {
        val credentialId =
            db.credentialDao().insert(
                CredentialEntity(
                    id = 0,
                    origin = CREDENTIAL_ORIGIN,
                    username = CREDENTIAL_USER,
                    secretCipher = null,
                    iv = null,
                    createdAt = 1L,
                ),
            )
        val podcast =
            db.podcastDao().insertPodcast(
                podcastEntity(
                    feedUrl = "https://member.example.com/feed",
                    feedKey = "member.example.com/feed",
                ) { copy(credentialId = credentialId) },
            )
        val secretId =
            assertNotNull(
                secrets.put(CREDENTIAL_ORIGIN, CREDENTIAL_USER, CREDENTIAL_PASS.toCharArray(), emptyMap()),
            )
        secrets.podcastCredentialIds.value = secrets.podcastCredentialIds.value + (podcast to secretId)
    }

    /** Runs [write] and cancels it right after the COMMIT of a transaction that ran [statement]. */
    private suspend fun CoroutineScope.cancelAtCommit(
        statement: String,
        write: suspend () -> Unit,
    ) {
        lateinit var caller: Job
        caller = launch(start = CoroutineStart.LAZY) { write() }
        driver.arm(statement) { caller.cancel() }
        caller.start()
        caller.join()
        assertEquals(1, driver.cancellations, "the commit hook fired")
    }

    /** Wraps the fake to count `forPodcast` secret reads a save would repeat. */
    private class CountingSecretStore(
        private val inner: SecretStore,
    ) : SecretStore {
        var forPodcastCalls = 0

        override suspend fun awaitLoaded() = inner.awaitLoaded()

        override fun basicAuthorization(origin: Origin): String? = inner.basicAuthorization(origin)

        override suspend fun put(
            origin: String,
            username: String,
            secret: CharArray,
            attributes: Map<String, String>,
        ): Long? = inner.put(origin, username, secret, attributes)

        override suspend fun get(origin: String): StoredSecret? = inner.get(origin)

        override suspend fun remove(origin: String) = inner.remove(origin)

        override suspend fun forPodcast(podcastId: Long): BasicCredentials? {
            forPodcastCalls++
            return inner.forPodcast(podcastId)
        }

        override suspend fun podcastIndexKey(): Pair<String, String>? = inner.podcastIndexKey()
    }

    /** Records the destination copy; `failWith`/`explode` reproduce the seam's error lanes. */
    private class RecordingExportDestination : ExportDestination {
        /** `(destinationUri, copied bytes)` per accepted save, in order. */
        val copied = mutableListOf<Pair<String, ByteArray>>()
        val sources = mutableListOf<Path>()
        var failWith: ExportError? = null
        var explode = false

        override fun shareUri(fileName: String): String = "content://share/$fileName"

        override suspend fun save(
            source: Path,
            destinationUri: String,
        ): Outcome<Unit, ExportError> {
            sources += source
            if (explode) error("provider exploded")
            failWith?.let { return Outcome.Failure(it) }
            val bytes = FileSystem.SYSTEM.read(source) { readByteArray() }
            copied += destinationUri to bytes
            return Outcome.Success(Unit)
        }
    }

    private companion object {
        const val CREDENTIAL_ORIGIN = "https://member.example.com"
        const val CREDENTIAL_USER = "alice"
        const val CREDENTIAL_PASS = "s3cret"
    }
}
