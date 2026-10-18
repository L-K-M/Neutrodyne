// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.importing.ImportSessionCleaner
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.hasRestoreIntent
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.SnapshotStatus
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * 05's Auto Backup producer (M3 C4b) — the Android-only snapshot lane, in common code so the
 * `AutoSnapshotWorker` host (C2) is a thin call into [produce]. One service owns the whole
 * algorithm and its controls: the enabled gate, the restore-lane guard (native work AND the
 * durable `PREVIEW` intent — either may be executing while the other is already checked),
 * the foreign-installation guard, the level ladder over [BackupWriter] and the status
 * bookkeeping. On the desktop everything is a documented no-op/Unsupported: [supported] is
 * false, no file is touched and no native work is asked — a restored
 * `backup.auto_snapshot_enabled` value is stored and ignored (05 Sync classification).
 *
 * The file lifecycle (05's state diagram): the writer publishes to
 * `files/backup/auto-snapshot.zip` atomically, so a failed level always leaves the previous
 * snapshot readable; a foreign or unprovably-local file is never overwritten — the manifest's
 * `installationId` must equal `backup.installation_id`, and a missing local ID, an unreadable
 * or newer manifest or a corrupt file all stay foreign (protected until Discard, a capable
 * restore, or the restore runner's unrestorable-file rule). Status reads never mint an
 * installation ID — only [BackupWriter] may, inside a real write.
 *
 * Writes, disables and discards serialise on [mutex] — the writer's own mutex serialises
 * writes against manual backups, but the file-touching controls must also not race each
 * other. The database is only read inside suspending calls, never at construction.
 *
 * Not contributed to the graph yet: C2's native bindings (`AutoSnapshotWorker`,
 * `FirstLaunchRestoreInitializer`, `SnapshotNowReceiver`) land with the WorkManager row.
 */
@SingleIn(AppScope::class)
@Inject
internal class AutoSnapshotProducer(
    private val db: NeutrodyneDatabase,
    private val writer: BackupWriter,
    private val cleaner: ImportSessionCleaner,
    private val readers: ArchiveReaderFactory,
    private val settings: SettingsRepository,
    private val snapshots: SnapshotScheduler,
    private val workScheduler: ImportWorkScheduler,
    buildInfo: BuildInfo,
    private val clock: Clock,
    storagePaths: StoragePaths,
    private val fileSystem: FileSystem,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
    /**
     * 05 step 6's compressed-size cap — a constructor seam only so a test can walk the level
     * ladder without producing 20 MB archives; production never overrides it.
     */
    private val maxSnapshotBytes: Long = MAX_SNAPSHOT_BYTES,
) {
    private val mutex = Mutex()
    private val codec = BackupCodec()
    private val snapshotDir = backupDirOf(storagePaths)

    /** `files/backup/auto-snapshot.zip` — also handed to `ImportSessions.createAdopted`. */
    internal val snapshotFile: Path = snapshotDir / AUTO_SNAPSHOT_FILE

    /**
     * A bump after every file mutation that writes no settings key (a discard rename) —
     * without it an observed status would keep the stale foreign flag.
     */
    private val controlSignal = MutableStateFlow(0)

    /** Android-only (05 Auto Backup, D34); every member below is a documented no-op off it. */
    internal val supported: Boolean = buildInfo.platform == BuildInfo.Platform.ANDROID

    /**
     * The status of 05's Backup row: persisted write metadata plus the live foreign probe.
     * Reading it never writes — the installation ID is a writer-side artifact.
     */
    suspend fun status(): SnapshotStatus {
        if (!supported) return UNSUPPORTED_STATUS
        return SnapshotStatus(
            supported = true,
            enabled = settings.get(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED),
            lastWrittenAt = settings.get(BackupSettingKeys.LAST_SNAPSHOT_AT).takeIf { it > 0L },
            bytes = settings.get(BackupSettingKeys.LAST_SNAPSHOT_BYTES).takeIf { it > 0L },
            level = settings.get(BackupSettingKeys.LAST_SNAPSHOT_LEVEL),
            lastError = settings.get(BackupSettingKeys.LAST_SNAPSHOT_ERROR).ifEmpty { null },
            foreignPending = snapshotProbe() == SnapshotProbe.FOREIGN,
        )
    }

    /**
     * [status] observed: re-emits when a `backup.*` setting changes, when a backup `PREVIEW`
     * session row lands or leaves (a restore request stamped, or the finished restore that
     * retired the snapshot) and when a control mutates the file. No eager polling — nothing
     * is computed until collected.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatus(): Flow<SnapshotStatus> {
        if (!supported) return flowOf(UNSUPPORTED_STATUS)
        return merge(
            settings.observe(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED).map { },
            settings.observe(BackupSettingKeys.LAST_SNAPSHOT_AT).map { },
            settings.observe(BackupSettingKeys.LAST_SNAPSHOT_BYTES).map { },
            settings.observe(BackupSettingKeys.LAST_SNAPSHOT_LEVEL).map { },
            settings.observe(BackupSettingKeys.LAST_SNAPSHOT_ERROR).map { },
            db.importDao().observeBackupPreviewSessions().map { },
            controlSignal.map { },
        ).mapLatest { status() }
            .distinctUntilChanged()
    }

    /**
     * One pass of 05's Snapshot production algorithm: the enabled gate, the restore-lane
     * guard (native work or a committed durable intent — either side of a crash window),
     * the foreign probe, the M3 interim import-session cleanup, then the level ladder.
     * Every guard failure returns a distinct [SnapshotResult] and preserves the current file.
     */
    suspend fun produce(): SnapshotResult {
        if (!supported) return SnapshotResult.Unsupported
        return mutex.withLock { produceLocked() }
    }

    private suspend fun produceLocked(): SnapshotResult {
        if (!settings.get(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED)) return SnapshotResult.Disabled

        // Step 2: native restore work may be enqueued or finishing. A check that cannot be
        // answered is "cannot prove idle" — decline the write, never a best-guess overwrite.
        val restoreWork =
            suspendRunCatching { workScheduler.hasRestoreWork() }
                .onFailure { Log.w(TAG, it) { "restore-work check failed; treating the lane as busy" } }
                .getOrDefault(true)
        if (restoreWork) return SnapshotResult.GuardedRestore

        // The durable half of the same guard: a PREVIEW intent the host has not picked up
        // (or whose enqueue threw) is live work the same rule protects.
        val intentPending =
            suspendRunCatching { db.importDao().backupPreviewSessions().any { it.hasRestoreIntent() } }
                .onFailure { Log.w(TAG, it) { "pending-restore check failed; treating the lane as busy" } }
                .getOrDefault(true)
        if (intentPending) return SnapshotResult.GuardedRestore

        // Step 3: a foreign or unprovably-local snapshot is never overwritten.
        if (snapshotProbe() == SnapshotProbe.FOREIGN) return SnapshotResult.GuardedForeign

        // Step 4 (interim until 02's db-maintenance lands, M11b): the host sweeps sessions.
        suspendRunCatching { cleaner.cleanExpired() }
            .onFailure { Log.w(TAG, it) { "import-session cleanup failed during snapshot production" } }

        // `files/backup/` is this lane's own directory — the writer stages into it but never
        // creates it, so the first write's parent must exist before the ladder starts.
        val dirReady =
            suspendRunCatching { withContext(io) { fileSystem.createDirectories(snapshotDir) } }
        if (dirReady.isFailure) {
            Log.w(TAG, dirReady.exceptionOrNull()) { "snapshot directory creation failed" }
            recordFailure(BackupError.Storage)
            return SnapshotResult.Failed(BackupError.Storage)
        }

        // Steps 5–7: the level ladder. Only a TooLarge failure advances; anything else — and
        // a still-too-large level 3 — keeps the previous snapshot and records the error.
        for (level in SNAPSHOT_LEVELS) {
            when (
                val written =
                    writer.write(
                        BackupKind.AUTO_SNAPSHOT,
                        snapshotFile,
                        includeCredentials = false,
                        level = level,
                        maxCompressedBytes = maxSnapshotBytes,
                    )
            ) {
                is Outcome.Success -> {
                    recordPublication(written.value, level)
                    sweepRetired()
                    return SnapshotResult.Written(written.value)
                }

                is Outcome.Failure -> {
                    if (written.error != BackupError.TooLarge) {
                        recordFailure(written.error)
                        return SnapshotResult.Failed(written.error)
                    }
                }
            }
        }
        recordFailure(BackupError.TooLarge)
        return SnapshotResult.Failed(BackupError.TooLarge)
    }

    /**
     * An owned file must be removed before recording the disabled flag: a failed removal
     * must not leave a supposedly-disabled snapshot in the OS backup include path. That
     * mutation finishes non-cancellably once started. A foreign snapshot is declined outright:
     * deleting the file is Discard's job and the order matters (05 First-launch restore).
     */
    suspend fun setEnabled(enabled: Boolean): Boolean {
        if (!supported) return false
        return mutex.withLock {
            if (enabled) {
                if (settings.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, true) is Outcome.Failure) {
                    Log.w(TAG) { "auto-snapshot enable write failed" }
                    return@withLock false
                }
                // Re-arm the debounced write so the lane is live before the periodic name
                // returns with the start-up initializer's next UPDATE enqueue (05's table).
                suspendRunCatching { snapshots.requestSoon() }
                    .onFailure { Log.w(TAG, it) { "snapshot poke after enable failed" } }
                true
            } else {
                if (snapshotProbe() == SnapshotProbe.FOREIGN) {
                    Log.i(TAG) { "auto-snapshot disable declined: a foreign snapshot is pending" }
                    return@withLock false
                }
                withContext(NonCancellable) {
                    val removed =
                        suspendRunCatching {
                            withContext(io) { fileSystem.delete(snapshotFile, mustExist = false) }
                        }
                    if (removed.isFailure) {
                        Log.w(TAG, removed.exceptionOrNull()) { "auto-snapshot file delete failed" }
                        recordFailure(BackupError.Io)
                        return@withContext false
                    }
                    if (settings.set(BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, false) is Outcome.Failure) {
                        Log.w(TAG) { "auto-snapshot disable write failed" }
                        recordFailure(BackupError.Storage)
                        return@withContext false
                    }
                    suspendRunCatching { snapshots.cancelScheduled() }
                        .onFailure { Log.w(TAG, it) { "snapshot work cancellation failed" } }
                    controlSignal.value++
                    true
                }
            }
        }
    }

    /**
     * 05's Discard: the pending foreign snapshot retires to `restored-{epochMs}.zip`, which a
     * later successful write sweeps. No file is already settled; a provably-own file is
     * never the Discard's target and
     * a failed rename keeps the foreign guard protecting the file.
     */
    suspend fun discardPending(): Boolean {
        if (!supported) return false
        return mutex.withLock {
            when (snapshotProbe()) {
                SnapshotProbe.ABSENT -> {
                    true
                }

                SnapshotProbe.OWN -> {
                    Log.w(TAG) { "discard declined: the snapshot belongs to this installation" }
                    false
                }

                SnapshotProbe.FOREIGN -> {
                    val retired =
                        withContext(io) {
                            suspendRunCatching {
                                fileSystem.atomicMove(snapshotFile, snapshotDir / restoredSnapshotName(clock.now()))
                            }.onFailure { Log.w(TAG, it) { "pending snapshot discard failed" } }
                                .isSuccess
                        }
                    if (retired) controlSignal.value++
                    retired
                }
            }
        }
    }

    /**
     * The manifest-only probe (05 step 3): bounded reads through [ArchiveCaps.BACKUP] and the
     * codec's own manifest cap — entry names and bodies are never trusted. `FOREIGN` means
     * "not provably this installation's": a different `installationId`, but also a missing
     * local ID, an unreadable archive or a newer `minReaderVersion` — anything that cannot
     * prove ownership protects the file from overwrite.
     */
    private suspend fun snapshotProbe(): SnapshotProbe =
        withContext(io) {
            if (!fileSystem.exists(snapshotFile)) return@withContext SnapshotProbe.ABSENT
            val installationId = settings.get(BackupSettingKeys.INSTALLATION_ID)
            if (installationId.isEmpty()) return@withContext SnapshotProbe.FOREIGN
            val snapshotId =
                try {
                    readers.open(snapshotFile, ArchiveCaps.BACKUP).use { archive ->
                        archive.open(BackupCodec.ENTRY_MANIFEST).use { source ->
                            codec.decodeManifest(source).installationId
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    // Not openable, not a backup, hostile caps, a corrupt or a newer manifest —
                    // all fail closed to foreign protection (05 First-launch restore).
                    Log.i(TAG) { "auto-snapshot manifest unreadable; keeping it as foreign" }
                    null
                }
            if (snapshotId == null || snapshotId != installationId) SnapshotProbe.FOREIGN else SnapshotProbe.OWN
        }

    /**
     * 05 step 7's bookkeeping: the written size, level and time plus the cleared error. A
     * failed set is logged, not reported — the file was published and the status keys are
     * observational metadata.
     */
    private suspend fun recordPublication(
        summary: BackupSummary,
        level: SnapshotLevel,
    ) {
        val now = clock.now()
        suspendRunCatching { settings.set(BackupSettingKeys.LAST_SNAPSHOT_AT, now) }
            .onFailure { Log.w(TAG, it) { "last_snapshot_at write failed" } }
        suspendRunCatching { settings.set(BackupSettingKeys.LAST_SNAPSHOT_BYTES, summary.bytes) }
            .onFailure { Log.w(TAG, it) { "last_snapshot_bytes write failed" } }
        suspendRunCatching { settings.set(BackupSettingKeys.LAST_SNAPSHOT_LEVEL, level.wire) }
            .onFailure { Log.w(TAG, it) { "last_snapshot_level write failed" } }
        suspendRunCatching { settings.set(BackupSettingKeys.LAST_SNAPSHOT_ERROR, "") }
            .onFailure { Log.w(TAG, it) { "last_snapshot_error clear failed" } }
    }

    /**
     * 05 step 6's recorded failure — the status line's `lastError`. The preserved snapshot
     * (and `restored-*.zip` files) is untouched; only the bookkeeping row moves.
     */
    private suspend fun recordFailure(error: BackupError) {
        suspendRunCatching { settings.set(BackupSettingKeys.LAST_SNAPSHOT_ERROR, errorCode(error)) }
            .onFailure { Log.w(TAG, it) { "last_snapshot_error write failed" } }
    }

    /** 05 step 7's second half: `restored-*.zip` remnants only — every other file stays. */
    private suspend fun sweepRetired() =
        withContext(io) {
            suspendRunCatching {
                for (file in fileSystem.list(snapshotDir)) {
                    if (isRetiredSnapshotFile(file.name)) fileSystem.delete(file)
                }
            }.onFailure { Log.w(TAG, it) { "retired-snapshot cleanup failed" } }
        }

    /** `backup.last_snapshot_error`'s wire strings (05 Backup status). */
    private fun errorCode(error: BackupError): String =
        when (error) {
            BackupError.TooLarge -> ERROR_TOO_LARGE

            BackupError.Storage -> ERROR_STORAGE

            BackupError.Io -> ERROR_IO

            // `BackupWriter` only reports the three above; a future kind is still an honest
            // "the write failed" line rather than a missing one.
            else -> ERROR_IO
        }

    private enum class SnapshotProbe {
        ABSENT,
        OWN,
        FOREIGN,
    }

    private companion object {
        const val TAG = "AutoSnapshotProducer"
        const val ERROR_TOO_LARGE = "too_large"
        const val ERROR_IO = "io"
        const val ERROR_STORAGE = "storage"

        /** 05 Snapshot production step 6's compressed-size ceiling. */
        const val MAX_SNAPSHOT_BYTES = 20_000_000L

        val UNSUPPORTED_STATUS =
            SnapshotStatus(
                supported = false,
                enabled = false,
                lastWrittenAt = null,
                bytes = null,
                level = 0,
                lastError = null,
                foreignPending = false,
            )

        /** The 05 level ladder, low to high reduction — the write retries in this order. */
        val SNAPSHOT_LEVELS =
            listOf(
                SnapshotLevel.FULL,
                SnapshotLevel.SLIM_HISTORY,
                SnapshotLevel.DROP_HISTORY,
                SnapshotLevel.ACTIVE_ONLY,
            )
    }
}

/**
 * What one [AutoSnapshotProducer.produce] pass did (05 Snapshot production): explicit states
 * so a caller — the C2 worker, `writeSnapshotNow` — never has to infer "written" from a file
 * timestamp.
 */
internal sealed interface SnapshotResult {
    /** The new snapshot was published; [summary] is the writer's `BackupSummary`. */
    data class Written(
        val summary: BackupSummary,
    ) : SnapshotResult

    /** `backup.auto_snapshot_enabled` is off — nothing was attempted. */
    data object Disabled : SnapshotResult

    /** A restore is enqueued or running (native work or a durable intent) — it owns the lane. */
    data object GuardedRestore : SnapshotResult

    /** The pending snapshot is not provably this installation's — the foreign guard held. */
    data object GuardedForeign : SnapshotResult

    /** Every level failed ([error] is the last one); the previous snapshot stands. */
    data class Failed(
        val error: BackupError,
    ) : SnapshotResult

    /** Not Android — 05's desktop rule; no file or native work was touched. */
    data object Unsupported : SnapshotResult
}
