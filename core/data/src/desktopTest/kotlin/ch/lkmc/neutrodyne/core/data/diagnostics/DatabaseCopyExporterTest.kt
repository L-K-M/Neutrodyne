// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
