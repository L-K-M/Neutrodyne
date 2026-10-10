// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Context
import android.system.Os
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.database.AndroidDatabaseFactory
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabaseCallback
import ch.lkmc.neutrodyne.platform.DeviceClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device-side proof for the patched `room3-runtime` AAR (patch 0002): a `SQLiteConnection`
 * created inside `RoomConnectionManager.openLocked`/`Pool.acquire` crosses a cancellable
 * `withReentrantLock` (`withContext`) completion boundary before the pool registers it, so a
 * cancelled coroutine completes the block but the boundary discards its result — a live native
 * connection that `Pool.close()` never sees. The gate sits inside `SQLiteDriver.open`, so the
 * connection is created and fully configured on the already-cancelled coroutine, isolating the
 * handoff as the place ownership was lost.
 *
 * Mirrors `RoomConnectionHandoffTest` (desktop) against the real Android path:
 * `AndroidDatabaseFactory` + `NeutrodyneDatabase.build` + `BundledSQLiteDriver` on a WAL file
 * database (writer + reader pools). `SeenConnection` counts closes and `openDbFds` counts
 * process descriptors still held under the database path. Runs under the app's managed-device
 * groups (`api26DebugAndroidTest`/`api36DebugAndroidTest`, the `run-instrumented` label).
 */
@RunWith(AndroidJUnit4::class)
class RoomConnectionHandoffDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbFile: File
        get() = context.getDatabasePath(NeutrodyneDatabase.FILE_NAME)

    @Before
    fun deleteLeftoverDbFiles() {
        for (suffix in arrayOf("", "-wal", "-shm")) {
            File(dbFile.path + suffix).delete()
        }
    }

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
                val db = buildDb(driver)

                val attempt =
                    scope.async {
                        db.useWriterConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                assertTrue("driver open must be reached", waitBounded(insideOpen))
                attempt.cancel()
                releaseOpen.complete(Unit)
                assertTrue(assertThrows { attempt.await() } is CancellationException)

                assertEquals(
                    "a connection discarded at the lock boundary must be closed",
                    0,
                    seen.count { !it.closed.get() },
                )
                val fdsAfterCancel = openDbFds()
                if (fdsAfterCancel >= 0) {
                    assertEquals(
                        "a leaked connection still holds db files open",
                        0,
                        fdsAfterCancel,
                    )
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
                    "closing the database must release every connection it ever created",
                    0,
                    seen.count { !it.closed.get() },
                )
                val fdsAfterClose = openDbFds()
                if (fdsAfterClose >= 0) {
                    assertEquals("no db descriptor may survive db.close()", 0, fdsAfterClose)
                }
            } finally {
                scope.cancel()
            }
        }

    /**
     * The same window on the readers pool after startup: with the writer connection already
     * pooled, a cancelled read acquisition opens a second connection (the readers factory also
     * applies `PRAGMA query_only = 1`), so the handoff hole is covered beyond first-open.
     */
    @Test
    fun aCancelledReadAcquireLeavesNoNativeConnectionBehind() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            try {
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
                val db = buildDb(driver)
                db.useWriterConnection {
                    it.usePrepared("SELECT 1") { statement -> statement.step() }
                }
                assertEquals("the writer open must be the first connection", 1, seen.size)

                val attempt =
                    scope.async {
                        db.useReaderConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                assertTrue("driver open must be reached", waitBounded(insideOpen))
                attempt.cancel()
                releaseOpen.complete(Unit)
                assertTrue(assertThrows { attempt.await() } is CancellationException)

                assertEquals(
                    "only the pooled writer may remain open; a discarded reader must be closed",
                    1,
                    seen.count { !it.closed.get() },
                )
                db.close()
                assertEquals(
                    "closing the database must release every connection it ever created",
                    0,
                    seen.count { !it.closed.get() },
                )
                val fdsAfterClose = openDbFds()
                if (fdsAfterClose >= 0) {
                    assertEquals("no db descriptor may survive db.close()", 0, fdsAfterClose)
                }
            } finally {
                scope.cancel()
            }
        }

    /** Control: the same gated open without cancellation must complete and close cleanly. */
    @Test
    fun anUncancelledOpenRegistersAndClosesItsConnection() =
        runBlocking {
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
            val db = buildDb(driver)
            try {
                val attempt =
                    async {
                        db.useWriterConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                assertTrue("driver open must be reached", waitBounded(insideOpen))
                releaseOpen.complete(Unit)
                assertEquals("completed", attempt.await())
            } finally {
                db.close()
            }
            assertEquals(0, seen.count { !it.closed.get() })
            val fdsAfterClose = openDbFds()
            if (fdsAfterClose >= 0) {
                assertEquals("no db descriptor may survive db.close()", 0, fdsAfterClose)
            }
        }

    private fun buildDb(driver: SQLiteDriver): NeutrodyneDatabase =
        NeutrodyneDatabase.build(
            factory = AndroidDatabaseFactory(context),
            driver = driver,
            io = Dispatchers.Default,
            cb = NeutrodyneDatabaseCallback(DeviceClock, optimizeMask = true),
        )

    private suspend fun waitBounded(deferred: CompletableDeferred<Unit>): Boolean =
        withTimeoutOrNull(BOUND_MS) {
            deferred.await()
            true
        } == true

    /** `assertThrows` is not suspend-aware; this helper is. */
    private suspend fun assertThrows(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (t: Throwable) {
            return t
        }
        throw AssertionError("expected an exception but none was thrown")
    }

    /** Open process descriptors whose target is the `neutrodyne.db*` files (-1 when /proc is absent). */
    private fun openDbFds(): Int {
        val fdDir = File("/proc/self/fd")
        val entries = fdDir.list() ?: return -1
        val prefix = dbFile.path
        return entries.count { fd ->
            runCatching { Os.readlink(File(fdDir, fd).path).startsWith(prefix) }
                .getOrDefault(false)
        }
    }

    private companion object {
        const val BOUND_MS = 10_000L
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
