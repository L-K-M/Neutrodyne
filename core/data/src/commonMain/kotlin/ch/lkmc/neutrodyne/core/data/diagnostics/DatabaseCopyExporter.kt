// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.exporting.exportDirOf
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Produces the diagnostics database copy of 09 (D33): `VACUUM INTO` a private staging
 * file under `<cache>/export/`, scrubbed by [DiagExportScrub] on a raw driver
 * connection, `VACUUM`ed again so deleted bytes leave the file, then moved to a unique
 * `neutrodyne-diagnostics-<yyyy-MM-dd-HHmm>-<unique>.db` share name. Raw bytes never
 * exist under a name a FileProvider grant can resolve: a minted URI resolves by path
 * and outlives the file's deletion, so an earlier export's name is never reused —
 * even within the same minute. The export cache sweeps leftovers.
 *
 * Guards (09): free space under `2 × database size + 50 MB` fails [DiagnosticsError
 * .NOT_ENOUGH_SPACE]; a writer-connection wait over 30 s fails [DiagnosticsError
 * .DATABASE_BUSY]. A quarantined database never reaches here — it cannot be opened, so the
 * screen shows only its recovery cause.
 */
@SingleIn(AppScope::class)
internal class DatabaseCopyExporter
    @Inject
    constructor(
        private val db: NeutrodyneDatabase,
        private val factory: DatabaseFactory,
        private val driver: SQLiteDriver,
        private val storagePaths: StoragePaths,
        private val fileSystem: FileSystem,
        private val clock: Clock,
        @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
        private val writerWaitMs: Long = WRITER_WAIT_MS,
    ) {
        private val exportDir: Path get() = exportDirOf(storagePaths)

        /**
         * The scrubbed copy, or the failure the screen maps to its message. [produced]'s
         * caller owns the file afterwards (share sheet, save-dialog copy, deletion).
         */
        suspend fun produce(): Outcome<Path, DiagnosticsError> =
            withContext(io) {
                // The free-space guard needs the real database size; treating an unreadable
                // size as zero would skip the guard and hand out a copy anyway.
                val dbBytes =
                    suspendRunCatching { fileSystem.metadata(factory.databasePath.toPath()).size }
                        .getOrNull()
                        ?: return@withContext Outcome.Failure(DiagnosticsError.FAILED)
                val usable = factory.usableSpaceBytes()
                if (usable != null && usable < dbBytes * 2 + MIN_FREE_BYTES) {
                    return@withContext Outcome.Failure(DiagnosticsError.NOT_ENOUGH_SPACE)
                }

                fileSystem.createDirectories(exportDir)
                // Unique names per export: the raw copy is staged under a private name
                // that is never shared, and the scrubbed file moves to its own unique
                // share name — a re-export can never write raw data into a path a
                // previous export's FileProvider grant still resolves.
                val unique = Uuid.random().toString()
                val staging = exportDir / stagingName(unique)
                val target = exportDir / fileName(clock.now(), unique)
                try {
                    // VACUUM INTO cannot run inside a transaction. The 30 s bound is for
                    // waiting on the writer (09): the step itself is a blocking SQLite
                    // call coroutine cancellation cannot interrupt, so a vacuum that
                    // outlives the deadline still completes — its copy is valid and must
                    // not be deleted as "busy".
                    var vacuumFinished = false
                    withTimeoutOrNull(writerWaitMs) {
                        db.useWriterConnection { conn ->
                            conn.usePrepared("VACUUM INTO '${staging.toString().replace("'", "''")}'") { it.step() }
                            vacuumFinished = true
                        }
                    }
                    if (!vacuumFinished) {
                        return@withContext Outcome.Failure(DiagnosticsError.DATABASE_BUSY)
                    }

                    val outcome =
                        suspendRunCatching {
                            driver.open(staging.toString()).use { connection ->
                                DiagExportScrub.scrub(connection)
                                connection.prepare("VACUUM").use { it.step() }
                            }
                        }
                    outcome.fold(
                        onSuccess = {
                            // A cancellation that raced the blocking scrub is honoured
                            // before the copy becomes a share file.
                            currentCoroutineContext().ensureActive()
                            // The shareable file appears atomically, already scrubbed:
                            // unscrubbed bytes only ever existed under the staging name.
                            fileSystem.atomicMove(staging, target)
                            Outcome.Success(target)
                        },
                        onFailure = {
                            Log.w(TAG, it) { "diagnostics export scrub failed" }
                            Outcome.Failure(DiagnosticsError.FAILED)
                        },
                    )
                } finally {
                    // A timed-out, failed or cancelled export leaves no raw or partial
                    // copy behind; after a successful move the staging name is gone.
                    suspendRunCatching { fileSystem.delete(staging, mustExist = false) }
                }
            }

        /** Deletes every earlier diagnostics export — at most one waits for its share. */
        suspend fun removeStale() {
            // The call sites reach here from the caller's context; the sweep is file I/O.
            withContext(io) {
                val children = runCatching { fileSystem.listOrNull(exportDir) }.getOrNull() ?: return@withContext
                for (child in children) {
                    if (child.name.startsWith(NAME_PREFIX) && child.name.endsWith(NAME_SUFFIX)) {
                        runCatching { fileSystem.delete(child) }
                    }
                }
            }
        }

        internal companion object {
            const val NAME_PREFIX = "neutrodyne-diagnostics-"
            const val NAME_SUFFIX = ".db"
            const val WRITER_WAIT_MS = 30_000L
            const val MIN_FREE_BYTES = 50L * 1_048_576
            private const val STAGING_INFIX = "staging-"
            private const val TAG = "DbCopyExporter"

            /**
             * `neutrodyne-diagnostics-<yyyy-MM-dd-HHmm>-<unique>.db` in UTC (02; the
             * unique suffix keeps a same-minute re-export off every earlier share
             * path). month/day are Int only on the desktop's newer kotlinx-datetime;
             * the deprecated accessors are the common denominator.
             */
            @Suppress("DEPRECATION")
            fun fileName(
                now: Long,
                unique: String,
            ): String {
                val t = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.UTC)

                fun Int.pad() = toString().padStart(2, '0')
                return "$NAME_PREFIX${t.year}-${t.monthNumber.pad()}-${t.dayOfMonth.pad()}" +
                    "-${t.hour.pad()}${t.minute.pad()}-$unique$NAME_SUFFIX"
            }

            /** The private intermediate name — never returned to callers, never shared. */
            fun stagingName(unique: String): String = "$NAME_PREFIX$STAGING_INFIX$unique$NAME_SUFFIX"
        }
    }
