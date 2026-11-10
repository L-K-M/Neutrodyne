// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.LinkedRestoreCoordinator
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.LinkedRestoreReach
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergeSummary
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import kotlin.coroutines.cancellation.CancellationException

/**
 * 05's Restore algorithm (M3 B12b): executes one `NEUTRODYNE_BACKUP` `import_session` whose
 * `ImportOptions.restoreRequestedAt` a `BackupRepository.restore` call committed. The host
 * — Android's `import-restore` worker or the desktop `import-backup` lane (C2/C3) — supplies
 * the session id; this runner owns everything after it: durable-intent gating, re-validation
 * through [BackupStager], the library phase ([RestoreMerger.mergeLibrary]), the
 * episode/queue/session phase ([RestoreEpisodeMerger.mergeEpisodes]), portable settings
 * ([RestoreSettingsMerge]) and the finish step's items, markers and effects.
 *
 * Ownership boundaries: matching, unsubscribes, credentials, aliases and queue/session rows
 * stay with the B10/B11 mergers — transaction A's [LibraryMergeResult] maps are the only
 * identities any later phase or the finish attaches through (no re-matching after library
 * writes). Staging lifetime stays with [StagedBackup.close] — only the owned staging
 * directory is ever deleted; the acquired payload belongs to the session's cleanup rules.
 *
 * Lifecycle: the durable request survives everything until COMMITTED. Validation failures
 * cancel the session and report [RestoreProgress.Failed] (an `auto` restore's
 * Corrupt/NotABackup additionally retires the foreign snapshot to `restored-{ts}.zip`;
 * NewerFormat keeps it). A failure after validation — storage, a settings write — returns
 * [RestoreRunResult.Retry] with the intent untouched, and a cancelled host leaves the same
 * durable PREVIEW request for the next run: every merger step is idempotent, so a replay
 * re-validates and re-runs the whole algorithm on the same acquired payload.
 *
 * Linked restores (05 Restore while linked, MS2): a Merge runs with `applying = 1` and
 * `captureAt` at the backup's own timestamps; a Replace reaches THIS_DEVICE (unlink
 * first through [LinkedRestoreCoordinator], then a plain local Replace) or ALL_DEVICES
 * (the claim cleared `linkedAt`; the Replace runs under `applying`, and the finish runs
 * 10's upload/prune steps before the session commits).
 *
 * Runs serialize on an in-process mutex: one restore at a time per process, a queued run
 * re-reads the row's current state when it enters. Not contributed to the graph yet —
 * bindings arrive with B12c's `BackupRepositoryImpl` and the platform hosts.
 */
