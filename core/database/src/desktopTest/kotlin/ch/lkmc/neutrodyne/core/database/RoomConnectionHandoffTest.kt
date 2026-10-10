// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression coverage for the vendored Room 3.0.3 connection-handoff defect: a raw
 * `SQLiteConnection` is created inside `RoomConnectionManager.openLocked` and in
 * `Pool.acquire`, then handed back across `withReentrantLock`'s `withContext` completion
 * boundary. A cancelled coroutine still runs the block to completion but discards its result,
 * so the connection lands nowhere: it is never registered in `Pool.connections`, `Pool.close()`
 * never sees it, and nothing else closes it. The probe showed one still-usable native
 * connection holding `neutrodyne.db`/`-wal`/`-shm` descriptors after the cancelled opener was
 * joined, surviving a successful retry and the published database's `close()`.
 *
 * The gate sits inside `SQLiteDriver.open` so cancellation lands after the driver was entered
 * but before the native connection exists; the connection is then created and fully configured
 * on the already-cancelled coroutine, proving the handoff — not creation or configuration — is
 * where ownership was lost. `SeenConnection` counts closes and `openDbFds` counts the process
 * file descriptors still held under the database directory.
 */
class RoomConnectionHandoffTest {
    /**
     * First-open cancellation on the writer path: the cancelled attempt must leave no live
     * connection behind, a retry must still open the real file, and closing the published
     * database must release every connection and descriptor.
     */
    @Test
    fun aCancelledFirstWriterOpenLeavesNoNativeConnectionBehind() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val dir = Files.createTempDirectory("room-handoff-writer")
            val insideOpen = CompletableDeferred<Unit>()
            val releaseOpen = CompletableDeferred<Unit>()
            val seen = CopyOnWriteArrayList<SeenConnection>()
            var db: NeutrodyneDatabase? = null
            var attempt: Deferred<*>? = null
            try {
                val driver =
                    GatedDriver(
                        BundledSQLiteDriver(),
                        gateOnCall = { it == 1 },
                        insideOpen,
                        releaseOpen,
                        seen,
                    )
                val database = TestDb.file(dir, driver)
                db = database
                val launched =
                    scope.async {
                        database.useWriterConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                attempt = launched
                // Cancel while the driver is gated, then let the native connection be created on
                // the cancelled coroutine.
                assertTrue(
                    withTimeoutOrNull(BOUND_MS) {
                        insideOpen.await()
                        true
                    } != null,
                )
                launched.cancel()
                releaseOpen.complete(Unit)
                assertIs<CancellationException>(assertThrows { launched.await() })

                assertEquals(
                    0,
                    seen.count { !it.closed.get() },
                    "a connection discarded at the lock boundary must be closed",
                )
                assertDbFilesReleased(dir, "a leaked connection still holds db files open")

                // Retry: an ungated open must succeed and its connection becomes pool-owned.
                database.useWriterConnection {
                    it.usePrepared("SELECT count(*) FROM sync_state") { statement ->
                        statement.step()
                        statement.getLong(0)
                    }
                }
                database.close()
                assertEquals(
                    0,
                    seen.count { !it.closed.get() },
                    "closing the database must release every connection it ever created",
                )
                assertDbFilesReleased(dir, "no db descriptor may survive db.close()")
            } finally {
                cleanup(attempt, releaseOpen, seen, db, scope, dir)
            }
        }

    /**
     * The same window on the readers pool after startup: with the writer connection already
     * pooled, a cancelled read acquisition opens a *second* connection (the readers factory also
     * applies `PRAGMA query_only = 1`), so the handoff hole is covered beyond first-open.
     */
    @Test
    fun aCancelledReadAcquireLeavesNoNativeConnectionBehind() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val dir = Files.createTempDirectory("room-handoff-reader")
            val insideOpen = CompletableDeferred<Unit>()
            val releaseOpen = CompletableDeferred<Unit>()
            val seen = CopyOnWriteArrayList<SeenConnection>()
            var db: NeutrodyneDatabase? = null
            var attempt: Deferred<*>? = null
            try {
                val driver =
                    GatedDriver(
                        BundledSQLiteDriver(),
                        gateOnCall = { it == 2 },
                        insideOpen,
                        releaseOpen,
                        seen,
                    )
                val database = TestDb.file(dir, driver)
                db = database
                database.useWriterConnection {
                    it.usePrepared("SELECT 1") { statement -> statement.step() }
                }
                assertEquals(1, seen.size, "the writer open must be the first connection")

                val launched =
                    scope.async {
                        database.useReaderConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                attempt = launched
                assertTrue(
                    withTimeoutOrNull(BOUND_MS) {
                        insideOpen.await()
                        true
                    } != null,
                )
                launched.cancel()
                releaseOpen.complete(Unit)
                assertIs<CancellationException>(assertThrows { launched.await() })

                assertEquals(
                    1,
                    seen.count { !it.closed.get() },
                    "only the pooled writer may remain open; a discarded reader must be closed",
                )
                database.close()
                assertEquals(
                    0,
                    seen.count { !it.closed.get() },
                    "closing the database must release every connection it ever created",
                )
                assertDbFilesReleased(dir, "no db descriptor may survive db.close()")
            } finally {
                cleanup(attempt, releaseOpen, seen, db, scope, dir)
            }
        }

