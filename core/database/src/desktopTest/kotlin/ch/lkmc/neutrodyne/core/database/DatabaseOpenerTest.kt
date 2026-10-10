// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `DatabaseOpener` (02 Error handling and recovery): a fresh directory creates; a corrupt file, a
 * quarantine marker and a newer `user_version` each quarantine and recreate; failures are not
 * cached so retry succeeds; quarantine keeps only the newest copy inside the 14-day window; and
 * `onOpen` resets `sync_state.applying`.
 */
class DatabaseOpenerTest {
    private val dir: Path = Files.createTempDirectory("m1a-open")
    private val dirs = AppDirs(data = dir, config = dir, cache = dir, state = dir, logs = dir, downloadsDefault = dir)
    private val factory = DesktopDatabaseFactory(dirs)

    private fun opener(
        clock: TestClock = TestClock(),
        f: DatabaseFactory = factory,
        d: SQLiteDriver = BundledSQLiteDriver(),
    ) = DatabaseOpener(
        factory = f,
        driver = d,
        io = Dispatchers.Default,
        clock = clock,
        strictMigrations = false,
    )

    /** A healthy database file on disk, closed. */
    private suspend fun createHealthyDatabase() {
        val o = opener()
        o.awaitOpen()
        o.requireDatabase().close()
    }

    @Test
    fun freshDirectoryCreatesAndReportsOpened() =
        runTest {
            dirs.ensureCreated()
            val o = opener()
            val result = o.awaitOpen()
            assertEquals(OpenResult(created = true, recovered = null), result)
            assertEquals(DatabaseOpenState.Opened(result), o.openState.value)
            assertNotNull(o.requireDatabase().playSessionDao().get())
            o.requireDatabase().close()
        }

    @Test
    fun corruptFileIsQuarantinedAndAFreshDatabaseCreated() =
        runTest {
            dirs.ensureCreated()
            Files.write(dir.resolve(NeutrodyneDatabase.FILE_NAME), "garbage-not-sqlite".encodeToByteArray())

            val o = opener()
            val result = o.awaitOpen()

            assertEquals(RecoveryCause.CORRUPT, result.recovered)
            assertTrue(result.created)

            // The file moved to quarantine, was never deleted, and the new database works.
            val quarantine = dir.resolve("quarantine")
            val copies = Files.list(quarantine).use { it.toList() }
            assertEquals(1, copies.size)
            assertTrue(Files.exists(copies.single().resolve(NeutrodyneDatabase.FILE_NAME)))
            assertNotNull(o.requireDatabase().playSessionDao().get())
            o.requireDatabase().close()
        }

    @Test
    fun quarantineMarkerRecoversEvenAHealthyFileAndClears() =
        runTest {
            dirs.ensureCreated()
            val first = opener()
            first.awaitOpen()
            first.requireDatabase().close()

            factory.quarantineMarker = true
            val second = opener()
            val result = second.awaitOpen()

            assertEquals(RecoveryCause.CORRUPT, result.recovered)
            assertTrue(result.created)
            assertFalse(factory.quarantineMarker, "marker must be cleared after recovery")

            // A third launch sees a plain open — recovery is reported once.
            second.requireDatabase().close()
            val third = opener()
            assertEquals(OpenResult(created = false, recovered = null), third.awaitOpen())
            third.requireDatabase().close()
        }

    @Test
    fun userVersionNewerThanOursIsADowngradeQuarantine() =
        runTest {
            dirs.ensureCreated()
            val first = opener()
            first.awaitOpen()
            first.requireDatabase().close()

            BundledSQLiteDriver().open(factory.databasePath).use { conn ->
                conn.exec("PRAGMA user_version = ${NeutrodyneDatabase.VERSION + 1}")
            }

            val second = opener()
            val result = second.awaitOpen()
            assertEquals(RecoveryCause.DOWNGRADE, result.recovered)
            assertTrue(result.created)
            second.requireDatabase().close()
        }