@Inject
@SingleIn(AppScope::class)
internal class RestoreRunner(
    private val db: NeutrodyneDatabase,
    private val stager: BackupStager,
    private val merger: RestoreMerger,
    private val episodes: RestoreEpisodeMerger,
    private val settingsMerge: RestoreSettingsMerge,
    private val payloads: PayloadStore,
    storagePaths: StoragePaths,
    private val fileSystem: FileSystem,
    private val workScheduler: ImportWorkScheduler,
    private val refresh: RefreshController,
    private val channels: GroupChannelSync,
    private val snapshots: SnapshotScheduler,
    private val linkedSync: LinkedRestoreCoordinator,
    private val clock: Clock,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val progressFlow = MutableStateFlow<RestoreProgress?>(null)

    /** The staging lane: restore-owned directories under the import cache (`StagingRoot`). */
    private val stagingRoot = restoreStagingDirOf(storagePaths)

    /** The app-private auto-snapshot a `fromAndroidBackup` session was adopted from. */
    private val autoSnapshotDir = backupDirOf(storagePaths)
    private val autoSnapshot = autoSnapshotDir / AUTO_SNAPSHOT_FILE

    /** The flow B12c's `BackupRepositoryImpl.observeRestore()` exposes (05). */
    val progress: Flow<RestoreProgress?> = progressFlow

    /**
     * Runs the durable restore of [sessionId] to COMMITTED, or explains why nothing ran.
     * Cancellation propagates and leaves the request durable — the next host run replays.
     */
    suspend fun run(sessionId: Long): RestoreRunResult = mutex.withLock { runLocked(sessionId) }

    private suspend fun runLocked(sessionId: Long): RestoreRunResult {
        val pending =
            when (val gate = db.withReadTransaction { gateLocked(sessionId) }) {
                Gate.NotPending -> {
                    return RestoreRunResult.NotPending
                }

                is Gate.Requested -> {
                    gate.request
                }
            }
        // 05/08: the actual link answer is read outside the Room transaction (the coordinator
        // reads settings and the secret store). A stale `linkedAt` (copied database, revoked
        // token, disabled switch or missing token) must not offer the linked path; the stored
        // ALL_DEVICES reach keeps its claim-cleared state on the linked path.
        val linkedHost =
            when (val link = linkedSync.actualLinkedHost()) {
                is Outcome.Success -> link.value
                is Outcome.Failure -> return RestoreRunResult.Retry(sessionId, link.error)
            }
        val request = pending.withActualLink(linkedHost != null)

        progressFlow.value = RestoreProgress.Running(sessionId, PHASE_VALIDATE, 0, 0)
        // 05 step 1 first: the payload is validated before any unlink, so a bad archive cancels
        // the session without deleting the token or clearing the sync tables.
        val staged =
            when (
                val outcome =
                    stager.inspect(
                        payloads.payloadFile(request.payloadPath),
                        stagingRoot,
                        resolvedLinkHost = ResolvedLinkHost(linkedHost),
                    )
            ) {
                is Outcome.Failure -> return failRestore(sessionId, outcome.error, request.auto)
                is Outcome.Success -> outcome.value
            }
        return try {
            // 05 Restore while linked, Replace THIS_DEVICE: 10's unlink runs before any restore
            // write, so the device is unlinked for the whole Replace and pushes nothing.
            if (request.unlinkFirst) {
                when (val outcome = linkedSync.unlinkForRestore()) {
                    is Outcome.Failure -> return RestoreRunResult.Retry(sessionId, outcome.error)
                    is Outcome.Success -> Unit
                }
            }
            runStaged(sessionId, request, staged)
        } finally {
            staged.close()
        }
    }

    /**
     * The durable gate (re-read on every run — a replayed or racing host must not trust a
     * caller's memory): a `NEUTRODYNE_BACKUP` session in `PREVIEW`, a decodable
     * `optionsJson` carrying `restoreRequestedAt` and a mode, and a payload to re-validate.
     * A corrupt options row is logged and left alone — never replaced with defaults.
     *
     * The link answer is read outside this transaction (05/08): a linked Merge captures with
     * the backup's timestamps; a Replace with reach ALL_DEVICES ran its claim-time `linkedAt`
     * clear, so it stays on the linked execution path through its stored reach; a Replace that
     * is no longer linked (the user unlinked meanwhile) degrades to the plain local rules.
     */
    private suspend fun gateLocked(sessionId: Long): Gate {
        val session = db.importDao().sessionById(sessionId) ?: return Gate.NotPending
        if (session.sourceFormat != ImportFormat.NEUTRODYNE_BACKUP) return Gate.NotPending
        if (session.state != ImportState.PREVIEW) return Gate.NotPending
        val encoded = session.optionsJson ?: return Gate.NotPending
        val options =
            ImportOptionsJson.decodeOrNull(encoded)
                ?: run {
                    Log.w(TAG) { "restore session $sessionId skipped: unreadable options" }
                    return Gate.NotPending
                }
        options.restoreRequestedAt ?: return Gate.NotPending
        val mode =
            options.restoreMode
                ?: run {
                    Log.w(TAG) { "restore session $sessionId skipped: requested without a mode" }
                    return Gate.NotPending
                }
        val payloadPath = session.payloadPath ?: return Gate.NotPending
        // The stored reach decides: THIS_DEVICE on an unlinked device is the plain local
        // Replace; ALL_DEVICES keeps its claim-cleared unlinked state on the linked path.
        val reach =
            if (mode == RestoreMode.REPLACE) {
                options.linkedReach ?: LinkedRestoreReach.THIS_DEVICE
            } else {
                LinkedRestoreReach.THIS_DEVICE
            }
        return Gate.Requested(
            PendingRestore(
                mode = mode,
                categories = options.restoreCategories,
                payloadPath = payloadPath,
                auto = options.fromAndroidBackup,
                reach = reach,
            ),
        )
    }

    /**
     * The link-derived execution flags (05 Restore while linked): a linked Merge captures with
     * the backup's timestamps; THIS_DEVICE unlinks first; ALL_DEVICES keeps its claim-cleared
     * unlinked state on the linked path, so the finish runs the upload/prune steps. A false
     * [actuallyLinked] on THIS_DEVICE degrades to the plain local rules — a stale `linkedAt`
     * must never unlink, log out or clear the remembered profile.
     */
    private fun PendingRestore.withActualLink(actuallyLinked: Boolean): RequestedRestore {
        val linkedReplace =
            mode == RestoreMode.REPLACE && (actuallyLinked || reach == LinkedRestoreReach.ALL_DEVICES)
        return RequestedRestore(
            mode = mode,
            categories = categories,
            payloadPath = payloadPath,
            auto = auto,
            linkedMerge = actuallyLinked && mode == RestoreMode.MERGE,
            suppressCapture = linkedReplace && reach == LinkedRestoreReach.ALL_DEVICES,
            unlinkFirst = linkedReplace && reach == LinkedRestoreReach.THIS_DEVICE,
            completeAllDevices = linkedReplace && reach == LinkedRestoreReach.ALL_DEVICES,
        )
    }

    /**
     * The phases and the finish, over the owned staging directory. Everything before the
     * finish marker is cancellable; a non-cancellation failure reports [RestoreRunResult.Retry]
     * and keeps the request durable for the host's next run.
     */
    private suspend fun runStaged(
        sessionId: Long,
        request: RequestedRestore,
        staged: StagedBackup,
    ): RestoreRunResult =
        try {
            val policy =
                when (request.mode) {
                    RestoreMode.MERGE -> MergePolicy.RESTORE_MERGE
                    RestoreMode.REPLACE -> MergePolicy.RESTORE_REPLACE
                }
            val settled =
                merger.mergeLibrary(
                    staged,
                    policy,
                    request.categories,
                    captureWithBackupTimes = request.linkedMerge,
                    suppressCapture = request.suppressCapture,
                    progress = { done, total ->
                        progressFlow.value = RestoreProgress.Running(sessionId, PHASE_LIBRARY, done, total)
                    },
                )
            val episodeResult =
                episodes.mergeEpisodes(
                    staged,
                    settled,
                    policy,
                    request.categories,
                    episodeTotal = staged.episodeLines,
                    captureWithBackupTimes = request.linkedMerge,
                    suppressCapture = request.suppressCapture,
                    progress = { done, total ->
                        progressFlow.value = RestoreProgress.Running(sessionId, PHASE_EPISODES, done, total)
                    },
                )

            var settingsApplied = 0
            var settingsSkipped = 0
            if (RestoreCategory.SETTINGS in request.categories) {
                // 05 Restore while linked: synced keys take the Sync write ordering (the
                // capture carries the archive's `createdAt`), the sync address keys skip.
                val linkedAt = if (request.linkedMerge) staged.createdAtMs else null
                when (val applied = settingsMerge.apply(staged.settings(), linkedAt)) {
                    is Outcome.Failure -> {
                        return RestoreRunResult.Retry(sessionId, applied.error)
                    }

                    is Outcome.Success -> {
                        settingsApplied = applied.value.applied
                        settingsSkipped = applied.value.skipped
                    }
                }
            }

            // 05 step 8, reach ALL_DEVICES: 10's upload/prune steps run before the session
            // commits, so a failure there re-runs the whole (idempotent) restore.
            if (request.completeAllDevices) {
                when (val outcome = linkedSync.completeAllDevicesRestore()) {
                    is Outcome.Failure -> return RestoreRunResult.Retry(sessionId, outcome.error)
                    is Outcome.Success -> Unit
                }
            }

            if (!finish(sessionId, request.auto, staged.library(), settled, episodeResult.redownloadIds)) {
                // The session left PREVIEW (or vanished) before COMMITTED — restore writes
                // stand, the durable intent did not; the host's next run re-gates on it.
                Log.w(TAG) { "restore session $sessionId left PREVIEW before COMMITTED" }
                return RestoreRunResult.Retry(sessionId, BackupError.Storage)
            }
            progressFlow.value = RestoreProgress.Finished(sessionId, request.auto)
            RestoreRunResult.Completed(
                sessionId = sessionId,
                auto = request.auto,
                summary =
                    MergeSummary(
                        podcastsAdded = settled.summary.podcastsAdded,
                        podcastsMatched = settled.summary.podcastsMatched,
                        groupsAdded = settled.summary.groupsAdded,
                        groupsMatched = settled.summary.groupsMatched,
                        episodeLines = episodeResult.summary.episodeLines,
                        stubs = episodeResult.summary.stubs,
                        unmatched = episodeResult.summary.unmatched,
                        // Unknown-podcast lines + the codec's skipped lines both count (05).
                        skippedLines = episodeResult.summary.skippedLines + staged.skippedLines,
                    ),
                settingsApplied = settingsApplied,
                settingsSkipped = settingsSkipped,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, e) { "restore session $sessionId failed before COMMITTED" }
            RestoreRunResult.Retry(sessionId, BackupError.Storage)
        }

    /**
     * 05 step 8's finish, one [NonCancellable] unit like the import commit's: the
     * `import_item` rows (one per distinct staged podcast — inserted QUEUED, matched
     * ALREADY_SUBSCRIBED, credentials-missing AUTH_REQUIRED, all through the settled ids),
     * the `PREVIEW` → `COMMITTED` marker carrying `committedAt`/`attemptMarkerAt` and the
     * worker-owned `redownloadIds` merged into the latest options — then the effects the
     * committed state owes: the session's fetch work, a periodic reschedule, the channel
     * sweep and the snapshot poke. Auto-snapshot retirement precedes the marker so a failed
     * rename retains a request the host can retry. Cancellation surfacing
     * at the Room COMMIT still runs every effect (the CommitCancellationTest regression).
     */
    private suspend fun finish(
        sessionId: Long,
        auto: Boolean,
        library: LibraryV1,
        settled: LibraryMergeResult,
        redownloadIds: List<Long>,
    ): Boolean =
        withContext(NonCancellable) {
            val marked =
                db.withWriteTransaction {
                    val dao = db.importDao()
                    val session = dao.sessionById(sessionId) ?: return@withWriteTransaction false
                    if (session.state != ImportState.PREVIEW) return@withWriteTransaction false
                    val options =
                        session.optionsJson?.let(ImportOptionsJson::decodeOrNull)
                            ?: return@withWriteTransaction false
                    if (auto && !retireAutoSnapshot()) return@withWriteTransaction false

                    val items =
                        library.podcasts.distinctBy { it.key }.mapIndexedNotNull { ordinal, podcast ->
                            val podcastId =
                                settled.podcastIdByKey[podcast.key] ?: return@mapIndexedNotNull null
                            val row = db.podcastDao().byId(podcastId)
                            ImportItemEntity(
                                sessionId = sessionId,
                                ordinal = ordinal,
                                title = podcast.customTitle ?: podcast.title,
                                originalUrl = podcast.feedUrl,
                                normalizedUrl = podcast.key,
                                kind =
                                    if (podcast.source == SourceType.YOUTUBE_CHANNEL.name) {
                                        ImportItemKind.YOUTUBE
                                    } else {
                                        ImportItemKind.RSS
                                    },
                                groupNamesJson = "[]",
                                selected = false,
                                status =
                                    when {
                                        row?.needsCredentials == true -> {
                                            ImportItemStatus.AUTH_REQUIRED
                                        }

                                        podcastId in settled.insertedPodcastIds -> {
                                            ImportItemStatus.QUEUED
                                        }

                                        // Earlier attempts may have inserted it before cancellation.
                                        row?.status == PodcastStatus.PENDING_FIRST_FETCH && row.initialFetch -> {
                                            ImportItemStatus.QUEUED
                                        }

                                        else -> {
                                            ImportItemStatus.ALREADY_SUBSCRIBED
                                        }
                                    },
                                podcastId = podcastId,
                            )
                        }
                    dao.insertItems(items)
                    val now = clock.now()
                    dao.markCommitted(
                        sessionId,
                        ImportOptionsJson.encode(
                            options.copy(
                                committedAt = now,
                                attemptMarkerAt = now,
                                redownloadIds = redownloadIds,
                            ),
                        ),
                    ) > 0
                }
            if (!marked) return@withContext false

            suspendRunCatching { workScheduler.schedule(sessionId) }
                .onFailure { Log.w(TAG, it) { "import fetch scheduling failed for session $sessionId" } }
            suspendRunCatching { refresh.reschedulePeriodic() }
                .onFailure { Log.w(TAG, it) { "periodic reschedule failed after restore commit" } }
            suspendRunCatching { channels.sync() }
                .onFailure { Log.w(TAG, it) { "group channel sync failed after restore commit" } }
            suspendRunCatching { snapshots.requestSoon() }
                .onFailure { Log.w(TAG, it) { "snapshot scheduling failed after restore commit" } }
            true
        }

    /**
     * A rejected restore (05 step 1's "failure → session CANCELLED"): the durable request
     * ends — nothing was or will be restored — and progress reports the error. An `auto`
     * restore's Corrupt/NotABackup also retires the foreign snapshot so the
     * installation-ID guard stops protecting an unrestorable file; every other failure
     * (NewerFormat, TooLarge, Unsupported) keeps it.
     */
    private suspend fun failRestore(
        sessionId: Long,
        error: BackupError,
        auto: Boolean,
    ): RestoreRunResult {
        val cancelled =
            withContext(NonCancellable) {
                suspendRunCatching {
                    db.withWriteTransaction {
                        val dao = db.importDao()
                        val session = dao.sessionById(sessionId) ?: return@withWriteTransaction true
                        if (session.state != ImportState.PREVIEW) return@withWriteTransaction true
                        if (auto && (error is BackupError.Corrupt || error is BackupError.NotABackup) &&
                            !retireAutoSnapshot()
                        ) {
                            return@withWriteTransaction false
                        }
                        dao.setState(sessionId, ImportState.CANCELLED)
                        true
                    }
                }.onFailure { Log.w(TAG, it) { "restore session $sessionId could not be cancelled" } }
                    .getOrDefault(false)
            }
        if (!cancelled) return RestoreRunResult.Retry(sessionId, BackupError.Storage)
        progressFlow.value = RestoreProgress.Failed(sessionId, error)
        return RestoreRunResult.Failed(sessionId, error)
    }

    /**
     * The auto-snapshot's end state after an adopted restore (05 First-launch restore):
     * rename `files/backup/auto-snapshot.zip` to `restored-{epochMs}.zip` — the app-private
     * file the session's `adopt` copied from. A failed rename keeps the durable PREVIEW
     * request, so a host retries rather than permanently stranding the foreign-file guard.
     */
    private suspend fun retireAutoSnapshot(): Boolean =
        withContext(io) {
            suspendRunCatching {
                if (fileSystem.exists(autoSnapshot)) {
                    fileSystem.atomicMove(autoSnapshot, autoSnapshotDir / restoredSnapshotName(clock.now()))
                }
            }.onFailure { Log.w(TAG, it) { "auto-snapshot retirement failed" } }
                .isSuccess
        }

    /** What [gateLocked] read: the durable request, or why it isn't one. */
    private sealed interface Gate {
        data object NotPending : Gate

        data class Requested(
            val request: PendingRestore,
        ) : Gate
    }

    /** The persisted intent decoded out of the session row (`optionsJson` + `payloadPath`). */
    private class PendingRestore(
        val mode: RestoreMode,
        val categories: Set<RestoreCategory>,
        val payloadPath: String,
        /** `ImportOptions.fromAndroidBackup` — this session adopted the auto-snapshot. */
        val auto: Boolean,
        /** The stored reach; THIS_DEVICE unless a Replace claimed ALL_DEVICES. */
        val reach: LinkedRestoreReach,
    )

    /** The execution flags [PendingRestore.withActualLink] derives from the live link answer. */
    private class RequestedRestore(
        val mode: RestoreMode,
        val categories: Set<RestoreCategory>,
        val payloadPath: String,
        /** `ImportOptions.fromAndroidBackup` — this session adopted the auto-snapshot. */
        val auto: Boolean,
        /** A Merge on a linked device: `withApplying` + `captureAt` at the backup's times. */
        val linkedMerge: Boolean = false,
        /** A Replace whose writes run under `applying` without per-write captures. */
        val suppressCapture: Boolean = false,
        /** A THIS_DEVICE Replace: 10's unlink runs before the phases. */
        val unlinkFirst: Boolean = false,
        /** An ALL_DEVICES Replace: 10's upload/prune steps run in the finish. */
        val completeAllDevices: Boolean = false,
    )

    private companion object {
        const val TAG = "RestoreRunner"
        const val PHASE_VALIDATE = "validate"
        const val PHASE_LIBRARY = "library"
        const val PHASE_EPISODES = "episodes"
    }
}

