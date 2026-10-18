// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.FakeImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.FakePayloadSource
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.newNetworkClients
import ch.lkmc.neutrodyne.core.data.requestImportRestore
import ch.lkmc.neutrodyne.core.data.seedImportSession
import ch.lkmc.neutrodyne.core.data.storagePathsFor
import ch.lkmc.neutrodyne.core.data.testBuildInfo
import ch.lkmc.neutrodyne.core.data.testClassifier
import ch.lkmc.neutrodyne.core.data.testUserAgent
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.backup.AppInfoV1
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.CountsV1
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * M3 C4b's [AutoSnapshotProducer] over real Room, filesystem, `BackupWriter`/`ZipGuard`/
 * `BackupCodec` and recording scheduler seams: the enabled/restore/foreign guards preserving
 * existing bytes, the level ladder and its cap, status metadata, disable/discard ordering
 * and the desktop's zero-I/O unsupported lane.
 */
class AutoSnapshotProducerTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val settings = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val snapshots = FakeSnapshotScheduler()
    private val workScheduler = FakeImportWorkScheduler()
    private val fileSystem = FileSystem.SYSTEM
    private val root = createTempDirectory("nd-auto-snapshot").toFile()
    private val storagePaths = storagePathsFor(root)
    private val codec = BackupCodec()

    private val payloads =
        PayloadStore(
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            payloads = FakePayloadSource(),
            httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
            netErrors = testClassifier,
            io = Dispatchers.Default,
        )
    private val cleaner = ImportSessionCleaner(db, payloads, clock, Dispatchers.IO)
    private val exporter = OpmlExporter(db, secrets, clock, Dispatchers.Default)
    private val androidInfo = testBuildInfo().copy(platform = BuildInfo.Platform.ANDROID)

    private fun writerOf(archives: ArchiveWriterFactory = ZipArchiveWriter()) =
        BackupWriter(
            db,
            settings,
            secrets,
            archives,
            ZipGuard(),
            exporter,
            androidInfo,
            clock,
            fileSystem,
            Dispatchers.IO,
        )

    private fun producerOf(
        writer: BackupWriter = writerOf(),
        buildInfo: BuildInfo = androidInfo,
        // 05's 20 MB compressed cap — `AutoSnapshotProducer`'s default; small caps walk the
        // ladder without 20 MB fixtures.
        maxSnapshotBytes: Long = 20_000_000L,
    ) = AutoSnapshotProducer(
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
        maxSnapshotBytes = maxSnapshotBytes,
    )

    private val producer = producerOf()

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    // --- Gates --------------------------------------------------------------------------------

    @Test
    fun aDisabledLaneWritesNothingAndMintsNothing() =
        runTest {
            settings.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, false)

            assertEquals(SnapshotResult.Disabled, producer.produce())

            assertFalse(fileSystem.exists(snapshotPath()))
            assertEquals(0L, settings.get(BackupSettingKeys.LAST_SNAPSHOT_AT))
            assertEquals("", settings.get(BackupSettingKeys.INSTALLATION_ID))
        }

    @Test
    fun restoreWorkGuardsTheWriteAndKeepsTheFile() =
        runTest {
            writeOwnSnapshot()
            val before = fileSystem.read(snapshotPath()) { readByteArray() }

            // Native work running — and a check that cannot be answered fails closed too.
            workScheduler.restoreWork = true
            assertEquals(SnapshotResult.GuardedRestore, producer.produce())
            workScheduler.restoreWork = false
            workScheduler.fail = true
            assertEquals(SnapshotResult.GuardedRestore, producer.produce())

            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
            assertEquals("", settings.get(BackupSettingKeys.LAST_SNAPSHOT_ERROR))
        }

    @Test
    fun aDurableRestoreIntentGuardsTheWrite() =
        runTest {
            writeOwnSnapshot()
            val before = fileSystem.read(snapshotPath()) { readByteArray() }
            seedPendingRestore()

            assertEquals(SnapshotResult.GuardedRestore, producer.produce())

            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
        }

    @Test
    fun anUnreadableIntentRowFailsClosed() =
        runTest {
            writeOwnSnapshot()
            val before = fileSystem.read(snapshotPath()) { readByteArray() }
            val corrupt =
                db.seedImportSession(
                    options = ImportOptions(restoreMode = RestoreMode.MERGE),
                    format = ImportFormat.NEUTRODYNE_BACKUP,
                    payloadPath = "import/x.bin",
                )
            db.importDao().setOptionsJson(corrupt, "{not-json")

            assertEquals(SnapshotResult.GuardedRestore, producer.produce())
            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
            // The unreadable row survived the cleanup pass too.
            assertNotNull(db.importDao().sessionById(corrupt))
        }

    // --- The foreign guard ---------------------------------------------------------------------

    @Test
    fun aForeignSnapshotIsNeverOverwritten() =
        runTest {
            writeSnapshotAs("install-other")
            val before = fileSystem.read(snapshotPath()) { readByteArray() }

            assertEquals(SnapshotResult.GuardedForeign, producer.produce())

            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
            assertEquals(0L, settings.get(BackupSettingKeys.LAST_SNAPSHOT_AT))
            assertTrue(producer.status().foreignPending)
        }

    @Test
    fun aMissingInstallationIdKeepsAnUnprovableFile() =
        runTest {
            // A snapshot landed before this install ever wrote — nothing local proves it.
            writeSnapshotAs("install-elsewhere")
            val before = fileSystem.read(snapshotPath()) { readByteArray() }

            assertEquals(SnapshotResult.GuardedForeign, producer.produce())

            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
            assertEquals("", settings.get(BackupSettingKeys.INSTALLATION_ID))
        }

    @Test
    fun aNewerManifestStaysProtected() =
        runTest {
            writeSnapshotAs("install-other") { it.copy(minReaderVersion = 99) }
            val before = fileSystem.read(snapshotPath()) { readByteArray() }

            assertEquals(SnapshotResult.GuardedForeign, producer.produce())
            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
        }

    @Test
    fun aCorruptFileStaysProtected() =
        runTest {
            fileSystem.createDirectories(backupDir())
            fileSystem.write(snapshotPath()) { writeUtf8("not a backup at all") }
            val before = fileSystem.read(snapshotPath()) { readByteArray() }

            assertEquals(SnapshotResult.GuardedForeign, producer.produce())
            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
            assertTrue(producer.status().foreignPending)
        }

    // --- The write itself -----------------------------------------------------------------------

    @Test
    fun aWritePublishesTheFileAndItsMetadata() =
        runTest {
            seedLibrary()

            val result = producer.produce()

            val written = assertIs<SnapshotResult.Written>(result)
            assertTrue(written.summary.bytes > 0)
            assertTrue(fileSystem.exists(snapshotPath()))
            // The writer staged `auto-snapshot.zip.tmp` — no stray staging file survives.
            assertFalse(fileSystem.exists(backupDir() / "$AUTO_SNAPSHOT_FILE.tmp"))
            val status = producer.status()
            assertTrue(status.supported)
            assertTrue(status.enabled)
            assertEquals(clock.now(), status.lastWrittenAt)
            assertEquals(written.summary.bytes, status.bytes)
            assertEquals(0, status.level)
            assertNull(status.lastError)
            assertFalse(status.foreignPending)
            // The minted installation ID is the manifest's — the file is provably ours.
            val installationId = settings.get(BackupSettingKeys.INSTALLATION_ID)
            assertTrue(installationId.isNotEmpty())
            assertEquals(installationId, manifestInstallationId(snapshotPath()))
        }

    @Test
    fun theLevelLadderRetriesOnlyOnTooLarge() =
        runTest {
            seedLibrary(withHistory = true)
            // Measure every level with the real writer (sizes shrink as lines slim and drop),
            // then cap just above the smallest end: the first fitting level must publish.
            // A small slack absorbs any timestamp jitter between measuring and producing.
            val sizes =
                listOf(
                    SnapshotLevel.FULL,
                    SnapshotLevel.SLIM_HISTORY,
                    SnapshotLevel.DROP_HISTORY,
                    SnapshotLevel.ACTIVE_ONLY,
                ).map { measureSize(it) }
            val smallest = sizes.last()
            val gap = sizes.dropLast(1).filter { it > smallest }.minOfOrNull { it - smallest }
            val cap = if (gap != null && gap > 128) smallest + 64 else smallest
            val expected = sizes.indexOfFirst { it <= cap }
            assertTrue(expected > 0, "the fixture must need a reduced level (sizes: $sizes)")

            val capped = producerOf(writerOf(), maxSnapshotBytes = cap)

            val result = capped.produce()

            assertIs<SnapshotResult.Written>(result)
            assertEquals(expected, settings.get(BackupSettingKeys.LAST_SNAPSHOT_LEVEL))
            assertEquals(expected, capped.status().level)
        }

    @Test
    fun aStillTooLargeFinalLevelKeepsThePreviousSnapshot() =
        runTest {
            writeOwnSnapshot()
            val before = fileSystem.read(snapshotPath()) { readByteArray() }
            val writtenAt = settings.get(BackupSettingKeys.LAST_SNAPSHOT_AT)
            clock.advanceBy(1.hours)
            val capped = producerOf(writerOf(), maxSnapshotBytes = 1)

            val result = capped.produce()

            assertEquals(SnapshotResult.Failed(BackupError.TooLarge), result)
            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
            assertEquals("too_large", settings.get(BackupSettingKeys.LAST_SNAPSHOT_ERROR))
            // The preserved file's own metadata is untouched.
            assertEquals(writtenAt, settings.get(BackupSettingKeys.LAST_SNAPSHOT_AT))
        }

    @Test
    fun aWriteFailurePreservesTheFileAndRecordsTheError() =
        runTest {
            writeOwnSnapshot()
            val before = fileSystem.read(snapshotPath()) { readByteArray() }
            val failing =
                writerOf(
                    archives =
                        ArchiveWriterFactory { _: Path, _: Int ->
                            throw IOException("disk full")
                        },
                )
            val producer = producerOf(writer = failing)

            val result = producer.produce()

            assertEquals(SnapshotResult.Failed(BackupError.Io), result)
            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
            assertEquals("io", settings.get(BackupSettingKeys.LAST_SNAPSHOT_ERROR))
        }

    @Test
    fun aSuccessfulWriteSweepsOnlyRetiredRemnants() =
        runTest {
            writeOwnSnapshot()
            fileSystem.write(backupDir() / "restored-123.zip") { writeUtf8("old") }
            fileSystem.write(backupDir() / "keep.me") { writeUtf8("unrelated") }
            fileSystem.write(backupDir() / "restored-notazip") { writeUtf8("not a zip") }

            assertIs<SnapshotResult.Written>(producer.produce())

            assertFalse(fileSystem.exists(backupDir() / "restored-123.zip"))
            // "restored-notazip" does not match `restored-{ts}.zip` and stays.
            assertTrue(fileSystem.exists(backupDir() / "restored-notazip"))
            assertEquals("unrelated", fileSystem.read(backupDir() / "keep.me") { readUtf8() })
        }

    // --- Controls --------------------------------------------------------------------------------

    @Test
    fun disablingAForeignPendingSnapshotIsDeclined() =
        runTest {
            writeSnapshotAs("install-other")
            val before = fileSystem.read(snapshotPath()) { readByteArray() }

            assertFalse(producer.setEnabled(false))

            assertTrue(settings.get(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED))
            assertContentEquals(before, fileSystem.read(snapshotPath()) { readByteArray() })
            assertEquals(0, snapshots.cancelScheduledCalls)
        }

    @Test
    fun disablingAnOwnSnapshotCancelsWorkAndRemovesIt() =
        runTest {
            writeOwnSnapshot()

            assertTrue(producer.setEnabled(false))

            assertFalse(settings.get(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED))
            assertFalse(fileSystem.exists(snapshotPath()))
            assertEquals(1, snapshots.cancelScheduledCalls)
            // Re-enabling re-arms the debounced write (the periodic returns at next start).
            assertTrue(producer.setEnabled(true))
            assertEquals(1, snapshots.requestSoonCalls)
        }

    @Test
    fun discardRetiresThePendingForeignSnapshot() =
        runTest {
            writeSnapshotAs("install-other")

            assertTrue(producer.discardPending())

            assertFalse(fileSystem.exists(snapshotPath()))
            val retired = fileSystem.list(backupDir()).filter { isRetiredSnapshotFile(it.name) }
            assertEquals(1, retired.size)
            assertFalse(producer.status().foreignPending)
            assertEquals("", settings.get(BackupSettingKeys.INSTALLATION_ID))
        }

    @Test
    fun discardNeverRetiresAnOwnSnapshot() =
        runTest {
            writeOwnSnapshot()

            assertFalse(producer.discardPending())

            assertTrue(fileSystem.exists(snapshotPath()))
            assertTrue(fileSystem.list(backupDir()).none { isRetiredSnapshotFile(it.name) })
        }

    @Test
    fun aConcurrentDisableWaitsForTheWrite() =
        runTest {
            // The archive factory blocks inside the writer's stage: the producer holds its
            // mutex for the whole pass, so a racing disable can only run before or after.
            val gate = CompletableDeferred<Unit>()
            val blocking =
                writerOf(
                    archives =
                        ArchiveWriterFactory { path: Path, level: Int ->
                            runBlocking { gate.await() }
                            ZipArchiveWriter().create(path, level)
                        },
                )
            val blocked = producerOf(writer = blocking)

            val produceJob = async { blocked.produce() }
            val disableJob = async { blocked.setEnabled(false) }

            gate.complete(Unit)
            assertIs<SnapshotResult.Written>(produceJob.await())
            assertTrue(disableJob.await())
            // Serialized: the write finished, then the disable removed its file.
            assertFalse(fileSystem.exists(snapshotPath()))
            assertFalse(settings.get(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED))
        }

    // --- Status and the desktop lane ------------------------------------------------------------

    @Test
    fun observedStatusFollowsSettingsAndControls() =
        runTest {
            // The in-memory schema is created by the first query — touch it before the
            // invalidation tracker's flow does.
            db.importDao().backupPreviewSessions()
            val observed = producerOf()
            assertTrue(observed.observeStatus().first().enabled)
            assertNull(observed.observeStatus().first().lastWrittenAt)

            writeOwnSnapshot(observed)
            assertEquals(clock.now(), observed.observeStatus().first().lastWrittenAt)

            observed.setEnabled(false)
            assertFalse(observed.observeStatus().first().enabled)
        }

    @Test
    fun aStatusReadNeverMintsAnInstallationId() =
        runTest {
            db.importDao().backupPreviewSessions() // schema warm-up — see above
            producer.status()
            producer.observeStatus().first()
            assertEquals("", settings.get(BackupSettingKeys.INSTALLATION_ID))
            assertTrue(settings.calls.none { it == "set(backup.installation_id)" })
        }

    @Test
    fun theDesktopLaneIsUnsupportedAndTouchesNothing() =
        runTest {
            val desktop = producerOf(buildInfo = testBuildInfo())

            assertEquals(SnapshotResult.Unsupported, desktop.produce())
            assertFalse(desktop.setEnabled(true))
            assertFalse(desktop.setEnabled(false))
            assertFalse(desktop.discardPending())
            val status = desktop.observeStatus().first()
            assertFalse(status.supported)
            assertFalse(status.enabled)
            assertNull(status.lastWrittenAt)
            assertFalse(status.foreignPending)
            // No automatic filesystem work: the snapshot directory was never created.
            assertFalse(fileSystem.exists(backupDir()))
            assertEquals(0, snapshots.requestSoonCalls)
            assertEquals(0, snapshots.cancelScheduledCalls)
        }

    // --- Helpers ---------------------------------------------------------------------------------

    private fun snapshotPath(): Path = autoSnapshotPathOf(storagePaths)

    private fun backupDir(): Path = backupDirOf(storagePaths)

    private suspend fun seedLibrary(withHistory: Boolean = false) {
        val podcastId =
            db
                .podcastDao()
                .insertPodcast(podcastEntity(feedUrl = FEED_A, feedKey = feedKey(FEED_A)))
        if (withHistory) {
            val episodeId =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcastId, identityKey = "ep-1") {
                        copy(enclosureUrl = "https://cdn.example.com/ep-1.mp3")
                    },
                )
            db
                .episodeStateDao()
                .upsert(
                    episodeStateEntity(
                        episodeId,
                        playedAt = 1_600_000_000_000L,
                        updatedAt = 1_600_000_000_000L,
                    ),
                )
        }
    }

    /** A successful own snapshot: mints `backup.installation_id`, publishes the file. */
    private suspend fun writeOwnSnapshot(producer: AutoSnapshotProducer = this.producer) {
        seedLibrary()
        assertIs<SnapshotResult.Written>(producer.produce())
        assertTrue(fileSystem.exists(snapshotPath()))
    }

    /**
     * A foreign pending snapshot: a manifest-only archive whose `installationId` the local
     * `backup.installation_id` does not match (the probe reads no other entry anyway).
     * [mutate] models newer/corrupt-on-write manifests.
     */
    private fun writeSnapshotAs(
        installationId: String,
        mutate: (BackupManifest) -> BackupManifest = { it },
    ) {
        fileSystem.createDirectories(backupDir())
        val manifest =
            mutate(
                BackupManifest(
                    createdAt = "2026-10-22T00:00:00Z",
                    kind = BackupKind.AUTO_SNAPSHOT,
                    app = AppInfoV1("1.0", 1L, "android-test", "android"),
                    dbSchemaVersion = 1,
                    episodeKeysVersion = 1,
                    urlNormalizerVersion = UrlNormalizer.VERSION,
                    installationId = installationId,
                    counts = CountsV1(0, 0, 0, 0, 0, 0, 0),
                    entries = emptyList(),
                ),
            )
        ZipArchiveWriter().create(snapshotPath()).use { archive ->
            archive.entry(BackupCodec.ENTRY_MANIFEST).buffer().use {
                it.write(codec.encodeManifest(manifest).encodeToByteArray())
            }
        }
    }

    private fun manifestInstallationId(file: Path): String? =
        try {
            ZipGuard().open(file, ArchiveCaps.BACKUP).use { archive ->
                archive.open(BackupCodec.ENTRY_MANIFEST).use { codec.decodeManifest(it).installationId }
            }
        } catch (_: Throwable) {
            null
        }

    private fun feedKey(feedUrl: String): String = checkNotNull(UrlNormalizer.forIdentity(feedUrl))

    private suspend fun seedPendingRestore(): Long {
        val sessionId =
            db.seedImportSession(
                options =
                    ImportOptions(
                        restoreMode = RestoreMode.MERGE,
                        restoreCategories = setOf(RestoreCategory.HISTORY),
                    ),
                format = ImportFormat.NEUTRODYNE_BACKUP,
                payloadPath = "import/pending.bin",
            )
        assertIs<Outcome.Success<*>>(db.requestImportRestore(sessionId, clock.now()))
        assertEquals(ImportState.PREVIEW, db.importDao().sessionById(sessionId)?.state)
        return sessionId
    }

    /** The compressed bytes a real writer write produces at [level]. */
    private suspend fun measureSize(level: SnapshotLevel): Long {
        val dir = storagePaths.cacheDir.toPath()
        fileSystem.createDirectories(dir)
        val out = dir / "measure-${level.wire}.zip"
        val written =
            writerOf().write(BackupKind.AUTO_SNAPSHOT, out, includeCredentials = false, level = level)
        assertIs<Outcome.Success<*>>(written)
        val size = checkNotNull(fileSystem.metadata(out).size)
        fileSystem.delete(out)
        return size
    }

    private companion object {
        const val FEED_A = "https://a.example.com/feed.xml"
    }
}
