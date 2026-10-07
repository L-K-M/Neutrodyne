// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.DesktopDatabaseFactory
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabaseCallback
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabaseConstructor
import ch.lkmc.neutrodyne.core.database.migration.ALL_MIGRATIONS
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * Desktop database builders (02 Testing): [inMemory] for everything that does not need a second
 * connection, [file] for file-level behaviour (WAL reader isolation, `DatabaseOpener` recovery).
 * `TestDb.linked(…)` arrives with MS0 (fixed `nodeId` + capture triggers).
 */
object TestDb {
    /** `Room.inMemoryDatabaseBuilder` (name confirmed by S10), bundled driver on host natives. */
    fun inMemory(
        driver: SQLiteDriver = BundledSQLiteDriver(),
        clock: Clock = TestClock(),
    ): NeutrodyneDatabase =
        Room
            .inMemoryDatabaseBuilder<NeutrodyneDatabase>(NeutrodyneDatabaseConstructor::initialize)
            .applyTestSettings(driver, clock)
            .build()

    /**
     * A file database through the production path: `DesktopDatabaseFactory` over [dir] +
     * `NeutrodyneDatabase.build`, so the open callback, migrations and WAL mode are the real ones.
     */
    fun file(
        dir: Path,
        driver: SQLiteDriver = BundledSQLiteDriver(),
        clock: Clock = TestClock(),
    ): NeutrodyneDatabase {
        val dirs = AppDirs(data = dir, config = dir, cache = dir, state = dir, logs = dir, downloadsDefault = dir)
        dirs.ensureCreated()
        val factory = DesktopDatabaseFactory(dirs)
        return NeutrodyneDatabase.build(
            factory = factory,
            driver = driver,
            io = Dispatchers.Default,
            cb = callback(driver, clock),
        )
    }

    private fun RoomDatabase.Builder<NeutrodyneDatabase>.applyTestSettings(
        driver: SQLiteDriver,
        clock: Clock,
    ): RoomDatabase.Builder<NeutrodyneDatabase> =
        setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.Default)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(callback(driver, clock))

    private fun callback(
        driver: SQLiteDriver,
        clock: Clock,
    ) = NeutrodyneDatabaseCallback(clock, optimizeMask = driver is BundledSQLiteDriver)
}
