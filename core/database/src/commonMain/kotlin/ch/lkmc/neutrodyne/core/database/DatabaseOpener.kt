// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** What the first successful open found (02 Error handling and recovery). */
data class OpenResult(
    /** `Callback.onCreate` ran — the only fresh-install signal. */
    val created: Boolean,
    /** Non-null when a broken database was quarantined and a fresh one created. */
    val recovered: RecoveryCause?,
)

/** Why the previous database file was quarantined. */
enum class RecoveryCause { CORRUPT, MIGRATION_FAILED, DOWNGRADE }

/** `awaitOpen` could not produce a usable database; maps to `StartupState.database = Failed`. */
class DatabaseOpenException(
    val reason: Reason,
    cause: Throwable,
) : Exception(cause) {
    enum class Reason { DISK_FULL, IO, UNKNOWN }
}

/**
 * The gate-facing open state (01 Application start-up): the shells map it onto
 * `StartupGateState.Pending` / `Ready` / `Recovered` / `Failed`.
 */
sealed interface DatabaseOpenState {
    data object Pending : DatabaseOpenState

    data class Opened(
        val result: OpenResult,
    ) : DatabaseOpenState

    data class Failed(
        val exception: DatabaseOpenException,
    ) : DatabaseOpenState
}

/**
 * Opens and recovers the one process-scoped database (02 Error handling and recovery). Initializer
 * 100 calls [awaitOpen] on IO; the Metro provider of [NeutrodyneDatabase] calls [requireDatabase],
 * which blocks a background caller until the open finishes and throws on the UI thread.
 *
 * [strictMigrations] is bound by the app graphs (`@StrictMigrations`): Android `debug` builds and
 * desktop development runs rethrow a migration failure instead of quarantining the file, so a
 * broken migration is never hidden during development. A failed open is never cached — the next
 * [awaitOpen] retries (02: "Try again" re-runs it).
 */
