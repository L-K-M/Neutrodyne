// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.data.FakeImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.FakePayloadSource
import ch.lkmc.neutrodyne.core.data.exporting.OpmlExporter
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.newNetworkClients
import ch.lkmc.neutrodyne.core.data.storagePathsFor
import ch.lkmc.neutrodyne.core.data.testBuildInfo
import ch.lkmc.neutrodyne.core.data.testClassifier
import ch.lkmc.neutrodyne.core.data.testUserAgent
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipArchiveWriter
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.ForwardingSource
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 05 Snapshot testing's scale leg (M3 acceptance): a real Room database seeded with
 * [EPISODE_COUNT] distinct, state-bearing episodes — 4,000-row `insertEpisodes` batches and one
 * reused `episode_state` prepared statement per batch, never a materialised library — then the
 * production path end to end: [AutoSnapshotProducer] → [BackupWriter] → `ZipArchiveWriter` at the
 * format's deflate level 6, validated back through `ZipGuard` + `BackupCodec`. Asserts the
 * published archive's manifest counts and per-entry sha256/bytes, the streamed 400,000-line
 * count with independent played/in-progress recounts, and level 0 ([SnapshotLevel.FULL]) inside
 * the 20 MB compressed cap — all inside this task's 128 MB heap. Timings and sizes are written
 * to `build/review-e2/snapshot-scale.txt` for the task report.
 */
class SnapshotScaleTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val settings = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val snapshots = FakeSnapshotScheduler()
    private val workScheduler = FakeImportWorkScheduler()
    private val fileSystem = FileSystem.SYSTEM
    private val root = createTempDirectory("nd-snapshot-scale").toFile()
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

    private val producer =
        AutoSnapshotProducer(
            db = db,
            writer =
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
                ),
            cleaner = cleaner,
            readers = ZipGuard(),
            settings = settings,
            snapshots = snapshots,
            workScheduler = workScheduler,
            buildInfo = androidInfo,
            clock = clock,
            storagePaths = storagePaths,
            fileSystem = fileSystem,
            io = Dispatchers.IO,
        )

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun fullSnapshotAt400kEpisodes() =
        runBlocking {
            // The proof only counts under the dedicated 128 MB capped task's heap.
            assertTrue(
                Runtime.getRuntime().maxMemory() <= 128L * 1024 * 1024,
                "SnapshotScaleTest must run under the capped mutationTest heap",
            )

            // Wall-clock phases; the evidence report carries all three.
            val seedStarted = System.nanoTime()
            seed()
            val seedMs = (System.nanoTime() - seedStarted) / 1_000_000

            val produceStarted = System.nanoTime()
            producer.setEnabled(true)
            val report = producer.produceReport()
            val produceMs = (System.nanoTime() - produceStarted) / 1_000_000

            val written = assertIs<SnapshotResult.Written>(report.result)
            assertEquals(EPISODE_COUNT, written.summary.episodeLines)
            assertEquals(1, written.summary.podcasts)
            // Status bookkeeping: level 0 FULL won the ladder, previous status was empty.
            assertNull(report.before.lastWrittenAt)
            assertEquals(SnapshotLevel.FULL.wire, report.after.level)
            assertEquals(written.summary.bytes, report.after.bytes)
            assertNull(report.after.lastError)
            assertTrue(report.after.supported && report.after.enabled)

            val snapshot = producer.snapshotFile
            val onDisk = checkNotNull(fileSystem.metadataOrNull(snapshot)?.size)
            assertEquals(written.summary.bytes, onDisk)
            assertTrue(onDisk <= SNAPSHOT_CAP_BYTES, "FULL must fit the 20 MB compressed cap")

            val verifyStarted = System.nanoTime()
            val stats = verify(snapshot)
            val verifyMs = (System.nanoTime() - verifyStarted) / 1_000_000

            val evidence =
                "episodeLines=${EPISODE_COUNT}\n" +
                    "played=${stats.played}\n" +
                    "inProgress=${stats.inProgress}\n" +
                    "archiveBytes=$onDisk\n" +
                    "episodesEntryInflatedBytes=${stats.episodesBytes}\n" +
                    "deflateLevel=${ArchiveWriterFactory.DEFAULT_DEFLATE_LEVEL}\n" +
                    "maxHeapBytes=${Runtime.getRuntime().maxMemory()}\n" +
                    "seedMs=$seedMs\nproduceMs=$produceMs\nverifyMs=$verifyMs\n" +
                    "wallMs=${(System.nanoTime() - seedStarted) / 1_000_000}\n"
            File(evidenceDir(), "snapshot-scale.txt").writeText(evidence)
            println(evidence)
        }

    // --- Seeding: bounded batches + a reused prepared statement; nothing retained -------------

    private suspend fun seed() {
        val podcast =
            db.podcastDao().insertPodcast(
                podcastEntity(
                    feedUrl = "https://scale.example.com/f.xml",
                    title = "Scale feed",
                    subscribedAt = BASE_MS,
                    guidCoverageSince = BASE_MS,
                ) { copy(podcastGuid = "urn:scale:podcast") },
            )
        settings.set(BackupSettingKeys.INSTALLATION_ID, "install-scale-1")

        var offset = 0
        while (offset < EPISODE_COUNT) {
            val size = minOf(SEED_BATCH, EPISODE_COUNT - offset)
            db.withWriteTransaction {
                val ids =
                    db.ingestDao().insertEpisodes(List(size) { j -> episodeRow(offset + j, podcast) })
                db.useWriterConnection { conn ->
                    conn.usePrepared(STATE_SQL) { stmt ->
                        ids.forEachIndexed { j, id -> stmt.bindState(id, offset + j) }
                    }
                }
            }
            offset += size
        }
    }

    /** Compact but meaningful rows: every non-null field below lands on the exported line. */
    private fun episodeRow(
        i: Int,
        podcastId: Long,
    ): EpisodeEntity =
        episodeEntity(
            podcastId = podcastId,
            identityKey = "e%07d".format(i),
            title = "Ep $i",
            sortDate = BASE_MS - i * 1_000L,
            feedOrder = -i,
        ) {
            copy(
                pubDate = sortDate,
                guid = "urn:scale:$i",
                enclosureUrl = "https://cdn.example.com/e$i.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 8_000_000L + i % 90,
                durationMs = 1_800_000L,
                contentHash = i.toLong(),
            )
        }

    /**
     * The deterministic state mix: 60% played (`pl`/`pc`/`lp`), 10% in-progress (`st`), every
     * 1,000th favourite with a measured duration; every row carries `updatedAt`, so all
     * 400,000 qualify for `episodes.jsonl` (`s.episodeId IS NOT NULL`, `ts != 0`).
     */
    private fun SQLiteStatement.bindState(
        id: Long,
        i: Int,
    ) {
        val played = i % 10 < 6
        bindLong(1, id)
        if (i % 10 == 7) bindLong(2, BASE_MS + i) else bindNull(2)
        if (played) bindLong(3, BASE_MS + i) else bindNull(3)
        bindLong(4, if (played) 1L else 0L)
        if (played) bindLong(5, BASE_MS + i) else bindNull(5)
        bindLong(6, if (i % 1000 == 0) 1L else 0L)
        bindNull(7)
        if (i % 1000 == 0) bindLong(8, 1_800_000L + i % 600) else bindNull(8)
        bindLong(9, BASE_MS + i)
        step()
        reset()
    }

    // --- Verification: manifest contract + streamed, independently-counted entry bytes --------

    private class Stats(
        val episodesBytes: Long,
        val played: Int,
        val inProgress: Int,
    )

    private fun verify(snapshot: Path): Stats =
        ZipGuard().open(snapshot, ArchiveCaps.BACKUP).use { reader ->
            assertEquals(
                listOf(
                    BackupCodec.ENTRY_EPISODES,
                    BackupCodec.ENTRY_LIBRARY,
                    BackupCodec.ENTRY_QUEUE,
                    BackupCodec.ENTRY_SETTINGS,
                    BackupCodec.ENTRY_MANIFEST,
                ),
                reader.entryNames,
            )
            val manifest = reader.manifest()
            assertEquals(BackupKind.AUTO_SNAPSHOT, manifest.kind)
            assertEquals("install-scale-1", manifest.installationId)
            assertEquals(SnapshotLevel.FULL.wire, manifest.snapshotLevel)
            assertEquals(1, manifest.counts.podcasts)
            assertEquals(EPISODE_COUNT, manifest.counts.episodeLines)
            assertEquals(EXPECTED_PLAYED, manifest.counts.played)
            assertEquals(EXPECTED_IN_PROGRESS, manifest.counts.inProgress)
            assertEquals(0, manifest.counts.upNext)

            // Small entries: full read; sha256 + byte count must match the manifest.
            for (info in manifest.entries) {
                if (info.name == BackupCodec.ENTRY_EPISODES) continue
                val bytes = reader.bytes(info.name)
                assertEquals(info.bytes, bytes.size.toLong(), "${info.name} bytes")
                assertEquals(info.sha256, bytes.toByteString().sha256().hex(), "${info.name} sha256")
            }

            // episodes.jsonl pass 1: streamed line count + independent state recount.
            val keys = HashSet<String>(EPISODE_COUNT)
            var played = 0
            var inProgress = 0
            var lines = 0
            codec.episodeLines(reader.open(BackupCodec.ENTRY_EPISODES)).use { stream ->
                while (stream.hasNext()) {
                    val line = stream.next()
                    lines++
                    keys += line.k
                    if (line.pl != null) played++
                    if (line.pl == null && line.st != null) inProgress++
                }
            }
            assertEquals(EPISODE_COUNT, lines)
            assertEquals(EPISODE_COUNT, keys.size)
            assertEquals(EXPECTED_PLAYED, played)
            assertEquals(EXPECTED_IN_PROGRESS, inProgress)

            // episodes.jsonl pass 2: streamed bytes + sha256 against the manifest entry.
            val info = manifest.entries.first { it.name == BackupCodec.ENTRY_EPISODES }
            assertEquals(EPISODE_COUNT, info.records)
            var inflated = 0L
            reader.open(BackupCodec.ENTRY_EPISODES).use { source ->
                val hashing = HashingSource.sha256(source)
                val counting =
                    object : ForwardingSource(hashing) {
                        override fun read(
                            sink: Buffer,
                            byteCount: Long,
                        ): Long {
                            val n = super.read(sink, byteCount)
                            if (n > 0) inflated += n
                            return n
                        }
                    }
                counting.buffer().use { it.readAll(blackholeSink()) }
                assertEquals(info.bytes, inflated, "episodes.jsonl bytes")
                assertEquals(info.sha256, hashing.hash.hex(), "episodes.jsonl sha256")
            }
            assertTrue(
                inflated <= ArchiveCaps.BACKUP.maxEntryBytes,
                "episodes.jsonl must stay within the reader's per-entry cap",
            )
            Stats(inflated, played, inProgress)
        }

    private fun ArchiveReader.bytes(name: String): ByteArray = open(name).use { it.buffer().readByteArray() }

    private fun ArchiveReader.manifest(): BackupManifest =
        open(BackupCodec.ENTRY_MANIFEST).use { codec.decodeManifest(it) }

    private fun evidenceDir(): File {
        val base = System.getProperty("neutrodyne.rootDir")?.let(::File) ?: File(".")
        return File(base, "build/review-e2").apply { mkdirs() }
    }

    private companion object {
        const val EPISODE_COUNT = 400_000
        const val SEED_BATCH = 4_000
        const val EXPECTED_PLAYED = 240_000
        const val EXPECTED_IN_PROGRESS = 40_000
        const val BASE_MS = 1_791_000_000_000L

        /** 05 Snapshot production step 6's compressed-size ceiling — `AutoSnapshotProducer`'s default. */
        const val SNAPSHOT_CAP_BYTES = 20_000_000L
        const val STATE_SQL =
            "INSERT INTO episode_state(episodeId, startedAt, playedAt, playCount, lastPlayedAt," +
                " isFavorite, downloadDismissedAt, measuredDurationMs, updatedAt)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
