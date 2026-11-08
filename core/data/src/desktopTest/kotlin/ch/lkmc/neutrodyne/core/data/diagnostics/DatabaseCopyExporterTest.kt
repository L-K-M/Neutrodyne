// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.data.exporting.exportDirOf
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** `DatabaseCopyExporter`'s guards — what must fail rather than produce a copy. */
class DatabaseCopyExporterTest {
    private val driver = BundledSQLiteDriver()
    private val temp =
        Files
            .createTempDirectory("nd-export-test")
            .toFile()
            .apply { deleteOnExit() }
            .toPath()
    private val storagePaths =
        StoragePaths(
            AppDirs(
                data = temp,
                config = temp,
                cache = temp,
                state = temp,
                logs = temp,
                downloadsDefault = temp,
            ),
        )

    /** Only the members [DatabaseCopyExporter] reads; the rest must never be called. */
    private class FakeFactory(
        override val databasePath: String,
        private val usable: Long?,
    ) : DatabaseFactory {
        override fun builder() = throw UnsupportedOperationException()

        override fun exists() = throw UnsupportedOperationException()

        override fun usableSpaceBytes(): Long? = usable

        override fun quarantine(stamp: String) = throw UnsupportedOperationException()

        override fun pruneQuarantine(now: Long) = throw UnsupportedOperationException()

        override var quarantineMarker: Boolean
            get() = throw UnsupportedOperationException()
            set(_) = throw UnsupportedOperationException()

        override var pendingQuarantine: String?
            get() = throw UnsupportedOperationException()
            set(_) = throw UnsupportedOperationException()
    }

    /**
     * The exporter opens its copy on the injected driver exactly once per `produce()`:
     * after `VACUUM INTO` wrote the raw database and before `DiagExportScrub` ran — the
     * window in which a still-live FileProvider grant would read unscrubbed data.
     * [onExportOpen] is the deterministic gate inside that window.
     */
    private class ScrubGate(
        private val delegate: SQLiteDriver,
        private val exportDir: String,
    ) : SQLiteDriver {
        val opened = CopyOnWriteArrayList<String>()

        @Volatile var onExportOpen: () -> Unit = {}

        override fun open(fileName: String): SQLiteConnection {
            if (fileName.startsWith(exportDir)) {
                opened += fileName
                onExportOpen()
            }
            return delegate.open(fileName)
        }
    }