    @Test
    fun quarantineKeepsOnlyTheNewestCopyInsideTheWindow() =
        runTest {
            dirs.ensureCreated()
            // First recovery at t0.
            val first = opener(clock = TestClock(nowMs = T0))
            first.awaitOpen()
            first.requireDatabase().close()

            // Break the database and recover again 15 days later.
            Files.write(dir.resolve(NeutrodyneDatabase.FILE_NAME), "broken".encodeToByteArray())
            val second = opener(clock = TestClock(nowMs = T0 + 15L * 24 * 60 * 60 * 1000))
            second.awaitOpen()
            second.requireDatabase().close()

            // The t0 quarantine is outside the 14-day window; only the second copy remains.
            val copies = Files.list(dir.resolve("quarantine")).use { it.toList() }
            assertEquals(1, copies.size)
            assertEquals(
                T0 + 15L * 24 * 60 * 60 * 1000,
                copies
                    .single()
                    .fileName
                    .toString()
                    .toLong(),
            )
        }

    @Test
    fun failuresAreNotCachedAndRetrySucceeds() =
        runTest {
            dirs.ensureCreated()
            var fail: Throwable? = IllegalStateException("open refused")
            val flaky =
                object : DatabaseFactory by factory {
                    override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> {
                        fail?.let { throw it }
                        return factory.builder()
                    }
                }
            val o = opener(f = flaky)

            val error = assertThrows { o.awaitOpen() }
            assertIs<DatabaseOpenException>(error)
            assertEquals(DatabaseOpenException.Reason.UNKNOWN, error.reason)
            assertIs<DatabaseOpenState.Failed>(o.openState.value)
            assertEquals(error, (o.openState.value as DatabaseOpenState.Failed).exception)

            fail = null
            val result = o.awaitOpen()
            assertTrue(result.created)
            assertEquals(DatabaseOpenState.Opened(result), o.openState.value)
            o.requireDatabase().close()
        }

    @Test
    fun diskFullSurfacesWithoutTouchingTheFile() =
        runTest {
            dirs.ensureCreated()
            val first = opener()
            first.awaitOpen()
            first.requireDatabase().close()
            val sizeBefore = Files.size(dir.resolve(NeutrodyneDatabase.FILE_NAME))
            assertTrue(sizeBefore > 0)

            val full =
                object : DatabaseFactory by factory {
                    override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> =
                        throw IllegalStateException("SQLiteFullException: database or disk is full")
                }
            val o = opener(f = full)
            val error = assertThrows { o.awaitOpen() }
            assertIs<DatabaseOpenException>(error)
            assertEquals(DatabaseOpenException.Reason.DISK_FULL, error.reason)

            // The file was not quarantined, truncated or deleted (02: nothing is deleted).
            assertEquals(sizeBefore, Files.size(dir.resolve(NeutrodyneDatabase.FILE_NAME)))
            assertFalse(Files.exists(dir.resolve("quarantine")))
        }

    @Test
    fun onOpenResetsSyncStateApplying() =
        runTest {
            dirs.ensureCreated()
            val first = opener()
            first.awaitOpen()
            first.requireDatabase().useWriterConnection { conn ->
                conn.exec("UPDATE sync_state SET applying = 1 WHERE id = 0")
            }
            first.requireDatabase().close()

            val second = opener()
            second.awaitOpen()
            val state = assertNotNull(second.requireDatabase().syncStateDao().get())
            assertFalse(state.applying, "onOpen must reset sync_state.applying on every open")
            second.requireDatabase().close()
        }

    @Test
    fun cancellingTheOpenNeitherFailsNorQuarantines() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()

            var calls = 0
            val insideForceOpen = CompletableDeferred<Unit>()
            val releaseForceOpen = CompletableDeferred<Unit>()
            val gated =
                object : SQLiteDriver by BundledSQLiteDriver() {
                    private val inner = BundledSQLiteDriver()

                    override fun open(fileName: String): SQLiteConnection {
                        calls++
                        if (calls == 2) {
                            // Room's own open (inside forceOpen) — the point where the
                            // coroutine is cancelled below.
                            insideForceOpen.complete(Unit)
                            runBlocking { releaseForceOpen.await() }
                        }
                        return inner.open(fileName)
                    }
                }
            val o = opener(d = gated)
            val attempt = async { o.awaitOpen() }
            insideForceOpen.await()
            attempt.cancel()
            releaseForceOpen.complete(Unit)
            val thrown = runCatching { attempt.await() }.exceptionOrNull()

            assertIs<CancellationException>(thrown)
            assertEquals(DatabaseOpenState.Pending, o.openState.value)
            assertFalse(
                Files.exists(dir.resolve("quarantine")),
                "a cancelled open must not quarantine the healthy database",
            )

