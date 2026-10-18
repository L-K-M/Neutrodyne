// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.exporting.ExportDestination
import ch.lkmc.neutrodyne.core.data.exporting.exportDirOf
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportSessions
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.data.importing.RestoreEnqueue
import ch.lkmc.neutrodyne.core.data.importing.hasRestoreIntent
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import ch.lkmc.neutrodyne.core.model.SnapshotStatus
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * `BackupRepository` (05 Full backup and restore, M3 B12c) — the public contract's common
 * implementation. Every member delegates to the service that owns the work:
 * [BackupWriter] for the manual write and the R1.9 preflight counts,
 * [BackupStager] for archive validation and the preview, [RestoreRunner] for progress,
 * [AutoSnapshotProducer] for the Android snapshot lane and [ImportSessions] for the
 * adopted-snapshot session. `restore` itself only writes the durable intent and pays the
 * owed native enqueue — the runner does the restore.
 *
 * Manual `createBackup` runs the whole write → copy → cleanup under [backupMutex], not just
 * the writer's own lock: two callers in the same minute mint the same file name, so the
 * prepared cache artifact stays private to one caller until its copy (and delete) ends —
 * an opted-in password backup can never overwrite a concurrent password-free caller's
 * source mid-flight.
 *
 * Not contributed to the graph yet: [ExportDestination] binds with the platform rows
 * (C1 `SafExportDestination`, C3 `FileExportDestination`), like `ExportRepositoryImpl`.
 */