    /** Control: the same gated open without cancellation must complete and close cleanly. */
    @Test
    fun anUncancelledOpenRegistersAndClosesItsConnection() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val dir = Files.createTempDirectory("room-handoff-control")
            val insideOpen = CompletableDeferred<Unit>()
            val releaseOpen = CompletableDeferred<Unit>()
            val seen = CopyOnWriteArrayList<SeenConnection>()
            var db: NeutrodyneDatabase? = null
            var attempt: Deferred<*>? = null
            try {
                val driver =
                    GatedDriver(
                        BundledSQLiteDriver(),
                        gateOnCall = { it == 1 },
                        insideOpen,
                        releaseOpen,
                        seen,
                    )
                val database = TestDb.file(dir, driver)
                db = database
                val launched =
                    async {
                        database.useWriterConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                attempt = launched
                assertTrue(
                    withTimeoutOrNull(BOUND_MS) {
                        insideOpen.await()
                        true
                    } != null,
                )
                releaseOpen.complete(Unit)
                assertEquals("completed", launched.await())
                database.close()
                assertEquals(0, seen.count { !it.closed.get() })
                assertDbFilesReleased(dir, "no db descriptor may survive db.close()")
            } finally {
                cleanup(attempt, releaseOpen, seen, db, scope, dir)
            }
        }

    /**
     * Unblocks a possibly-latched gate, joins the attempt, then releases every unowned handle
     * the assertions just counted — a failed assert must not leave a blocked driver thread or a
     * live native connection behind. Only the test-owned directory is deleted.
     */
    private suspend fun cleanup(
        attempt: Deferred<*>?,
        releaseOpen: CompletableDeferred<Unit>,
        seen: List<SeenConnection>,
        db: NeutrodyneDatabase?,
        scope: CoroutineScope,
        dir: Path,
    ) {
        releaseOpen.complete(Unit)
        if (attempt != null) withTimeoutOrNull(BOUND_MS) { attempt.join() }
        db?.let { runCatching { it.close() } }
        seen.filter { !it.closed.get() }.forEach { runCatching { it.close() } }
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private companion object {
        const val BOUND_MS = 10_000L
    }

    /**
     * Asserts no process descriptor still targets `<dir>/neutrodyne.db*`. `-1` means descriptor
     * inspection is unsupported here, so the assertion is skipped — never silently reported as
     * proven.
     */
    private fun assertDbFilesReleased(
        dir: Path,
        message: String,
    ) {
        val fds = openDbFds(dir)
        if (fds >= 0) assertEquals(0, fds, message)
    }

    /** Open descriptors targeting `<dir>/neutrodyne.db*`, or -1 when unsupported. */
    private fun openDbFds(dir: Path): Int {
        val fdDir = Path.of("/proc/self/fd")
        if (!Files.isDirectory(fdDir)) return -1
        val prefix = dir.resolve(NeutrodyneDatabase.FILE_NAME).toString()
        var held = 0
        Files.list(fdDir).use { stream ->
            for (fd in stream) {
                val target =
                    try {
                        Files.readSymbolicLink(fd).toString()
                    } catch (e: NoSuchFileException) {
                        // An fd vanishing between list and readlink is a normal close race.
                        continue
                    } catch (e: IOException) {
                        // Permission or read failure: inspection unsupported, not a pass.
                        return -1
                    } catch (e: SecurityException) {
                        return -1
                    }
                if (target.startsWith(prefix)) held++
            }
        }
        return held
    }

    /** Driver that blocks [open] on [gateOnCall] while recording every produced connection. */
    private class GatedDriver(
        private val delegate: SQLiteDriver,
        private val gateOnCall: (Int) -> Boolean,
        private val insideOpen: CompletableDeferred<Unit>,
        private val releaseOpen: CompletableDeferred<Unit>,
        private val seen: MutableList<SeenConnection>,
    ) : SQLiteDriver {
        private val calls = AtomicInteger()
        override val hasConnectionPool: Boolean
            get() = delegate.hasConnectionPool

        override fun open(fileName: String): SQLiteConnection {
            if (gateOnCall(calls.incrementAndGet())) {
                insideOpen.complete(Unit)
                // Bounded: a test that never releases the gate must not park this thread.
                runBlocking { withTimeoutOrNull(BOUND_MS) { releaseOpen.await() } }
            }
            return SeenConnection(delegate.open(fileName)).also { seen += it }
        }
    }

    /**
     * Records `close()` on the real native connection; the rest delegates unchanged. `closed`
     * is set only when the delegate close completed, so a failed close is never accounted as
     * released.
     */
    private class SeenConnection(
        private val delegate: SQLiteConnection,
    ) : SQLiteConnection by delegate {
        val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close()
                } catch (t: Throwable) {
                    closed.set(false)
                    throw t
                }
            }
        }
    }
}