            // Recovery is not wedged: the retry opens the healthy file normally.
            val result = o.awaitOpen()
            assertEquals(OpenResult(created = false, recovered = null), result)
            o.requireDatabase().close()
        }

    @Test
    fun sqliteNumericResultCodesDriveClassification() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()
            val dbFile = dir.resolve(NeutrodyneDatabase.FILE_NAME)

            // The bundled driver writes "Error code: N, message: …"; the framework driver adds
            // "(code N SQLITE_…)". An extended code reduces to its primary code via the low byte.
            val cases =
                listOf(
                    "Error code: 10, message: disk I/O error" to DatabaseOpenException.Reason.IO,
                    "disk I/O error (code 4618 SQLITE_IOERR_SHMOPEN)" to DatabaseOpenException.Reason.IO,
                    "unable to open database file (code 14)" to DatabaseOpenException.Reason.IO,
                    "Error code: 13, message: database or disk is full" to
                        DatabaseOpenException.Reason.DISK_FULL,
                    "database or disk is full (code 3341)" to DatabaseOpenException.Reason.DISK_FULL,
                )
            for ((message, reason) in cases) {
                val throwing =
                    object : DatabaseFactory by factory {
                        override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> =
                            throw IllegalStateException(message)
                    }
                val error = assertThrows { opener(f = throwing).awaitOpen() }
                assertIs<DatabaseOpenException>(error)
                assertEquals(reason, error.reason, message)
                assertFalse(
                    Files.exists(dir.resolve("quarantine")),
                    "$message is an IO/full failure and must never quarantine",
                )
            }
            assertTrue(Files.exists(dbFile), "the database file is untouched")
        }

    @Test
    fun extendedCorruptionCodesStillQuarantine() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()

            // An extended corruption code (3851 = 0xF0B, primary 11) with no text marker —
            // only the numeric parse can classify it. The first open fails; the post-quarantine
            // fresh build succeeds.
            val corrupt =
                object : DatabaseFactory by factory {
                    var failed = false

                    override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> {
                        if (!failed) {
                            failed = true
                            throw IllegalStateException("write failed (code 3851)")
                        }
                        return factory.builder()
                    }
                }
            val o = opener(f = corrupt)
            val result = o.awaitOpen()
            assertEquals(RecoveryCause.CORRUPT, result.recovered)
            assertTrue(result.created)
            o.requireDatabase().close()
        }

    @Test
    fun failedQuarantinePropagatesAndKeepsTheRecoveryMarker() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()
            factory.quarantineMarker = true

            val stuck =
                object : DatabaseFactory by factory {
                    var fail = true

                    override fun quarantine(stamp: String) {
                        if (fail) throw IOException("quarantine destination unavailable")
                        factory.quarantine(stamp)
                    }
                }
            val o = opener(f = stuck)
            val error = assertThrows { o.awaitOpen() }
            assertIs<DatabaseOpenException>(error)
            assertEquals(DatabaseOpenException.Reason.IO, error.reason)
            assertTrue(factory.quarantineMarker, "the recovery request must survive a failed move")

            // Nothing reopened or pruned over the debris: the retry completes the move.
            stuck.fail = false
            val result = o.awaitOpen()
            assertEquals(RecoveryCause.CORRUPT, result.recovered)
            assertFalse(factory.quarantineMarker)
            o.requireDatabase().close()
        }

    @Test
    fun aPartialQuarantineBlocksTheOpenAndCompletesOnRetry() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()
            val dbFile = dir.resolve(NeutrodyneDatabase.FILE_NAME)
            val walFile = dir.resolve("${NeutrodyneDatabase.FILE_NAME}-wal")
            Files.write(walFile, "stale".encodeToByteArray())
            factory.quarantineMarker = true

            val partial =
                object : DatabaseFactory by factory {
                    var fail = true

                    override fun quarantine(stamp: String) {
                        if (!fail) {
                            factory.quarantine(stamp)
                            return
                        }
                        // The -wal sidecar lands, then the main move fails mid-sequence.
                        val dest = dir.resolve("quarantine").resolve(stamp)
                        Files.createDirectories(dest)
                        Files.move(walFile, dest.resolve(walFile.fileName.toString()))
                        throw IOException("main database move failed")
                    }
                }
            val o = opener(f = partial)
            val error = assertThrows { o.awaitOpen() }
            assertIs<DatabaseOpenException>(error)
            assertTrue(Files.exists(dbFile), "the database stays put after a partial move")
            assertTrue(factory.quarantineMarker)

            partial.fail = false
            val result = o.awaitOpen()
            assertEquals(RecoveryCause.CORRUPT, result.recovered)
            // The retried move landed in the same stamp directory as the earlier sidecar.
            val copies = Files.list(dir.resolve("quarantine")).use { it.toList() }
            assertEquals(1, copies.size)
            assertTrue(Files.exists(copies.single().resolve(NeutrodyneDatabase.FILE_NAME)))
            assertTrue(Files.exists(copies.single().resolve("${NeutrodyneDatabase.FILE_NAME}-wal")))
            o.requireDatabase().close()
        }

    @Test
    fun storageAndOpenFailuresNeverQuarantine() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()
            val dbFile = dir.resolve(NeutrodyneDatabase.FILE_NAME)
            val sizeBefore = Files.size(dbFile)

            // A read-only file passes SQLite's read-only open fallback and preflight, then fails
            // Room's first write with SQLITE_READONLY (8); a database held by a live process
            // reports SQLITE_BUSY (5) — the file is healthy, so neither may quarantine it.
            val cases =
                listOf(
                    "Error code: 8, message: attempt to write a readonly database",
                    "attempt to write a readonly database (code 8 SQLITE_READONLY)",
                    "database is locked (code 5 SQLITE_BUSY)",
                    "unable to open database file (code 3 SQLITE_PERM)",
                )
            for (message in cases) {
                val throwing =
                    object : DatabaseFactory by factory {
                        override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> =
                            throw IllegalStateException(message)
                    }
                val error = assertThrows { opener(f = throwing).awaitOpen() }
                assertIs<DatabaseOpenException>(error)
                assertEquals(DatabaseOpenException.Reason.IO, error.reason, message)
                assertFalse(
                    Files.exists(dir.resolve("quarantine")),
                    "$message is a storage/open failure and must never quarantine",
                )
            }
            assertEquals(sizeBefore, Files.size(dbFile), "the database file is untouched")
        }

    @Test
    fun aFailedMarkerRemovalPropagatesBeforeCreatingTheReplacement() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()
            factory.quarantineMarker = true

            var deleteFails = true
            val stickyMarker =
                object : DatabaseFactory by factory {
                    override var quarantineMarker: Boolean
                        get() = factory.quarantineMarker
                        set(value) {
                            // A locked marker file (Windows fails deletes other processes hold).
                            if (!value && deleteFails) throw IOException("marker is locked")
                            factory.quarantineMarker = value
                        }
                }
            val o = opener(f = stickyMarker)
            val error = assertThrows { o.awaitOpen() }
            assertIs<DatabaseOpenException>(error)

            // The failure propagated before the fresh build: no healthy replacement exists for
            // the still-set marker to quarantine on the next launch, and the pending stamp still
            // names the directory the files already moved into.
            assertTrue(factory.quarantineMarker, "the failed removal left the request in place")
            assertFalse(factory.exists(), "no replacement may be created while the marker persists")
            assertNotNull(factory.pendingQuarantine)

            deleteFails = false
            val result = o.awaitOpen()
            assertEquals(RecoveryCause.CORRUPT, result.recovered)
            assertFalse(factory.quarantineMarker)
            assertNull(factory.pendingQuarantine)
            o.requireDatabase().close()
        }

    @Test
    fun aPartialQuarantineResumesIntoTheSameDirectoryAfterARestart() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()
            val walFile = dir.resolve("${NeutrodyneDatabase.FILE_NAME}-wal")
            Files.write(walFile, "stale".encodeToByteArray())
            factory.quarantineMarker = true

            // The first process dies mid-move: the wal sidecar lands, then the move fails.
            val dying =
                object : DatabaseFactory by factory {
                    override fun quarantine(stamp: String) {
                        val dest = dir.resolve("quarantine").resolve(stamp)
                        Files.createDirectories(dest)
                        Files.move(walFile, dest.resolve(walFile.fileName.toString()))
                        throw IOException("process died mid-move")
                    }
                }
            val first = opener(f = dying, clock = TestClock(nowMs = T0))
            assertThrows { first.awaitOpen() }
            assertEquals(T0.toString(), factory.pendingQuarantine, "the stamp is persisted before the first move")

            // The relaunch has no in-memory stamp and a different wall clock: the persisted
            // pending stamp must route the remaining moves into the same directory.
            val second = opener(clock = TestClock(nowMs = T0 + 60_000))
            val result = second.awaitOpen()
            assertEquals(RecoveryCause.CORRUPT, result.recovered)
            assertNull(factory.pendingQuarantine)

            val copies = Files.list(dir.resolve("quarantine")).use { it.toList() }
            assertEquals(1, copies.size, "the resumed quarantine must reuse the first stamp")
            assertEquals(T0.toString(), copies.single().fileName.toString())
            assertTrue(Files.exists(copies.single().resolve(NeutrodyneDatabase.FILE_NAME)))
            assertTrue(
                Files.exists(copies.single().resolve("${NeutrodyneDatabase.FILE_NAME}-wal")),
                "the moved wal must not be orphaned or pruned",
            )
            second.requireDatabase().close()
        }

    @Test
    fun aResumedQuarantineNeverRewritesItsPersistedStamp() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()
            factory.quarantineMarker = true
            factory.pendingQuarantine = T0.toString()

            // Rewriting truncates the record first: a kill in between would leave it empty and
            // send the next launch to a fresh stamp, orphaning what the first attempt moved.
            val stampWrites = mutableListOf<String?>()
            val recording =
                object : DatabaseFactory by factory {
                    override var pendingQuarantine: String?
                        get() = factory.pendingQuarantine
                        set(value) {
                            stampWrites += value
                            factory.pendingQuarantine = value
                        }
                }
            val subject = opener(f = recording, clock = TestClock(nowMs = T0 + 60_000))
            val result = subject.awaitOpen()

            assertEquals(RecoveryCause.CORRUPT, result.recovered)
            assertEquals(listOf<String?>(null), stampWrites, "only the final clear may write the record")
            subject.requireDatabase().close()
        }

    @Test
    fun aPendingQuarantineWithoutAMarkerStillResumes() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()
            val walFile = dir.resolve("${NeutrodyneDatabase.FILE_NAME}-wal")
            Files.write(walFile, "stale".encodeToByteArray())

            // A non-marker quarantine (a preflight-detected corruption, a migration failure)
            // died mid-move: the pending stamp survives without a recovery marker.
            val stamp = T0.toString()
            val dest = dir.resolve("quarantine").resolve(stamp)
            Files.createDirectories(dest)
            Files.move(walFile, dest.resolve(walFile.fileName.toString()))
            factory.pendingQuarantine = stamp
            assertFalse(factory.quarantineMarker)

            val o = opener(clock = TestClock(nowMs = T0 + 60_000))
            val result = o.awaitOpen()
            assertEquals(RecoveryCause.CORRUPT, result.recovered)
            assertNull(factory.pendingQuarantine)

            val copies = Files.list(dir.resolve("quarantine")).use { it.toList() }
            assertEquals(listOf(stamp), copies.map { it.fileName.toString() })
            assertTrue(Files.exists(dest.resolve(NeutrodyneDatabase.FILE_NAME)))
            assertTrue(Files.exists(dest.resolve("${NeutrodyneDatabase.FILE_NAME}-wal")))
            o.requireDatabase().close()
        }

    @Test
    fun pruneQuarantineKeepsAPendingDestination() =
        runTest {
            dirs.ensureCreated()
            val old = dir.resolve("quarantine").resolve(T0.toString())
            val pendingDir = dir.resolve("quarantine").resolve((T0 + 60_000).toString())
            Files.createDirectories(old)
            Files.createDirectories(pendingDir)
            factory.pendingQuarantine = (T0 + 60_000).toString()

            // Both stamps are far outside the 14-day window: only the pending one survives.
            factory.pruneQuarantine(T0 + 30L * 24 * 60 * 60 * 1000)
            assertFalse(Files.exists(old))
            assertTrue(Files.exists(pendingDir))
            factory.pendingQuarantine = null
        }

    @Test
    fun requireDatabaseReportsAFailureInsteadOfHanging() =
        runTest {
            dirs.ensureCreated()
            var broken = true
            val flaky =
                object : DatabaseFactory by factory {
                    override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> {
                        check(!broken) { "still broken" }
                        return factory.builder()
                    }
                }
            val o = opener(f = flaky)
            val error = assertThrows { o.awaitOpen() }
            assertIs<DatabaseOpenException>(error)
            assertIs<DatabaseOpenState.Failed>(o.openState.value)

            // The Metro provider calls this on a background thread: it must report the recorded
            // failure immediately, not wait for a retry that nobody started.
            val outcome = CompletableDeferred<Result<NeutrodyneDatabase>>()
            thread(isDaemon = true) {
                outcome.complete(runCatching { o.requireDatabase() })
            }
            // Real time: under runTest a bare withTimeoutOrNull runs on virtual time and expires at
            // once while the answer is still on its way from the other thread.
            val delivered = withContext(Dispatchers.Default) { withTimeoutOrNull(5.seconds) { outcome.await() } }
            assertNotNull(delivered, "requireDatabase must not hang after a failed open")
            assertIs<DatabaseOpenException>(delivered.exceptionOrNull())

            // A retry then succeeds and hands out the database.
            broken = false
            assertTrue(o.awaitOpen().created)
            o.requireDatabase().close()
        }

    @Test
    fun requireDatabaseOnTheUiThreadReturnsAnOpenedDatabase() =
        runTest {
            dirs.ensureCreated()
            val o = opener()
            o.awaitOpen()

            // The Swing EDT is the desktop's UI thread: the guard only protects against
            // blocking on an unopened database, so an opened one is still returned (R6).
            val delivered = CompletableDeferred<Result<NeutrodyneDatabase>>()
            SwingUtilities.invokeLater {
                delivered.complete(runCatching { o.requireDatabase() })
            }
            val db = delivered.await().getOrThrow()
            assertSame(o.requireDatabase(), db)
            db.close()
        }

    @Test
    fun requireDatabaseOnTheUiThreadStillThrowsWhileUnopened() =
        runTest {
            val o = opener()
            val delivered = CompletableDeferred<Throwable?>()
            SwingUtilities.invokeLater {
                delivered.complete(runCatching { o.requireDatabase() }.exceptionOrNull())
            }
            assertIs<IllegalStateException>(delivered.await())
        }

    @Test
    fun recoveryCauseSurvivesAFailedFreshOpen() =
        runTest {
            dirs.ensureCreated()
            factory.quarantineMarker = true
            var failBuild = true
            val flaky =
                object : DatabaseFactory by factory {
                    override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> {
                        if (failBuild) {
                            throw IllegalStateException("Error code: 10, message: disk I/O error")
                        }
                        return factory.builder()
                    }
                }
            val o = opener(f = flaky)
            val first = assertThrows { o.awaitOpen() }
            assertIs<DatabaseOpenException>(first)
            assertEquals(DatabaseOpenException.Reason.IO, first.reason)

            failBuild = false
            val result = o.awaitOpen()
            assertTrue(result.created)
            assertEquals(
                RecoveryCause.CORRUPT,
                result.recovered,
                "the recovery cause must survive the failed attempt (R8)",
            )
            o.requireDatabase().close()
        }

    @Test
    fun aRetryPublishesPendingWhileItRuns() =
        runTest {
            dirs.ensureCreated()
            createHealthyDatabase()

            val gated = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val driver =
                object : SQLiteDriver by BundledSQLiteDriver() {
                    private val inner = BundledSQLiteDriver()
                    var throwError = true
                    var gateNext = false

                    override fun open(fileName: String): SQLiteConnection {
                        if (throwError) throw RuntimeException("kaboom")
                        if (gateNext) {
                            gateNext = false
                            gated.complete(Unit)
                            runBlocking { release.await() }
                        }
                        return inner.open(fileName)
                    }
                }
            val o = opener(d = driver)

            val first = assertThrows { o.awaitOpen() }
            assertIs<DatabaseOpenException>(first)
            assertIs<DatabaseOpenState.Failed>(o.openState.value)

            // The retry must republish Pending while it is still running (R9): gate the
            // preflight open and observe the state before releasing it.
            driver.throwError = false
            driver.gateNext = true
            val retry = async { o.awaitOpen() }
            gated.await()
            try {
                assertEquals(DatabaseOpenState.Pending, o.openState.value)
            } finally {
                // Always release — a failing assertion must not strand the blocked driver call.
                release.complete(Unit)
            }

            val result = retry.await()
            assertEquals(OpenResult(created = false, recovered = null), result)
            assertEquals(DatabaseOpenState.Opened(result), o.openState.value)
            o.requireDatabase().close()
        }

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}
