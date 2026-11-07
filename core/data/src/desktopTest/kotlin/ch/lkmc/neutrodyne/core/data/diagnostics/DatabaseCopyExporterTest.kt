// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