    /**
     * The export used to be `VACUUM INTO`d under its final share name, deleted and
     * recreated on every run: a second export in the same minute rewrote the path a
     * previously minted `content://` grant still resolves to — serving raw rows under
     * the old URI between the vacuum and the scrub. [ScrubGate] reads the first result
     * path inside that window; it must hold the first scrubbed copy, never the second
     * raw one, and must not be deleted or overwritten at all.
     */
    @Test
    fun aSameMinuteReExportNeverWritesRawDataIntoAnEarlierSharePath() =
        runTest {
            val dbDir = Files.createTempDirectory("nd-export-src")
            val db = TestDb.file(dbDir)
            try {
                db.podcastDao().insertPodcast(
                    podcastEntity(
                        feedUrl = "https://one.example.invalid/feed?k=FIRSTRAWSECRET",
                        title = "One",
                    ),
                )
                val exportDir = exportDirOf(storagePaths)
                val gate = ScrubGate(driver, exportDir.toString())
                val exporter =
                    DatabaseCopyExporter(
                        db,
                        FakeFactory(
                            databasePath = dbDir.resolve(NeutrodyneDatabase.FILE_NAME).toString(),
                            usable = Long.MAX_VALUE,
                        ),
                        gate,
                        storagePaths,
                        FileSystem.SYSTEM,
                        // A fixed instant puts both exports in the same minute — the old
                        // code gave them the same file name.
                        TestClock(),
                        Dispatchers.Default,
                    )

                val first = exporter.produce() as Outcome.Success
                val firstBytes = FileSystem.SYSTEM.read(first.value) { readByteArray() }

                // The second export carries a secret that must never be readable through
                // the first share path.
                db.podcastDao().insertPodcast(
                    podcastEntity(
                        feedUrl = "https://two.example.invalid/feed?k=SECONDRAWSECRET",
                        title = "Two",
                    ),
                )
                val bytesAtOpen = CopyOnWriteArrayList<ByteArray>()
                gate.onExportOpen = {
                    if (FileSystem.SYSTEM.exists(first.value)) {
                        bytesAtOpen += FileSystem.SYSTEM.read(first.value) { readByteArray() }
                    }
                }
                val second = exporter.produce() as Outcome.Success

                assertNotEquals(
                    first.value,
                    second.value,
                    "a re-export must mint a fresh share path, never reuse a granted one",
                )
                assertTrue(
                    second.value.name.matches(
                        Regex("neutrodyne-diagnostics-\\d{4}-\\d{2}-\\d{2}-\\d{4}-.+\\.db"),
                    ),
                    "the final name stays dated and understandable: ${second.value.name}",
                )
                assertTrue(
                    FileSystem.SYSTEM.exists(first.value),
                    "the earlier share file must not be deleted",
                )
                assertContentEquals(
                    firstBytes,
                    FileSystem.SYSTEM.read(first.value) { readByteArray() },
                    "the earlier share file must not be overwritten",
                )
                assertEquals(1, bytesAtOpen.size, "the gate must see exactly one raw window")
                val leaked =
                    bytesAtOpen.any { bytes -> bytes.contains(SECOND_SECRET) }
                assertFalse(
                    leaked,
                    "raw second-export bytes were visible at the first share path",
                )

                // The raw second copy went through a name that was never shared.
                assertTrue(
                    gate.opened.all { it != first.value.toString() },
                    "the scrub connection never opened the earlier share path: ${gate.opened}",
                )
            } finally {
                db.close()
                dbDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun aFailedScrubLeavesNoRawOrPartialCopyBehind() =
        runTest {
            val dbDir = Files.createTempDirectory("nd-export-src")
            val db = TestDb.file(dbDir)
            try {
                db.podcastDao().insertPodcast(podcastEntity(title = "One"))
                // A WITHOUT ROWID table has no `rowid` column: the per-table scrub throws
                // after the raw copy is already on disk.
                db.useWriterConnection { conn ->
                    conn.usePrepared("CREATE TABLE probe(id TEXT PRIMARY KEY, note TEXT) WITHOUT ROWID") {
                        it.step()
                    }
                    conn.usePrepared("INSERT INTO probe VALUES ('k', 'x')") { it.step() }
                }
                val exportDir = exportDirOf(storagePaths)
                val exporter =
                    DatabaseCopyExporter(
                        db,
                        FakeFactory(
                            databasePath = dbDir.resolve(NeutrodyneDatabase.FILE_NAME).toString(),
                            usable = Long.MAX_VALUE,
                        ),
                        driver,
                        storagePaths,
                        FileSystem.SYSTEM,
                        TestClock(),
                        Dispatchers.Default,
                    )
                assertEquals(Outcome.Failure(DiagnosticsError.FAILED), exporter.produce())
                val leftovers =
                    (FileSystem.SYSTEM.listOrNull(exportDir) ?: emptyList())
                        .filter { it.name.startsWith(DatabaseCopyExporter.NAME_PREFIX) }
                assertTrue(
                    leftovers.isEmpty(),
                    "a failed export must leave no raw or partial copy: $leftovers",
                )
            } finally {
                db.close()
                dbDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun aCancelledExportLeavesNoRawOrPartialCopyBehind() =
        runTest {
            val dbDir = Files.createTempDirectory("nd-export-src")
            val db = TestDb.file(dbDir)
            try {
                db.podcastDao().insertPodcast(podcastEntity(title = "One"))
                val exportDir = exportDirOf(storagePaths)
                val gate = ScrubGate(driver, exportDir.toString())
                val exporter =
                    DatabaseCopyExporter(
                        db,
                        FakeFactory(
                            databasePath = dbDir.resolve(NeutrodyneDatabase.FILE_NAME).toString(),
                            usable = Long.MAX_VALUE,
                        ),
                        gate,
                        storagePaths,
                        FileSystem.SYSTEM,
                        TestClock(),
                        Dispatchers.Default,
                    )
                val job = async { exporter.produce() }
                // The deterministic cancel point: the raw copy exists but is unscrubbed.
                gate.onExportOpen = { job.cancel() }
                try {
                    job.await()
                    fail("a cancelled export must not return a copy")
                } catch (expected: CancellationException) {
                    // produce() propagates the cancellation rather than reporting success.
                }
                val leftovers =
                    (FileSystem.SYSTEM.listOrNull(exportDir) ?: emptyList())
                        .filter { it.name.startsWith(DatabaseCopyExporter.NAME_PREFIX) }
                assertTrue(
                    leftovers.isEmpty(),
                    "a cancelled export must leave no raw or partial copy: $leftovers",
                )
            } finally {
                db.close()
                dbDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun unreadableDbSizeFailsInsteadOfSkippingTheSpaceGuard() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val factory =
                    FakeFactory(
                        databasePath = temp.resolve("no-such-dir").resolve("neutrodyne.db").toString(),
                        usable = Long.MAX_VALUE,
                    )
                val exporter =
                    DatabaseCopyExporter(
                        db,
                        factory,
                        driver,
                        storagePaths,
                        FileSystem.SYSTEM,
                        TestClock(),
                        Dispatchers.Default,
                    )

                // With the database size unreadable, the guard must not treat it as zero:
                // produce() used to sail through and hand back a copy.
                assertEquals(Outcome.Failure(DiagnosticsError.FAILED), exporter.produce())
            } finally {
                db.close()
            }
        }

    /** Counts how often work actually hops onto the injected IO dispatcher. */
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var dispatches = 0
            private set

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun removeStaleSweepsOnTheIoDispatcher() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val io = RecordingDispatcher(Dispatchers.Default)
                val exporter =
                    DatabaseCopyExporter(
                        db,
                        FakeFactory(databasePath = "unused.db", usable = null),
                        driver,
                        storagePaths,
                        FileSystem.SYSTEM,
                        TestClock(),
                        io,
                    )

                exporter.removeStale()
                assertTrue(io.dispatches > 0, "the export-dir sweep must run on io")
            } finally {
                db.close()
            }
        }

    @Test
    fun aHeldWriterTurnsBusyInsideTheWaitBound() =
        runTest {
            val db = TestDb.inMemory()
            val dbFile = temp.resolve("held.db")
            Files.write(dbFile, byteArrayOf(1))
            try {
                // Occupy the pool's single writer connection so acquisition outlives the bound.
                val holding = CompletableDeferred<Unit>()
                val holder =
                    launch(Dispatchers.Default) {
                        db.useWriterConnection {
                            holding.complete(Unit)
                            awaitCancellation()
                        }
                    }
                holding.await()
                try {
                    val exporter =
                        DatabaseCopyExporter(
                            db,
                            FakeFactory(databasePath = dbFile.toString(), usable = Long.MAX_VALUE),
                            driver,
                            storagePaths,
                            FileSystem.SYSTEM,
                            TestClock(),
                            Dispatchers.Default,
                            writerWaitMs = 200,
                        )
                    assertEquals(
                        Outcome.Failure(DiagnosticsError.DATABASE_BUSY),
                        exporter.produce(),
                    )
                } finally {
                    holder.cancelAndJoin()
                }
            } finally {
                db.close()
            }
        }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        size >= needle.size &&
            (0..size - needle.size).any { i -> needle.indices.all { j -> this[i + j] == needle[j] } }

    private companion object {
        val SECOND_SECRET = "SECONDRAWSECRET".encodeToByteArray()
    }
}
