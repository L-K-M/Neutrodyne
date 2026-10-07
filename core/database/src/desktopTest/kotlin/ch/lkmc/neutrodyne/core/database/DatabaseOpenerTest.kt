// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    ) = DatabaseOpener(
        factory = f,
        driver = BundledSQLiteDriver(),
        io = Dispatchers.Default,
        clock = clock,
        strictMigrations = false,
    )

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

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}