@SingleIn(AppScope::class)
@Inject
internal class BackupRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val writer: BackupWriter,
    private val stager: BackupStager,
    private val runner: RestoreRunner,
    private val sessions: ImportSessions,
    private val producer: AutoSnapshotProducer,
    private val destination: ExportDestination,
    private val payloads: PayloadStore,
    private val settings: SettingsRepository,
    private val workScheduler: ImportWorkScheduler,
    private val clock: Clock,
    storagePaths: StoragePaths,
    private val fileSystem: FileSystem,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : BackupRepository {
    /** Serialises the whole manual write → destination copy → cache cleanup (05 Writing step 5). */
    private val backupMutex = Mutex()

    private val exportDir: Path = exportDirOf(storagePaths)
    private val stagingRoot: Path = restoreStagingDirOf(storagePaths)

    // --- Manual backup (05 Writing) --------------------------------------------------------------

    override suspend fun createBackup(
        destinationUri: String,
        includePasswords: Boolean,
    ): Outcome<BackupSummary, BackupError> =
        backupMutex.withLock {
            // `<cache>/export/` is this flow's own directory — the writer stages into it
            // but never creates it.
            val dirReady =
                suspendRunCatching { withContext(io) { fileSystem.createDirectories(exportDir) } }
            if (dirReady.isFailure) {
                Log.w(TAG, dirReady.exceptionOrNull()) { "export directory creation failed" }
                return@withLock Outcome.Failure(BackupError.Storage)
            }

            val target = exportDir / backupFileName(clock.now())
            try {
                val summary =
                    when (val written = writer.write(BackupKind.MANUAL, target, includePasswords)) {
                        is Outcome.Failure -> return@withLock Outcome.Failure(written.error)
                        is Outcome.Success -> written.value
                    }
                // A throw from the platform seam (provider quirks beyond its own Outcome
                // cases) reports "couldn't write" rather than crashing — ExportRepositoryImpl's
                // rule. The finally sweeps the owned artifact on every path, cancellation
                // included, so a same-minute name never leaks across calls.
                val saved =
                    suspendRunCatching { destination.save(target, destinationUri) }
                        .getOrNull() ?: return@withLock Outcome.Failure(BackupError.Io)
                when (saved) {
                    is Outcome.Failure -> {
                        Outcome.Failure(saved.error.toBackupError())
                    }

                    is Outcome.Success -> {
                        // The timestamp is bookkeeping for "Last backup: …" — a failed set
                        // is logged, not reported: the destination copy already landed.
                        val stamped =
                            suspendRunCatching {
                                settings.set(BackupSettingKeys.LAST_MANUAL_BACKUP_AT, clock.now())
                            }
                        if (stamped.getOrNull() !is Outcome.Success) {
                            Log.w(TAG, stamped.exceptionOrNull()) { "last_manual_backup_at write failed" }
                        }
                        Outcome.Success(summary)
                    }
                }
            } finally {
                deleteOwned(target)
            }
        }

    override suspend fun preflight(includePasswords: Boolean): PrivacyCounts = writer.preflight(includePasswords)

    // --- Inspect and the durable restore request ---------------------------------------------------

    /**
     * 05 Validation and preview: resolve the session's stored payload and let the stager
     * validate + build the preview; [StagedBackup.use] guarantees the staging directory
     * never outlives the call. No row is ever written here.
     */
    override suspend fun inspect(sessionId: Long): Outcome<BackupPreview, BackupError> {
        val found = suspendRunCatching { db.importDao().sessionById(sessionId) }
        val session =
            found.getOrNull()
                ?: return Outcome.Failure(
                    if (found.isFailure) BackupError.Io else BackupError.NotABackup,
                )
        if (session.sourceFormat != ImportFormat.NEUTRODYNE_BACKUP) {
            return Outcome.Failure(BackupError.NotABackup)
        }
        // A backup session without a payload path is malformed — the archive is unreadable.
        val payloadPath = session.payloadPath ?: return Outcome.Failure(BackupError.Io)
        return when (val staged = stager.inspect(payloads.payloadFile(payloadPath), stagingRoot)) {
            is Outcome.Failure -> staged
            is Outcome.Success -> staged.value.use { Outcome.Success(it.preview) }
        }
    }

    /**
     * 05 Restore arbitration: claim the session's durable intent in one write transaction —
     * mode, categories, reach and `restoreRequestedAt` merged into the existing `optionsJson`
     * (parser/system fields are preserved untouched) — then pay the owed native enqueue.
     *
     * The transaction and the enqueue are one [NonCancellable] unit: cancellation
     * delivered at Room COMMIT cannot separate a landed intent from its enqueue
     * (the host then owns the work). [RestoreEnqueue.ALREADY_RUNNING] means the platform
     * *definitively* declined the new request, so this stamp — provably unused — is rolled
     * back and the caller sees [BackupError.RestoreRunning]; a thrown enqueue is unknown
     * acceptance, so the intent stays and [BackupError.Storage] reports it — the C2/C3
     * native host's start-up reconciliation re-scans `PREVIEW` backup sessions for
     * `restoreRequestedAt` and enqueues whatever this path could not.
     */
    override suspend fun restore(
        sessionId: Long,
        request: RestoreRequest,
    ): Outcome<Unit, BackupError> {
        // A restore running on another session owns the lane; a check that cannot be
        // answered fails closed to "busy" — never best-guess a parallel restore.
        val busy =
            suspendRunCatching { workScheduler.hasRestoreWork() }
                .onFailure { Log.w(TAG, it) { "restore-work check failed; treating the lane as busy" } }
                .getOrDefault(true)
        if (busy) return Outcome.Failure(BackupError.RestoreRunning)

        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val claimed: Outcome<ClaimedRestore, BackupError> =
                try {
                    db.withWriteTransaction { claimLocked(sessionId, request) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, error) { "restore intent write failed" }
                    return@withContext Outcome.Failure(BackupError.Storage)
                }
            when (claimed) {
                is Outcome.Failure -> claimed
                is Outcome.Success -> dispatchRestore(sessionId, claimed.value)
            }
        }
    }

    /**
     * The claim transaction body: the row must be a `NEUTRODYNE_BACKUP` session still in
     * `PREVIEW` with a decodable `optionsJson` and no restore intent anywhere — this row's
     * or another preview backup session's. The returned receipt belongs to this write,
     * rather than a later read that could observe another request's metadata.
     */
    private suspend fun claimLocked(
        sessionId: Long,
        request: RestoreRequest,
    ): Outcome<ClaimedRestore, BackupError> {
        val dao = db.importDao()
        val session = dao.sessionById(sessionId) ?: return Outcome.Failure(BackupError.NotABackup)
        if (session.sourceFormat != ImportFormat.NEUTRODYNE_BACKUP) {
            return Outcome.Failure(BackupError.NotABackup)
        }
        if (session.payloadPath.isNullOrBlank()) return Outcome.Failure(BackupError.Io)
        if (session.state != ImportState.PREVIEW) {
            // COMMITTED/FETCHING means a restore already claimed this session (the finish
            // marker keeps `restoreRequestedAt`); DONE/CANCELLED leaves nothing restorable.
            return Outcome.Failure(
                when (session.state) {
                    ImportState.COMMITTED, ImportState.FETCHING -> BackupError.RestoreRunning
                    else -> BackupError.NotABackup
                },
            )
        }
        // A missing or undecodable `optionsJson` fails closed: it may hide an intent the
        // shared `hasRestoreIntent` rule protects — never stamp over an unreadable row.
        val options =
            session.optionsJson?.let(ImportOptionsJson::decodeOrNull)
                ?: return Outcome.Failure(BackupError.RestoreRunning)
        if (options.restoreRequestedAt != null) return Outcome.Failure(BackupError.RestoreRunning)
        if (dao.backupPreviewSessions().any { it.id != sessionId && it.hasRestoreIntent() }) {
            return Outcome.Failure(BackupError.RestoreRunning)
        }
        val stamp = clock.now()
        dao.setOptionsJson(
            sessionId,
            ImportOptionsJson.encode(
                options.copy(
                    restoreMode = request.mode,
                    restoreCategories = request.categories,
                    linkedReach = request.linkedReach,
                    restoreRequestedAt = stamp,
                ),
            ),
        )
        return Outcome.Success(ClaimedRestore(stamp, options.fromAndroidBackup))
    }

    /**
     * The owed enqueue after a committed claim, [NonCancellable] so a cancelled caller
     * cannot strand a durable intent: ENQUEUED reports success, a definitive decline undoes
     * only this still-unused stamp and an unknown answer keeps the intent for the native
     * host's start-up reconciliation.
     */
    private suspend fun dispatchRestore(
        sessionId: Long,
        claim: ClaimedRestore,
    ): Outcome<Unit, BackupError> =
        withContext(NonCancellable) {
            val ack = suspendRunCatching { workScheduler.scheduleRestore(sessionId, claim.auto) }
            when (val result = ack.getOrNull()) {
                null -> {
                    // Unknown acceptance: the intent stays durable; the host's start-up
                    // reconciliation recovers it (see `ImportWorkScheduler.scheduleRestore`).
                    Log.w(TAG, ack.exceptionOrNull()) {
                        "restore enqueue outcome unknown; the durable intent stays"
                    }
                    Outcome.Failure(BackupError.Storage)
                }

                RestoreEnqueue.ENQUEUED -> {
                    Outcome.Success(Unit)
                }

                RestoreEnqueue.ALREADY_RUNNING -> {
                    if (undoIntent(sessionId, claim.stamp)) {
                        Outcome.Failure(BackupError.RestoreRunning)
                    } else {
                        // The intent could not be undone — it may still execute, which
                        // "RestoreRunning" would deny. Storage is the honest answer.
                        Outcome.Failure(BackupError.Storage)
                    }
                }
            }
        }

    /**
     * Roll back only the intent [stamp] wrote: the row must still be a `PREVIEW` backup
     * session whose `restoreRequestedAt` is exactly our stamp — an intent another request
     * (or a replayed enqueue) could be acting on is never cleared.
     */
    private suspend fun undoIntent(
        sessionId: Long,
        stamp: Long,
    ): Boolean =
        suspendRunCatching {
            db.withWriteTransaction {
                val dao = db.importDao()
                val session = dao.sessionById(sessionId) ?: return@withWriteTransaction false
                if (session.sourceFormat != ImportFormat.NEUTRODYNE_BACKUP) return@withWriteTransaction false
                if (session.state != ImportState.PREVIEW) return@withWriteTransaction false
                val options =
                    session.optionsJson?.let(ImportOptionsJson::decodeOrNull)
                        ?: return@withWriteTransaction false
                if (options.restoreRequestedAt != stamp) return@withWriteTransaction false
                dao.setOptionsJson(
                    sessionId,
                    ImportOptionsJson.encode(
                        options.copy(
                            restoreRequestedAt = null,
                        ),
                    ),
                )
                true
            }
        }.onFailure { Log.w(TAG, it) { "restore intent rollback failed for session $sessionId" } }
            .getOrDefault(false)

    override fun observeRestore(): Flow<RestoreProgress?> = runner.progress

    // --- The Android snapshot lane (05 Auto Backup) --------------------------------------------------

    override fun observeSnapshotStatus(): Flow<SnapshotStatus> = producer.observeStatus()

    override suspend fun setSnapshotEnabled(enabled: Boolean) {
        // The producer owns serialization and the foreign-pending decline; a `false` there
        // (declined or unsupported) is a documented no-op for the toggle.
        producer.setEnabled(enabled)
    }

    /**
     * 05 First-launch restore: adopt `auto-snapshot.zip` — whatever its provenance — into a
     * `NEUTRODYNE_BACKUP` session through [ImportSessions.createAdopted]. An own-installation
     * snapshot recovers the same way; the foreign/own distinction only gates the UI's
     * banner and the producer's overwrite protection, never adoption.
     */
    override suspend fun restoreAndroidBackup(): Outcome<Long, BackupError> {
        if (!producer.supported) return Outcome.Failure(BackupError.Unsupported)
        return when (
            val adopted =
                sessions.createAdopted(
                    producer.snapshotFile,
                    AUTO_SNAPSHOT_FILE,
                    CreateImportOptions(),
                )
        ) {
            is Outcome.Failure -> Outcome.Failure(adopted.error.toBackupError())
            is Outcome.Success -> adopted
        }
    }

    override suspend fun discardAndroidBackup() {
        // The producer owns the foreign-guarded retire rename and its serialization.
        producer.discardPending()
    }

    /**
     * 05's "Write snapshot now" (`SnapshotNowReceiver`, debug row): the receiver maps the
     * returned [SnapshotStatus] — `enabled = false` → "disabled", a held guard →
     * "guarded" — so every non-fatal [SnapshotResult] answers with the live status and only
     * writer failures and the desktop lane surface as errors.
     */
    override suspend fun writeSnapshotNow(): Outcome<SnapshotStatus, BackupError> =
        when (val result = producer.produce()) {
            is SnapshotResult.Written,
            SnapshotResult.Disabled,
            SnapshotResult.GuardedRestore,
            SnapshotResult.GuardedForeign,
            -> Outcome.Success(producer.status())

            is SnapshotResult.Failed -> Outcome.Failure(result.error)

            SnapshotResult.Unsupported -> Outcome.Failure(BackupError.Unsupported)
        }

    // --- Mapping and helpers ------------------------------------------------------------------------

    /** Best-effort removal of the owned cache artifact, cancellation-safe. */
    private suspend fun deleteOwned(target: Path) {
        withContext(NonCancellable + io) {
            suspendRunCatching { fileSystem.delete(target, mustExist = false) }
                .onFailure { Log.w(TAG, it) { "prepared backup cleanup failed: $target" } }
        }
    }

    /** 05 Writing step 5's name: `neutrodyne-backup-{yyyy-MM-dd-HHmm}.zip` in UTC. */
    private fun backupFileName(nowMs: Long): String {
        val stamp = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.UTC)
        return buildString(31) {
            append("neutrodyne-backup-")
            append(stamp.year)
            append('-')
            appendPadded(stamp.month.ordinal + 1)
            append('-')
            appendPadded(stamp.day)
            append('-')
            appendPadded(stamp.hour)
            appendPadded(stamp.minute)
            append(".zip")
        }
    }

    private fun StringBuilder.appendPadded(value: Int): StringBuilder {
        if (value < 10) append('0')
        return append(value)
    }

    private fun ExportError.toBackupError(): BackupError =
        when (this) {
            ExportError.Io -> BackupError.Io
            ExportError.Storage -> BackupError.Storage
            ExportError.DestinationUnavailable -> BackupError.DestinationUnavailable
        }

    private fun ImportError.toBackupError(): BackupError =
        when (this) {
            is ImportError.TooLarge, is ImportError.Hostile -> BackupError.TooLarge
            is ImportError.Unsupported, ImportError.Empty -> BackupError.NotABackup
            is ImportError.Storage -> BackupError.Storage
            ImportError.Unreadable, is ImportError.Network, ImportError.SessionGone -> BackupError.Io
        }

    private class ClaimedRestore(
        val stamp: Long,
        val auto: Boolean,
    )

    private companion object {
        const val TAG = "BackupRepositoryImpl"
    }
}
