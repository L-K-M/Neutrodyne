// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabaseCallback
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabaseConstructor
import ch.lkmc.neutrodyne.core.database.migration.ALL_MIGRATIONS
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Android database builders (02 Testing): `AndroidSQLiteDriver` is the default because host
 * (Robolectric) tests cannot load the bundled driver's device `.so`s (spike S4, 2026-10-06);
 * device tests pass `BundledSQLiteDriver()` explicitly.
 */
object TestDb {
    fun inMemory(
        context: Context,
        driver: SQLiteDriver = AndroidSQLiteDriver(),
        clock: Clock = TestClock(),
    ): NeutrodyneDatabase =
        Room
            .inMemoryDatabaseBuilder<NeutrodyneDatabase>(
                context = context.applicationContext,
                factory = NeutrodyneDatabaseConstructor::initialize,
            ).applyTestSettings(driver, clock)
            .build()

    /** A file database at `dir/neutrodyne.db` — used by GMD migration and trigger tests. */
    fun file(
        context: Context,
        dir: File,
        driver: SQLiteDriver = AndroidSQLiteDriver(),
        clock: Clock = TestClock(),
    ): NeutrodyneDatabase {
        dir.mkdirs()
        return Room
            .databaseBuilder<NeutrodyneDatabase>(
                context = context.applicationContext,
                name = File(dir, NeutrodyneDatabase.FILE_NAME).absolutePath,
                factory = NeutrodyneDatabaseConstructor::initialize,
            ).applyTestSettings(driver, clock)
            .build()
    }

    private fun RoomDatabase.Builder<NeutrodyneDatabase>.applyTestSettings(
        driver: SQLiteDriver,
        clock: Clock,
    ): RoomDatabase.Builder<NeutrodyneDatabase> =
        setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.Default)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(NeutrodyneDatabaseCallback(clock, optimizeMask = driver !is AndroidSQLiteDriver))
}