@SingleIn(AppScope::class)
@Inject
class DatabaseOpener(
    private val factory: DatabaseFactory,
    private val driver: SQLiteDriver,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
    private val clock: Clock,
    @StrictMigrations private val strictMigrations: Boolean,
) {
    private val mutex = Mutex()

    /** A failed attempt is never cached: the deferred is replaced before the next attempt. */
    @Volatile
    private var openDeferred = CompletableDeferred<NeutrodyneDatabase>()

    @Volatile
    private var result: OpenResult? = null

    private val _openState = MutableStateFlow<DatabaseOpenState>(DatabaseOpenState.Pending)

    /** What the start-up gate renders: `Pending` until the open resolves. */
    val openState: StateFlow<DatabaseOpenState> = _openState

    /**
     * Opens the database, running migrations or recovery; idempotent — the first call does the
     * work, later calls return the stored [OpenResult]. Throws [DatabaseOpenException] when even a
     * fresh database cannot be created.
     */
    suspend fun awaitOpen(): OpenResult =
        withContext(io) {
            mutex.withLock {
                result?.let { return@withLock it }
                try {
                    val outcome = openWithRecovery()
                    result = outcome.result
                    openDeferred.complete(outcome.db)
                    _openState.value = DatabaseOpenState.Opened(outcome.result)
                    outcome.result
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        openDeferred.completeExceptionally(t)
                        openDeferred = CompletableDeferred()
                        throw t
                    }
                    val failure = t as? DatabaseOpenException ?: DatabaseOpenException(classify(t), t)
                    openDeferred.completeExceptionally(failure)
                    openDeferred = CompletableDeferred()
                    _openState.value = DatabaseOpenState.Failed(failure)
                    throw failure
                }
            }
        }

    /**
     * The Metro provider's accessor (02): throws on the UI thread if the database is not open yet;
     * on a background thread it blocks until the open finishes (and throws the open failure).
     */
    fun requireDatabase(): NeutrodyneDatabase {
        check(!isUiThread()) { "NeutrodyneDatabase required on the UI thread before the start-up gate" }
        return runBlocking { openDeferred.await() }
    }

    // --- Open and recovery (02's flow) -------------------------------------------------------------

    private class OpenOutcome(
        val db: NeutrodyneDatabase,
        val result: OpenResult,
    )

    private enum class Preflight { OK, CORRUPT, NEWER }

    /**
     * The recovery chain: quarantine marker → quarantine; existing file → raw-driver preflight
     * (`PRAGMA user_version` — a NOTADB/CORRUPT file or a version above [NeutrodyneDatabase.VERSION]
     * quarantines); open Room → a migration or corruption error quarantines; after a quarantine a
     * fresh database is created and `recovered` reports the cause.
     */
    private suspend fun openWithRecovery(): OpenOutcome {
        var recovered: RecoveryCause? = null
        if (factory.quarantineMarker) {
            recovered = RecoveryCause.CORRUPT
            quarantine()
            runCatching { factory.quarantineMarker = false }
        }
        if (recovered == null && factory.exists()) {
            when (preflightUserVersion()) {
                Preflight.CORRUPT -> {
                    recovered = RecoveryCause.CORRUPT
                    quarantine()
                }

                Preflight.NEWER -> {
                    recovered = RecoveryCause.DOWNGRADE
                    quarantine()
                }

                Preflight.OK -> {
                    var candidate: Candidate? = null
                    try {
                        candidate = build()
                        forceOpen(candidate.db)
                        return finish(candidate.db, candidate.callback.created, null)
                    } catch (t: Throwable) {
                        // Only a migration or corruption error quarantines; a full disk or an IO
                        // error fails the open without touching the file (02: nothing is deleted).
                        if (isDiskFull(t) || isIo(t)) throw DatabaseOpenException(classify(t), t)
                        if (strictMigrations && !isCorruption(t)) {
                            throw DatabaseOpenException(DatabaseOpenException.Reason.UNKNOWN, t)
                        }
                        recovered = if (isCorruption(t)) RecoveryCause.CORRUPT else RecoveryCause.MIGRATION_FAILED
                        closeQuietly(candidate?.db)
                        quarantine()
                    }
                }
            }
        }
        val fresh = build()
        try {
            forceOpen(fresh.db)
        } catch (t: Throwable) {
            closeQuietly(fresh.db)
            throw DatabaseOpenException(classify(t), t)
        }
        return finish(fresh.db, created = fresh.callback.created, recovered = recovered)
    }

    /**
     * `PRAGMA user_version` through the raw driver — Room is not involved, so a NOTADB or CORRUPT
     * file surfaces here as a driver error; a version above ours is a downgrade. An error that is
     * not corruption (full disk, IO) is rethrown: the file may be fine, and recovery never deletes.
     */
    private suspend fun preflightUserVersion(): Preflight =
        try {
            driver.open(factory.databasePath).use { connection ->
                connection.prepare("PRAGMA user_version").use { stmt ->
                    if (!stmt.step()) return@use Preflight.OK
                    val version = stmt.getLong(0)
                    if (version > NeutrodyneDatabase.VERSION) Preflight.NEWER else Preflight.OK
                }
            }
        } catch (t: Throwable) {
            if (!isCorruption(t)) {
                Log.w(TAG, t) { "database preflight failed before Room opened" }
                throw t
            }
            Preflight.CORRUPT
        }

    private class Candidate(
        val db: NeutrodyneDatabase,
        val callback: NeutrodyneDatabaseCallback,
    )

    private fun build(): Candidate {
        val callback = NeutrodyneDatabaseCallback(clock, optimizeMask = driver is BundledSQLiteDriver)
        return Candidate(NeutrodyneDatabase.build(factory, driver, io, callback), callback)
    }

    /**
     * Room opens lazily — a trivial read on the writer forces `onCreate` or the migrations and
     * `onOpen` to run now (the writer pool is the one that initializes the schema).
     */
    private suspend fun forceOpen(db: NeutrodyneDatabase) {
        db.useWriterConnection { transactor ->
            transactor.usePrepared("SELECT 1") { stmt -> stmt.step() }
        }
    }

    private suspend fun finish(
        db: NeutrodyneDatabase,
        created: Boolean,
        recovered: RecoveryCause?,
    ): OpenOutcome {
        if (recovered != null || created) factory.pruneQuarantine(clock.now())
        return OpenOutcome(db, OpenResult(created = created, recovered = recovered))
    }

    private suspend fun quarantine() {
        runCatching { factory.quarantine(clock.now().toString()) }
            .onFailure { Log.e(TAG, it) { "quarantine move failed; deleting is never the fallback" } }
    }

    private fun closeQuietly(db: NeutrodyneDatabase?) {
        db ?: return
        runCatching { db.close() }
    }

    // --- Error classification (02: no structured result-code API — message parsing) -----------------

    private fun classify(t: Throwable): DatabaseOpenException.Reason =
        when {
            isDiskFull(t) -> DatabaseOpenException.Reason.DISK_FULL
            isIo(t) -> DatabaseOpenException.Reason.IO
            else -> DatabaseOpenException.Reason.UNKNOWN
        }

    private fun isCorruption(t: Throwable): Boolean {
        val text = chainText(t)
        return CORRUPT_MARKERS.any { text.contains(it) }
    }

    private fun isDiskFull(t: Throwable): Boolean {
        val text = chainText(t)
        return DISK_FULL_MARKERS.any { text.contains(it) }
    }

    private fun isIo(t: Throwable): Boolean {
        val text = chainText(t)
        return IO_MARKERS.any { text.contains(it) }
    }

    private fun chainText(t: Throwable): String =
        generateSequence(t) { it.cause }
            .joinToString(" ") { "${it::class.simpleName}:${it.message ?: ""}" }

    private companion object {
        const val TAG = "DbOpen"

        /** `SQLITE_CORRUPT` (11) and `SQLITE_NOTADB` (26) spellings across both drivers (S2, 02). */
        val CORRUPT_MARKERS =
            listOf(
                "SQLITE_CORRUPT",
                "SQLITE_NOTADB",
                "not a database",
                "database disk image is malformed",
                "code 11",
                "code 26",
                "DatabaseCorrupt",
            )

        /** `SQLITE_FULL` (13). */
        val DISK_FULL_MARKERS =
            listOf("SQLITE_FULL", "database or disk is full", "code 13", "SQLiteFullException", "ENOSPC")

        /** `SQLITE_CANTOPEN` (14) and `SQLITE_IOERR` (10). */
        val IO_MARKERS =
            listOf("SQLITE_CANTOPEN", "SQLITE_IOERR", "code 14", "code 10", "DiskIOException", "CantOpenDatabase")
    }
}

/**
 * The graphs bind this from `BuildInfo.debug` (Android) and `installKind == DEV` (desktop) —
 * strict mode rethrows migration failures instead of quarantining (02 Error handling and
 * recovery).
 */
@Qualifier
annotation class StrictMigrations
