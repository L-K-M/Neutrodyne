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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    /**
     * Holds the last attempt's outcome: a failed or cancelled attempt leaves the deferred
     * completed, and it is replaced only when a retry actually starts under [mutex] — so a
     * background `requireDatabase()` after a failure reports that failure instead of hanging on
     * a fresh deferred that nobody will complete.
     */
    @Volatile
    private var openDeferred = CompletableDeferred<NeutrodyneDatabase>()

    @Volatile
    private var result: OpenResult? = null

    /** The recovery cause, retained across failed attempts until a result is published. */
    private var pendingRecovery: RecoveryCause? = null

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
                if (openDeferred.isCompleted) openDeferred = CompletableDeferred()
                val deferred = openDeferred
                _openState.value = DatabaseOpenState.Pending
                try {
                    val outcome = openWithRecovery()
                    result = outcome.result
                    pendingRecovery = null
                    deferred.complete(outcome.db)
                    _openState.value = DatabaseOpenState.Opened(outcome.result)
                    outcome.result
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        // The cancelled caller still receives its own cancellation; the shared
                        // deferred carries an actionable open failure so later requireDatabase()
                        // callers never see a foreign CancellationException.
                        deferred.completeExceptionally(
                            DatabaseOpenException(DatabaseOpenException.Reason.UNKNOWN, t),
                        )
                        throw t
                    }
                    val failure = t as? DatabaseOpenException ?: DatabaseOpenException(classify(t), t)
                    deferred.completeExceptionally(failure)
                    _openState.value = DatabaseOpenState.Failed(failure)
                    throw failure
                }
            }
        }

    /**
     * The Metro provider's accessor (02): throws on the UI thread if the database is not open yet;
     * on a background thread it blocks until the open finishes (and throws the open failure).
     */
    @OptIn(ExperimentalCoroutinesApi::class) // getCompleted — the guarded await stays the fallback
    fun requireDatabase(): NeutrodyneDatabase {
        val deferred = openDeferred
        // An already-resolved attempt answers first — the UI-thread guard exists only to stop a
        // UI caller from blocking on an open that has not finished, not to deny a ready database.
        if (deferred.isCompleted) return deferred.getCompleted()
        check(!isUiThread()) { "NeutrodyneDatabase required on the UI thread before the start-up gate" }
        return runBlocking { deferred.await() }
    }

    // --- Open and recovery (02's flow) -------------------------------------------------------------

    private class OpenOutcome(
        val db: NeutrodyneDatabase,
        val result: OpenResult,
    )

    private enum class Preflight { OK, CORRUPT, NEWER }

    /**
     * The recovery chain: a pending quarantine stamp or quarantine marker → quarantine; existing
     * file → raw-driver preflight (`PRAGMA user_version` — a NOTADB/CORRUPT file or a version
     * above [NeutrodyneDatabase.VERSION] quarantines); open Room → a migration or corruption
     * error quarantines; after a quarantine a fresh database is created and `recovered` reports
     * the cause. A failed or partial quarantine move propagates: the marker stays set and
     * nothing opens over the unmoved file.
     */
    private suspend fun openWithRecovery(): OpenOutcome {
        if (factory.quarantineMarker || factory.pendingQuarantine != null) {
            // The cause is recorded only once the move completed: `recovered` is published only
            // when the old file really was quarantined, and it survives a failed fresh open. A
            // resumed pending stamp without a marker still reports CORRUPT — the original cause
            // is lost with the process that died mid-move.
            quarantine()
            pendingRecovery = RecoveryCause.CORRUPT
            // Both removals are verified and propagate — marker first so that while either
            // record survives, the next attempt resumes into the same quarantine directory.
            // Never let a launch create a replacement database under a stale marker: it would
            // quarantine the healthy new library, or scatter the files across a second stamp.
            factory.quarantineMarker = false
            factory.pendingQuarantine = null
        }
        if (factory.exists()) {
            when (preflightUserVersion()) {
                Preflight.CORRUPT -> {
                    quarantine()
                    factory.pendingQuarantine = null
                    pendingRecovery = RecoveryCause.CORRUPT
                }

                Preflight.NEWER -> {
                    quarantine()
                    factory.pendingQuarantine = null
                    pendingRecovery = RecoveryCause.DOWNGRADE
                }

                Preflight.OK -> {
                    var candidate: Candidate? = null
                    try {
                        candidate = build()
                        forceOpen(candidate.db)
                        return finish(candidate.db, candidate.callback.created)
                    } catch (t: Throwable) {
                        // The failed candidate never becomes the returned database: close it on
                        // every path. Cancellation stays cancellation — it is never classified.
                        closeQuietly(candidate?.db)
                        if (t is CancellationException) throw t
                        // finish() reports post-open maintenance failures itself: they propagate
                        // as open failures, never as a migration or corruption signal.
                        if (t is DatabaseOpenException) throw t
                        // Only a migration or corruption error quarantines; a full disk, an IO
                        // error or another storage/open failure (permissions, a read-only file,
                        // a lock held by a live process) fails the open without touching the
                        // file (02: nothing is deleted). SQLITE_READONLY reaches Room here:
                        // the raw driver preflight reads it fine through the read-only fallback.
                        // Room reports a missing migration path or a failed schema validation as
                        // plain IllegalStateException text; anything else that reaches here — a
                        // callback bug, Room internals, an OOME — is unknown, never a license
                        // to move a file that may be healthy.
                        if (isDiskFull(t) || isStorageFailure(t)) {
                            throw DatabaseOpenException(classify(t), t)
                        }
                        val corrupt = isCorruption(t)
                        if (!corrupt && (strictMigrations || !isMigrationFailure(t))) {
                            throw DatabaseOpenException(DatabaseOpenException.Reason.UNKNOWN, t)
                        }
                        quarantine()
                        factory.pendingQuarantine = null
                        pendingRecovery =
                            if (corrupt) RecoveryCause.CORRUPT else RecoveryCause.MIGRATION_FAILED
                    }
                }
            }
        }
        val fresh = build()
        try {
            forceOpen(fresh.db)
            // finish() owns the candidate until every post-open step succeeded: a failure here
            // still closes the unpublished database instead of leaking its connections.
            return finish(fresh.db, created = fresh.callback.created)
        } catch (t: Throwable) {
            closeQuietly(fresh.db)
            if (t is CancellationException) throw t
            if (t is DatabaseOpenException) throw t
            throw DatabaseOpenException(classify(t), t)
        }
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
    ): OpenOutcome {
        // pruneQuarantine runs only after every move succeeded — a propagated quarantine failure
        // never reaches here (02: quarantine keeps only the newest copy, at most 14 days). It is
        // post-open maintenance, not migration recovery: a failed prune is an environment
        // failure that propagates as the open's failure — it must never quarantine the healthy
        // database nor delete the preserved original through a misclassified retry.
        try {
            if (pendingRecovery != null || created) factory.pruneQuarantine(clock.now())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val reason = classify(t)
            throw DatabaseOpenException(
                if (reason == DatabaseOpenException.Reason.UNKNOWN) {
                    DatabaseOpenException.Reason.IO
                } else {
                    reason
                },
                t,
            )
        }
        return OpenOutcome(db, OpenResult(created = created, recovered = pendingRecovery))
    }

    // The stamp is persisted in the `quarantine-pending` file before the first move: a failed
    // move propagates (nothing opens over the unmoved file) and a crashed or killed attempt
    // resumes into the same directory on the next launch instead of scattering the files across
    // a fresh stamp — an orphaned sidecar is a piece of the library prune must not destroy.
    // The caller clears the record once the recovery request itself is resolved. An existing
    // record is reused, never rewritten: a rewrite truncates first, and a kill in between would
    // leave it empty and send the next launch to a fresh stamp.
    private fun quarantine() {
        val stamp = factory.pendingQuarantine ?: clock.now().toString().also { factory.pendingQuarantine = it }
        factory.quarantine(stamp)
    }

    private fun closeQuietly(db: NeutrodyneDatabase?) {
        db ?: return
        runCatching { db.close() }
    }

    // --- Error classification (02: no structured result-code API — message parsing) -----------------

    private fun classify(t: Throwable): DatabaseOpenException.Reason =
        when {
            isDiskFull(t) -> DatabaseOpenException.Reason.DISK_FULL
            isStorageFailure(t) -> DatabaseOpenException.Reason.IO
            else -> DatabaseOpenException.Reason.UNKNOWN
        }

    private fun isCorruption(t: Throwable): Boolean {
        val text = chainText(t)
        return sqlitePrimaryCodes(text).any { it in CORRUPT_CODES } ||
            CORRUPT_MARKERS.any { text.contains(it) }
    }

    private fun isMigrationFailure(t: Throwable): Boolean =
        chainEntries(t).any { entry ->
            if (entry !is IllegalStateException) return@any false
            val message = entry.message ?: return@any false
            MIGRATION_FAILURE_PREFIXES.any { message.startsWith(it) } ||
                (message.startsWith(MISSING_MIGRATION_PREFIX) && MISSING_MIGRATION_MARKER in message)
        }

    private fun isDiskFull(t: Throwable): Boolean {
        val text = chainText(t)
        return sqlitePrimaryCodes(text).any { it in DISK_FULL_CODES } ||
            DISK_FULL_MARKERS.any { text.contains(it) }
    }

    private fun isStorageFailure(t: Throwable): Boolean {
        val text = chainText(t)
        return sqlitePrimaryCodes(text).any { it in STORAGE_CODES } ||
            STORAGE_MARKERS.any { text.contains(it) }
    }

    /**
     * Every `code N` the exception chain carries, reduced to its primary SQLite result code (the
     * low byte — e.g. `SQLITE_IOERR_SHMOPEN` 4618 = 0x120A → 10). `androidx.sqlite.SQLiteException`
     * has no structured result-code API (02, S2): the bundled driver writes "Error code: N,
     * message: …" and the framework driver "… (code N SQLITE_…)".
     */
    private fun sqlitePrimaryCodes(text: String): List<Int> =
        SQLITE_CODE.findAll(text).map { it.groupValues[1].toInt() and PRIMARY_CODE_MASK }.toList()

    /**
     * The complete cause chain of [t]. The walk stops only on a repeated throwable identity: a
     * cyclic chain (a.initCause(b); b.initCause(a)) would otherwise classify forever while
     * holding the opener mutex. Depth is deliberately not truncated — a real driver failure can
     * nest deeper than any fixed cap, and cutting the chain would hide the root cause.
     */
    private fun chainEntries(t: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = t
        while (current != null && seen.none { it === current }) {
            seen += current
            current = current.cause
        }
        return seen
    }

    private fun chainText(t: Throwable): String =
        chainEntries(t).joinToString(" ") { "${it::class.simpleName}:${it.message ?: ""}" }

    private companion object {
        const val TAG = "DbOpen"

        /** Bundled "Error code: N" and framework "(code N …)" spellings. */
        val SQLITE_CODE = Regex("""\bcode:?\s*(\d+)""")

        const val PRIMARY_CODE_MASK = 0xFF

        /**
         * Storage/open failures: the environment refuses a file that may be perfectly healthy —
         * permissions `SQLITE_PERM` (3), a lock held by a live process `SQLITE_BUSY` (5) or
         * `SQLITE_LOCKED` (6), out of memory `SQLITE_NOMEM` (7), a read-only file
         * `SQLITE_READONLY` (8), IO errors `SQLITE_IOERR` (10) and `SQLITE_CANTOPEN` (14), no
         * large-file support `SQLITE_NOLFS` (22). None may quarantine (2026-10-07).
         */
        val STORAGE_CODES = setOf(3, 5, 6, 7, 8, 10, 14, 22)

        /** `SQLITE_FULL` (13); `SQLITE_CORRUPT` (11), `SQLITE_NOTADB` (26). */
        val DISK_FULL_CODES = setOf(13)
        val CORRUPT_CODES = setOf(11, 26)

        /** `SQLITE_CORRUPT` (11) and `SQLITE_NOTADB` (26) spellings across both drivers (S2, 02). */
        val CORRUPT_MARKERS =
            listOf(
                "SQLITE_CORRUPT",
                "SQLITE_NOTADB",
                "not a database",
                "database disk image is malformed",
                "DatabaseCorrupt",
            )

        /** `SQLITE_FULL` (13). */
        val DISK_FULL_MARKERS =
            listOf("SQLITE_FULL", "database or disk is full", "SQLiteFullException", "ENOSPC")

        /**
         * The exact message families `RoomConnectionManager` throws via `error(...)` for its own
         * migration and schema-validation failures (S2: `IllegalStateException` text, no
         * result-code API): a migration that left the schema invalid, a pre-packaged file whose
         * schema does not match, and an identity hash that cannot be verified. A look-alike
         * message thrown by app or callback code is not Room's failure and must not authorize
         * quarantine of a healthy file.
         */
        val MIGRATION_FAILURE_PREFIXES =
            listOf(
                "Migration didn't properly handle:",
                "Pre-packaged database has an invalid schema:",
                "Room cannot verify the data integrity.",
            )

        /**
         * `"A migration from $oldVersion to $newVersion was required but not found. …"` — the
         * version numbers vary, so the family is the fixed prefix plus the marker clause.
         */
        const val MISSING_MIGRATION_PREFIX = "A migration from "
        const val MISSING_MIGRATION_MARKER = " was required but not found"

        /** The [STORAGE_CODES] spellings across both drivers and Android's exception class names. */
        val STORAGE_MARKERS =
            listOf(
                "SQLITE_PERM",
                "SQLITE_BUSY",
                "SQLITE_LOCKED",
                "SQLITE_NOMEM",
                "SQLITE_READONLY",
                "SQLITE_CANTOPEN",
                "SQLITE_IOERR",
                "SQLITE_NOLFS",
                "SQLiteReadOnlyDatabaseException",
                "SQLiteDatabaseLockedException",
                "readonly database",
                "database is locked",
                "DiskIOException",
                "CantOpenDatabase",
                "IOException",
            )
    }
}

/**
 * The graphs bind this from `BuildInfo.debug` (Android) and `installKind == DEV` (desktop) —
 * strict mode rethrows migration failures instead of quarantining (02 Error handling and
 * recovery).
 */
@Qualifier
annotation class StrictMigrations
