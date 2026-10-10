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
import java.nio.file.Path
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
 *
 * The app's own startup may still create `databases/neutrodyne.db*` during a test —
 * `NeutrodyneApplication` launches its initializers on a fresh process, and the
 * `clearPackageData` instrumentation flag wipes app data before each test. That independent
 * write is not fixture misrouting, so isolation is proven by recording every target the
 * driver was asked to open, never by requiring the app's database dir to stay untouched.
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
                        fixtureDir.absoluteFile.toPath().normalize(),
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
                assertTrue(
                    "the database file must actually live inside the test fixture",
                    File(fixtureDir, NeutrodyneDatabase.FILE_NAME).exists(),
                )
                assertOpensStayedInFixture(driver, fixtureDir)
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
                        fixtureDir.absoluteFile.toPath().normalize(),
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
                assertTrue(
                    "the database file must actually live inside the test fixture",
                    File(fixtureDir, NeutrodyneDatabase.FILE_NAME).exists(),
                )
                assertOpensStayedInFixture(driver, fixtureDir)
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
                        fixtureDir.absoluteFile.toPath().normalize(),
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
                assertTrue(
                    "the database file must actually live inside the test fixture",
                    File(fixtureDir, NeutrodyneDatabase.FILE_NAME).exists(),
                )
                assertOpensStayedInFixture(driver, fixtureDir)
            } finally {
                cleanup(attempt, releaseOpen, seen, db, scope, fixtureDir)
            }
        }

    /**
     * Fixture routing proof: `AndroidDatabaseFactory` resolves through `context.applicationContext`,
     * which `ContextWrapper` delegates to the base context — before the wrapper retained itself
     * there, this resolved the app's real `databases/` path and every test opened and measured
     * the wrong file. This assertion is what failed on the pre-fix wrapper.
     */
    @Test
    fun theFixtureOwnsTheDatabasePath() {
        val fixtureDir = fixtureDir("isolation")
        try {
            val factory = AndroidDatabaseFactory(isolatedContext(fixtureDir))
            assertEquals(
                "the factory must resolve the test-owned fixture, not the app database dir",
                File(fixtureDir, NeutrodyneDatabase.FILE_NAME).absolutePath,
                factory.databasePath,
            )
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    /**
     * Guard control: a path outside the fixture must fail before the delegate ever sees it —
     * a misrouted open can never reach the app's real database file.
     */
    @Test
    fun anOutOfFixtureOpenIsRejectedBeforeDelegating() {
        val fixtureDir = fixtureDir("misroute")
        try {
            val spy = SpyDriver()
            val driver =
                GatedDriver(
                    spy,
                    fixtureDir.absoluteFile.toPath().normalize(),
                    gateOnCall = { false },
                    CompletableDeferred(),
                    CompletableDeferred(),
                    CopyOnWriteArrayList(),
                )
            val appDbPath = context.getDatabasePath(NeutrodyneDatabase.FILE_NAME).absolutePath
            val thrown = runCatching { driver.open(appDbPath) }.exceptionOrNull()
            assertTrue(
                "an out-of-fixture open must be rejected, got: $thrown",
                thrown is IllegalArgumentException,
            )
            assertEquals("the rejected path must never reach the delegate", 0, spy.openCalls.get())
            assertEquals("the rejected target is still recorded", listOf(appDbPath), driver.openedPaths)
        } finally {
            fixtureDir.deleteRecursively()
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

    /**
     * Redirects the database path into [fixtureDir]; every other call delegates.
     * `AndroidDatabaseFactory` resolves every path through `context.applicationContext`, so the
     * wrapper must retain itself there or this override is bypassed and the test writes into
     * the app's real `databases/` directory. `getDatabasePath` accepts only the bare file name
     * (mapped into the fixture) or an absolute path already inside the fixture — Room hands the
     * resolved absolute name back through this method (`resolveFileName`); anything else is an
     * escape or a `File(parent, absolute)` double-map and must fail instead of relocating.
     */
    private fun isolatedContext(fixtureDir: File): Context =
        object : ContextWrapper(context) {
            private val fixtureRoot = fixtureDir.absoluteFile.toPath().normalize()

            override fun getApplicationContext(): Context = this

            override fun getDatabasePath(name: String): File {
                val candidate = File(name)
                if (candidate.isAbsolute) {
                    require(candidate.toPath().normalize().startsWith(fixtureRoot)) {
                        "database path escaped the test fixture: $name"
                    }
                    return candidate
                }
                require(name == NeutrodyneDatabase.FILE_NAME) {
                    "the test fixture only owns the Neutrodyne database name: $name"
                }
                return File(fixtureDir, name).also { fixtureDir.mkdirs() }
            }
        }

    private fun fixtureDir(name: String): File = File(context.cacheDir, "room-handoff-$name").apply { mkdirs() }

    /**
     * Every target the driver was asked to open must be the fixture database — nonempty, and
     * each normalized path equal to `<fixtureDir>/neutrodyne.db`. This replaces watching the
     * app's `databases/` dir: under `clearPackageData` the app legitimately creates its own
     * database there during the test, which proved nothing about routing either way.
     */
    private fun assertOpensStayedInFixture(
        driver: GatedDriver,
        fixtureDir: File,
    ) {
        val fixtureDb = File(fixtureDir, NeutrodyneDatabase.FILE_NAME).absoluteFile.toPath().normalize()
        assertTrue("the driver must have received at least one open", driver.openedPaths.isNotEmpty())
        driver.openedPaths.forEach { opened ->
            assertEquals(
                "every open the driver received must target the fixture database",
                fixtureDb,
                File(opened).toPath().normalize(),
            )
        }
    }

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
        private val fixtureRoot: Path,
        private val gateOnCall: (Int) -> Boolean,
        private val insideOpen: CompletableDeferred<Unit>,
        private val releaseOpen: CompletableDeferred<Unit>,
        private val seen: MutableList<SeenConnection>,
    ) : SQLiteDriver {
        private val calls = AtomicInteger()

        /** Every `fileName` passed to [open], including rejected ones, in order of receipt. */
        val openedPaths = CopyOnWriteArrayList<String>()

        override val hasConnectionPool: Boolean
            get() = delegate.hasConnectionPool

        override fun open(fileName: String): SQLiteConnection {
            openedPaths += fileName
            // Gate before any native open: the only legal target is inside the test fixture —
            // anything else means fixture isolation failed and the app's real database would
            // be the one opened.
            require(File(fileName).toPath().normalize().startsWith(fixtureRoot)) {
                "database open escaped the test fixture: $fileName"
            }
            if (gateOnCall(calls.incrementAndGet())) {
                insideOpen.complete(Unit)
                // Bounded: a test that never releases the gate must not park this thread.
                runBlocking { withTimeoutOrNull(BOUND_MS) { releaseOpen.await() } }
            }
            return SeenConnection(delegate.open(fileName)).also { seen += it }
        }
    }

    /** Delegate that proves the path gate rejects before any native open is attempted. */
    private class SpyDriver : SQLiteDriver {
        val openCalls = AtomicInteger()

        override val hasConnectionPool: Boolean
            get() = false

        override fun open(fileName: String): SQLiteConnection {
            openCalls.incrementAndGet()
            throw AssertionError("the spy delegate must never be invoked")
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
