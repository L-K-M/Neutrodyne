// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.InvalidationTracker
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.RoomOpenDelegate
import androidx.room3.RoomOpenDelegateMarker
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the Room 3.0.3 connection-pool defect we carry patched under
 * `third_party/room3-maven/`: `Pool.acquireWithTimeout` caught *any* `TimeoutCancellationException`
 * and retried, so a foreign timeout (a caller's own `withTimeoutOrNull`) was mistaken for the
 * pool's 30 s acquisition timeout. The waiter turned into a zombie coroutine that re-logged the
 * pool dump on every retry (~hundreds of MB/s of stderr) and survived holder release and cancel.
 *
 * An in-memory database's single-connection pool is the same code path as the file pool. A
 * counting `System.err` measures the dump flood instead of storing it. Patched runtime: the
 * foreign timeout propagates, the waiter completes, the holder's cancelled transaction rolls
 * back and a fresh writer acquires the sole permit afterwards.
 */
class RoomPoolForeignTimeoutTest {
    @Test
    fun foreignTimeoutOnWaitingWriterCompletesAndPoolStaysHealthy() =
        runBlocking {
            // Detached scope: a coroutine trapped in a retry loop must not hang the test body.
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val opens = AtomicInteger()
            val db =
                Room
                    .inMemoryDatabaseBuilder<PoolProbeDatabase>(factory = ::PoolProbeDatabase)
                    .setDriver(CountingDriver(BundledSQLiteDriver(), opens))
                    .build()
            val stderrBytes = AtomicLong()
            val realErr = System.err
            try {
                db.withWriteTransaction { } // first open: runs onCreate -> createAllTables
                assertEquals(1, opens.get(), "one connection opened")

                val holderEntered = CompletableDeferred<Unit>()
                val holdLatch = CompletableDeferred<Unit>()
                val holder =
                    scope.launch {
                        db.withWriteTransaction {
                            usePrepared("INSERT INTO probe(id) VALUES (1)") { it.step() }
                            holderEntered.complete(Unit)
                            holdLatch.await()
                        }
                    }
                holderEntered.await()
                delay(500) // settle: holder owns the sole permit inside a write transaction

                System.setErr(countingStderr(stderrBytes))

                val waiter =
                    scope.launch {
                        withTimeoutOrNull(300) {
                            db.withWriteTransaction { }
                            "ran"
                        }
                    }
                val joined =
                    withTimeoutOrNull(2_000) {
                        waiter.join()
                        true
                    }
                assertNotNull(joined, "foreign-timeout waiter must complete, not turn zombie")
                assertEquals(
                    0L,
                    stderrBytes.get(),
                    "patched pool must not log an acquisition-timeout dump for a foreign timeout",
                )

                val plainWaiter = scope.launch { db.withWriteTransaction { } }
                delay(200)
                val joinedAfterCancel =
                    withTimeoutOrNull(2_000) {
                        plainWaiter.cancelAndJoin()
                        true
                    }
                assertNotNull(joinedAfterCancel, "plain cancellation of a waiting writer must join")

                holder.cancelAndJoin() // cancelled mid-transaction: rolled back by Room
                assertEquals(0L, countProbes(db), "cancelled transaction must roll back its insert")

                val reacquired =
                    withTimeoutOrNull(5_000) {
                        db.withWriteTransaction { }
                        "acquired"
                    }
                assertEquals("acquired", reacquired, "fresh writer must acquire after holder release")

                val read =
                    withTimeoutOrNull(5_000) {
                        db.withReadTransaction {
                            usePrepared("SELECT count(*) FROM probe") {
                                it.step()
                                it.getLong(0)
                            }
                        }
                    }
                assertEquals(0L, read, "fresh reader must acquire after holder release")
                assertEquals(1, opens.get(), "the pooled connection must be recycled, not leaked")
            } finally {
                System.setErr(realErr)
                scope.cancel()
                db.close()
            }
        }

    /**
     * The pool's own 30 s acquisition timeout must still fire once, log its dump and retry;
     * a holder that outlasts it proves the retry policy survived the patch. A lost
     * acquire-in-flight would deadlock the waiter; a double recycle would open a second
     * connection, so `opens` staying 1 pins exactly-one recycling of the timeout race.
     */
    @Test
    fun ownAcquisitionTimeoutStillRetriesAndRecyclesOnce() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val opens = AtomicInteger()
            val db =
                Room
                    .inMemoryDatabaseBuilder<PoolProbeDatabase>(factory = ::PoolProbeDatabase)
                    .setDriver(CountingDriver(BundledSQLiteDriver(), opens))
                    .build()
            val stderrBytes = AtomicLong()
            val realErr = System.err
            try {
                db.withWriteTransaction { }
                assertEquals(1, opens.get(), "one connection opened")

                val holderEntered = CompletableDeferred<Unit>()
                val holder =
                    scope.launch {
                        db.withWriteTransaction {
                            holderEntered.complete(Unit)
                            delay(32_000) // hold past the pool's fixed 30 s acquisition timeout
                        }
                    }
                holderEntered.await()
                delay(500)

                System.setErr(countingStderr(stderrBytes))
                // No foreign timeout: the pool's own timeout drives the dump-and-retry path.
                val waiter = scope.launch { db.withWriteTransaction { } }
                val joined =
                    withTimeoutOrNull(40_000) {
                        waiter.join()
                        true
                    }
                assertNotNull(joined, "own-timeout waiter must acquire once the holder releases")
                holder.join()
                assertTrue(
                    stderrBytes.get() > 0L,
                    "the pool's own timeout must still invoke the dump-and-retry handler",
                )
                assertEquals(1, opens.get(), "timeout/handoff race must recycle exactly once")
            } finally {
                System.setErr(realErr)
                scope.cancel()
                db.close()
            }
        }

    private suspend fun countProbes(db: RoomDatabase): Long =
        db.withWriteTransaction {
            usePrepared("SELECT count(*) FROM probe") { statement ->
                statement.step()
                statement.getLong(0)
            }
        }

    /** Sole-purpose fixture: a real pooled database without generated code. */
    private class PoolProbeDatabase : RoomDatabase() {
        override fun createOpenDelegate(): RoomOpenDelegateMarker =
            object :
                RoomOpenDelegate(
                    version = 1,
                    identityHash = "pool-probe",
                    legacyIdentityHash = "pool-probe",
                ) {
                override suspend fun onCreate(connection: SQLiteConnection) {
                    // tables are created by createAllTables during open
                }

                override suspend fun onPreMigrate(connection: SQLiteConnection) {
                    // no migrations in this fixture
                }

                override suspend fun onValidateSchema(connection: SQLiteConnection) =
                    ValidationResult(isValid = true, expectedFoundMsg = null)

                override suspend fun onPostMigrate(connection: SQLiteConnection) {
                    // no migrations in this fixture
                }

                override suspend fun onOpen(connection: SQLiteConnection) {
                    // nothing to seed
                }

                override suspend fun createAllTables(connection: SQLiteConnection) {
                    connection
                        .prepare("CREATE TABLE IF NOT EXISTS probe(id INTEGER PRIMARY KEY)")
                        .use { it.step() }
                }

                override suspend fun dropAllTables(connection: SQLiteConnection) {
                    connection.prepare("DROP TABLE IF EXISTS probe").use { it.step() }
                }
            }

        override fun createInvalidationTracker(): InvalidationTracker =
            InvalidationTracker(this, emptyMap(), emptyMap())

        override suspend fun clearAllTables() {
            // never called by these tests
        }
    }

    /** Records how many connections the pool opens; a leaked permit forces extra opens. */
    private class CountingDriver(
        private val delegate: SQLiteDriver,
        private val opens: AtomicInteger,
    ) : SQLiteDriver {
        override val hasConnectionPool: Boolean
            get() = delegate.hasConnectionPool

        override fun open(fileName: String): SQLiteConnection {
            opens.incrementAndGet()
            return delegate.open(fileName)
        }
    }

    private fun countingStderr(bytes: AtomicLong): PrintStream =
        PrintStream(
            object : OutputStream() {
                override fun write(b: Int) {
                    bytes.incrementAndGet()
                }

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    bytes.addAndGet(len.toLong())
                }
            },
            true,
        )
}
