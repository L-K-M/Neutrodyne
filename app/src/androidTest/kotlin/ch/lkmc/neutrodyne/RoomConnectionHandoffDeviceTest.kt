// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Context
import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * database (writer + reader pools). The database file lives under a test-owned `ContextWrapper`
 * so the app's real `getDatabasePath` target is never opened or deleted. `SeenConnection`
 * counts closes and `openDbFds` counts process descriptors still held under the fixture path.
 * Runs under the app's managed-device groups (`api26DebugAndroidTest`/`api36DebugAndroidTest`,
 * the `run-instrumented` label).
 */
@RunWith(AndroidJUnit4::class)
class RoomConnectionHandoffDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * First-open cancellation on the writer path: the cancelled attempt must leave no live
     * connection behind, a retry must still open the real file, and closing the published
     * database must release every connection and descriptor.
     */
    @Test
    fun aCancelledFirstWriterOpenLeavesNoNativeConnectionBehind() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val fixtureDir = fixtureDir("writer")
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
                val database = buildDb(driver, fixtureDir)
                db = database
                val launched =
                    scope.async {
                        database.useWriterConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                attempt = launched
                assertTrue("driver open must be reached", waitBounded(insideOpen))
                launched.cancel()
                releaseOpen.complete(Unit)
                assertTrue(assertThrows { launched.await() } is CancellationException)

                assertEquals(
                    "a connection discarded at the lock boundary must be closed",
                    0,
                    seen.count { !it.closed.get() },
                )
                assertDbFilesReleased(fixtureDir, "a leaked connection still holds db files open")

                // Retry: an ungated open must succeed and its connection becomes pool-owned.
                database.useWriterConnection {
                    it.usePrepared("SELECT count(*) FROM sync_state") { statement ->
                        statement.step()
                        statement.getLong(0)
                    }
                }
                database.close()
                assertEquals(
                    "closing the database must release every connection it ever created",
                    0,
                    seen.count { !it.closed.get() },
                )
                assertDbFilesReleased(fixtureDir, "no db descriptor may survive db.close()")
            } finally {
                cleanup(attempt, releaseOpen, seen, db, scope, fixtureDir)
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
            val fixtureDir = fixtureDir("reader")
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
                val database = buildDb(driver, fixtureDir)
                db = database
                database.useWriterConnection {
                    it.usePrepared("SELECT 1") { statement -> statement.step() }
                }
                assertEquals("the writer open must be the first connection", 1, seen.size)

                val launched =
                    scope.async {
                        database.useReaderConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                attempt = launched
                assertTrue("driver open must be reached", waitBounded(insideOpen))
                launched.cancel()
                releaseOpen.complete(Unit)
                assertTrue(assertThrows { launched.await() } is CancellationException)

                assertEquals(
                    "only the pooled writer may remain open; a discarded reader must be closed",
                    1,
                    seen.count { !it.closed.get() },
                )
                database.close()
                assertEquals(
                    "closing the database must release every connection it ever created",
                    0,
                    seen.count { !it.closed.get() },
                )
                assertDbFilesReleased(fixtureDir, "no db descriptor may survive db.close()")
            } finally {
                cleanup(attempt, releaseOpen, seen, db, scope, fixtureDir)
            }
        }

    /** Control: the same gated open without cancellation must complete and close cleanly. */
    @Test
    fun anUncancelledOpenRegistersAndClosesItsConnection() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val fixtureDir = fixtureDir("control")
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
                val database = buildDb(driver, fixtureDir)
                db = database
                val launched =
                    scope.async {
                        database.useWriterConnection {
                            it.usePrepared("SELECT 1") { statement -> statement.step() }
                        }
                        "completed"
                    }
                attempt = launched
                assertTrue("driver open must be reached", waitBounded(insideOpen))
                releaseOpen.complete(Unit)
                assertEquals("completed", launched.await())
                database.close()
                assertEquals(0, seen.count { !it.closed.get() })
                assertDbFilesReleased(fixtureDir, "no db descriptor may survive db.close()")
            } finally {
                cleanup(attempt, releaseOpen, seen, db, scope, fixtureDir)
            }
        }

    /**
     * Unblocks a possibly-latched gate, joins the attempt, then releases every unowned handle
     * the assertions just counted — a failed assert must not leave a blocked thread or a live
     * native connection hanging the device run. Only the test-owned fixture dir is deleted.
     */
    private suspend fun cleanup(
        attempt: Deferred<*>?,
        releaseOpen: CompletableDeferred<Unit>,
        seen: List<SeenConnection>,
        db: NeutrodyneDatabase?,
        scope: CoroutineScope,
        fixtureDir: File,
    ) {
        releaseOpen.complete(Unit)
        if (attempt != null) withTimeoutOrNull(BOUND_MS) { attempt.join() }
        db?.let { runCatching { it.close() } }
        seen.filter { !it.closed.get() }.forEach { runCatching { it.close() } }
        scope.cancel()
        fixtureDir.deleteRecursively()
    }

    /** Redirects only `getDatabasePath` into [fixtureDir]; every other call delegates. */
    private fun isolatedContext(fixtureDir: File): Context =
        object : ContextWrapper(context) {
            override fun getDatabasePath(name: String): File = File(fixtureDir, name).also { fixtureDir.mkdirs() }
        }

    private fun fixtureDir(name: String): File = File(context.cacheDir, "room-handoff-$name").apply { mkdirs() }

    private fun buildDb(
        driver: SQLiteDriver,
        fixtureDir: File,
    ): NeutrodyneDatabase =
        NeutrodyneDatabase.build(
            factory = AndroidDatabaseFactory(isolatedContext(fixtureDir)),
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

    /**
     * Asserts no process descriptor still targets the fixture's `neutrodyne.db*` files.
     * `-1` means descriptor inspection is unsupported on this device, so the assertion is
     * skipped — never silently reported as proven.
     */
    private fun assertDbFilesReleased(
        fixtureDir: File,
        message: String,
    ) {
        val fds = openDbFds(fixtureDir)
        if (fds >= 0) assertEquals(message, 0, fds)
    }

    /** Open descriptors targeting `<fixtureDir>/neutrodyne.db*`, or -1 when unsupported. */
    private fun openDbFds(fixtureDir: File): Int {
        val fdDir = File("/proc/self/fd")
        val entries = fdDir.list() ?: return -1
        val prefix = File(fixtureDir, NeutrodyneDatabase.FILE_NAME).path
        var held = 0
        for (fd in entries) {
            val target =
                try {
                    Os.readlink(File(fdDir, fd).path)
                } catch (e: ErrnoException) {
                    // An fd vanishing between list and readlink is a normal close race; a
                    // permission error means inspection is unsupported, not a pass.
                    if (e.errno == OsConstants.ENOENT) continue else return -1
                }
            if (target.startsWith(prefix)) held++
        }
        return held
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
                // Bounded: a test that never releases the gate must not park this thread.
                runBlocking { withTimeoutOrNull(BOUND_MS) { releaseOpen.await() } }
            }
            return SeenConnection(delegate.open(fileName)).also { seen += it }
        }
    }

    /**
     * Records `close()` on the real native connection; the rest delegates unchanged.
     * `closed` is set only when the delegate close completed, so a failed close is never
     * accounted as released.
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