/**
 * What one [RestoreRunner.run] did — the host's (worker's or lane's) terminal answer.
 */
internal sealed interface RestoreRunResult {
    /** The restore committed: items, markers, effects and [RestoreProgress.Finished] all landed. */
    data class Completed(
        val sessionId: Long,
        /** The session adopted `files/backup/auto-snapshot.zip` (05's `auto` flag). */
        val auto: Boolean,
        /** Library + episode counts; `skippedLines` covers codec-skipped and unknown-podcast lines. */
        val summary: MergeSummary,
        val settingsApplied: Int,
        val settingsSkipped: Int,
    ) : RestoreRunResult

    /** A rejected restore: the session is CANCELLED, [RestoreProgress.Failed] reported. */
    data class Failed(
        val sessionId: Long,
        val error: BackupError,
    ) : RestoreRunResult

    /**
     * A post-validation failure (storage, a settings write, a lost session): the durable
     * PREVIEW intent survives, so the host re-runs — every phase is replayable.
     */
    data class Retry(
        val sessionId: Long,
        val error: BackupError,
    ) : RestoreRunResult

    /**
     * No durable restore request (gone, wrong format/state, unrequested, or unreadable
     * options): nothing was written and no progress posted. The corrupt-options row stays
     * for a fixed build to re-read, like the session cleaner's rule.
     */
    data object NotPending : RestoreRunResult
}
