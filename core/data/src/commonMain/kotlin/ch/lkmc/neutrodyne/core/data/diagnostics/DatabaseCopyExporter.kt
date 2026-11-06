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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.Instant

/**
 * Produces the diagnostics database copy of 09 (D33): `VACUUM INTO` a fresh
 * `neutrodyne-diagnostics-<yyyy-MM-dd-HHmm>.db` under `<cache>/export/`, scrubbed by
 * [DiagExportScrub] on a raw driver connection, then `VACUUM`ed again so deleted bytes
 * leave the file before it is shared or copied out. The export cache sweeps leftovers.
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
    ) {
        private val exportDir: Path get() = exportDirOf(storagePaths)

        /**
         * The scrubbed copy, or the failure the screen maps to its message. [produced]'s
         * caller owns the file afterwards (share sheet, save-dialog copy, deletion).
         */
        suspend fun produce(): Outcome<Path, DiagnosticsError> =
            withContext(io) {
                val dbBytes =
                    suspendRunCatching { fileSystem.metadata(factory.databasePath.toPath()).size }
                        .getOrNull() ?: 0L
                val usable = factory.usableSpaceBytes()
                if (usable != null && usable < dbBytes * 2 + MIN_FREE_BYTES) {
                    return@withContext Outcome.Failure(DiagnosticsError.NOT_ENOUGH_SPACE)
                }

                fileSystem.createDirectories(exportDir)
                val target = exportDir / fileName(clock.now())
                fileSystem.delete(target)

                // VACUUM INTO cannot run inside a transaction; the writer wait beyond 30 s is
                // the busy failure (09). The path is ours, but the literal is escaped anyway.
                val vacuum =
                    withTimeoutOrNull(WRITER_WAIT_MS) {
                        db.useWriterConnection { conn ->
                            conn.usePrepared("VACUUM INTO '${target.toString().replace("'", "''")}'") { it.step() }
                        }
                    }
                if (vacuum == null) {
                    fileSystem.delete(target)
                    return@withContext Outcome.Failure(DiagnosticsError.DATABASE_BUSY)
                }

                val outcome =
                    suspendRunCatching {
                        driver.open(target.toString()).use { connection ->
                            DiagExportScrub.scrub(connection)
                            connection.prepare("VACUUM").use { it.step() }
                        }
                    }
                outcome.fold(
                    onSuccess = { Outcome.Success(target) },
                    onFailure = {
                        Log.w(TAG, it) { "diagnostics export scrub failed" }
                        fileSystem.delete(target)
                        Outcome.Failure(DiagnosticsError.FAILED)
                    },
                )
            }

        /** Deletes every earlier diagnostics export — at most one waits for its share. */
        suspend fun removeStale() {
            val children = runCatching { fileSystem.listOrNull(exportDir) }.getOrNull() ?: return
            for (child in children) {
                if (child.name.startsWith(NAME_PREFIX) && child.name.endsWith(NAME_SUFFIX)) {
                    runCatching { fileSystem.delete(child) }
                }
            }
        }

        internal companion object {
            const val NAME_PREFIX = "neutrodyne-diagnostics-"
            const val NAME_SUFFIX = ".db"
            const val WRITER_WAIT_MS = 30_000L
            const val MIN_FREE_BYTES = 50L * 1_048_576
            private const val TAG = "DbCopyExporter"

            /**
             * `neutrodyne-diagnostics-<yyyy-MM-dd-HHmm>.db` in UTC (02). month/day are Int
             * only on the desktop's newer kotlinx-datetime; the deprecated accessors are
             * the common denominator.
             */
            @Suppress("DEPRECATION")
            fun fileName(now: Long): String {
                val t = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.UTC)

                fun Int.pad() = toString().padStart(2, '0')
                return "$NAME_PREFIX${t.year}-${t.monthNumber.pad()}-${t.dayOfMonth.pad()}" +
                    "-${t.hour.pad()}${t.minute.pad()}$NAME_SUFFIX"
            }
        }
    }
