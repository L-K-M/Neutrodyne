// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.database.migration.ALL_MIGRATIONS
import ch.lkmc.neutrodyne.core.testing.database.MigrationInvariants
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The GMD half of `MigrateAllTest` (02 Testing): the Android `MigrationTestHelper` creates v1
 * from the packaged `assets/<FQN>/1.json` (the frozen export, wired as a symlink under
 * `src/androidDeviceTest/assets/`), `db/v1-fixture.sql` fills every table, the chain runs, and
 * [MigrationInvariants] verifies nothing was lost — on both drivers.
 */
@RunWith(AndroidJUnit4::class)
class MigrateAllDeviceTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val dbFile: File
        get() = File(instrumentation.targetContext.cacheDir, "migrate-all.db")

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = dbFile,
            driver = BundledSQLiteDriver(),
            databaseClass = NeutrodyneDatabase::class,
        )

    @Test
    fun v1FixtureSurvivesOnTheFrameworkDriver() =
        runTest {
            migrate(AndroidSQLiteDriver())
        }

    @Test
    fun v1FixtureSurvivesOnTheBundledDriver() =
        runTest {
            migrate(BundledSQLiteDriver())
        }

    private suspend fun migrate(driver: androidx.sqlite.SQLiteDriver) {
        // The rule's helper is bound to the bundled driver; for the framework run a second
        // helper instance is needed (managed connections close with it).
        val h =
            if (driver is BundledSQLiteDriver) {
                helper
            } else {
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = dbFile,
                    driver = driver,
                    databaseClass = NeutrodyneDatabase::class,
                )
            }
        val before =
            h.createDatabase(1).use { conn ->
                fixtureStatements(instrumentation.context).forEach { conn.exec(it) }
                MigrationInvariants.capture(conn)
            }
        val after =
            h
                .runMigrationsAndValidate(NeutrodyneDatabase.VERSION, ALL_MIGRATIONS.toList())
                .use { conn -> MigrationInvariants.capture(conn) }
        MigrationInvariants.assertPreserved(before, after)
    }
}

internal suspend fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}

/** The same `db/v1-fixture.sql` as `desktopTest`, packaged into the test APK's assets. */
internal fun fixtureStatements(context: android.content.Context): List<String> =
    context.assets.open("db/v1-fixture.sql").bufferedReader().use { reader ->
        reader
            .readText()
            .lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
