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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
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
            try {
                val dir = Files.createTempDirectory("room-handoff-write")
                val insideOpen = CompletableDeferred<Unit>()
                val releaseOpen = CompletableDeferred<Unit>()
                val seen = CopyOnWriteArrayList<SeenConnection>()
                val driver =
                    GatedDriver(
                        BundledSQLiteDriver(),
                        gateOnCall = { it == 1 },
                        insideOpen,
                        releaseOpen,
                        seen,
                    )
                val db = TestDb.file(dir, driver)

                val attempt =
                    scope.async {
                        db.useWriterConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                // Cancel while the driver is gated, then let the native connection be created on
                // the cancelled coroutine.
                assertTrue(
                    withTimeoutOrNull(BOUND_MS) {
                        insideOpen.await()
                        true
                    } != null,
                )
                attempt.cancel()
                releaseOpen.complete(Unit)
                assertIs<CancellationException>(assertThrows { attempt.await() })

                assertEquals(
                    0,
                    seen.count { !it.closed.get() },
                    "a connection discarded at the lock boundary must be closed",
                )
                val fdsAfterCancel = openDbFds(dir)
                if (fdsAfterCancel >= 0) {
                    assertEquals(0, fdsAfterCancel, "a leaked connection still holds db files open")
                }

                // Retry: an ungated open must succeed and its connection becomes pool-owned.
                db.useWriterConnection {
                    it.usePrepared("SELECT count(*) FROM sync_state") { statement ->
                        statement.step()
                        statement.getLong(0)
                    }
                }
                db.close()
                assertEquals(
                    0,
                    seen.count { !it.closed.get() },
                    "closing the database must release every connection it ever created",
                )
                val fdsAfterClose = openDbFds(dir)
                if (fdsAfterClose >= 0) {
                    assertEquals(0, fdsAfterClose, "no db descriptor may survive db.close()")
                }
            } finally {
                scope.cancel()
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
            try {
                val dir = Files.createTempDirectory("room-handoff-read")
                val insideOpen = CompletableDeferred<Unit>()
                val releaseOpen = CompletableDeferred<Unit>()
                val seen = CopyOnWriteArrayList<SeenConnection>()
                val driver =
                    GatedDriver(
                        BundledSQLiteDriver(),
                        gateOnCall = { it == 2 },
                        insideOpen,
                        releaseOpen,
                        seen,
                    )
                val db = TestDb.file(dir, driver)
                db.useWriterConnection {
                    it.usePrepared("SELECT 1") { statement -> statement.step() }
                }
                assertEquals(1, seen.size, "the writer open must be the first connection")

                val attempt =
                    scope.async {
                        db.useReaderConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                assertTrue(
                    withTimeoutOrNull(BOUND_MS) {
                        insideOpen.await()
                        true
                    } != null,
                )
                attempt.cancel()
                releaseOpen.complete(Unit)
                assertIs<CancellationException>(assertThrows { attempt.await() })

                assertEquals(
                    1,
                    seen.count { !it.closed.get() },
                    "only the pooled writer may remain open; a discarded reader must be closed",
                )
                db.close()
                assertEquals(
                    0,
                    seen.count { !it.closed.get() },
                    "closing the database must release every connection it ever created",
                )
                val fdsAfterClose = openDbFds(dir)
                if (fdsAfterClose >= 0) {
                    assertEquals(0, fdsAfterClose, "no db descriptor may survive db.close()")
                }
            } finally {
                scope.cancel()
            }
        }

    /** Control: the same gated open without cancellation must complete and close cleanly. */
    @Test
    fun anUncancelledOpenRegistersAndClosesItsConnection() =
        runBlocking {
            val dir = Files.createTempDirectory("room-handoff-control")
            val insideOpen = CompletableDeferred<Unit>()
            val releaseOpen = CompletableDeferred<Unit>()
            val seen = CopyOnWriteArrayList<SeenConnection>()
            val driver =
                GatedDriver(
                    BundledSQLiteDriver(),
                    gateOnCall = { it == 1 },
                    insideOpen,
                    releaseOpen,
                    seen,
                )
            val db = TestDb.file(dir, driver)
            try {
                val attempt =
                    async {
                        db.useWriterConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                assertTrue(
                    withTimeoutOrNull(BOUND_MS) {
                        insideOpen.await()
                        true
                    } != null,
                )
                releaseOpen.complete(Unit)
                assertEquals("completed", attempt.await())
            } finally {
                db.close()
            }
            assertEquals(0, seen.count { !it.closed.get() })
            val fdsAfterClose = openDbFds(dir)
            if (fdsAfterClose >= 0) {
                assertEquals(0, fdsAfterClose, "no db descriptor may survive db.close()")
            }
        }

    private companion object {
        const val BOUND_MS = 10_000L
    }

    /** Counts open process descriptors whose target is `<dir>/neutrodyne.db*` (-1 when /proc is absent). */
    private fun openDbFds(dir: Path): Int {
        val fdDir = Path.of("/proc/self/fd")
        if (!Files.isDirectory(fdDir)) return -1
        val prefix = dir.resolve(NeutrodyneDatabase.FILE_NAME).toString()
        return Files.list(fdDir).use { stream ->
            stream
                .filter { fd ->
                    runCatching { Files.readSymbolicLink(fd).toString().startsWith(prefix) }
                        .getOrDefault(false)
                }.count()
                .toInt()
        }
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
                runBlocking { releaseOpen.await() }
            }
            return SeenConnection(delegate.open(fileName)).also { seen += it }
        }
    }

    /** Records close() on the real native connection; the rest delegates unchanged. */
    private class SeenConnection(
        private val delegate: SQLiteConnection,
    ) : SQLiteConnection by delegate {
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
            delegate.close()
        }
    }
}
