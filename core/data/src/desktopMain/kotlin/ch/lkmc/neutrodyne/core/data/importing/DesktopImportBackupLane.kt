// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.backup.RestoreRunResult
import ch.lkmc.neutrodyne.core.data.backup.RestoreRunner
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ImportReportPoster
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * The `import-backup` lane (05 §7 desktop column, 11's lane table). Every run re-reads the
 * durable state instead of trusting pokes, in the runner's fixed order: the oldest pending
 * restore (`PREVIEW` backup session with `restoreRequestedAt` — `RestoreRunner` itself
 * serializes, one at a time), then the `COMMITTED`/`FETCHING` import sessions oldest first,
 * then the once-per-24-h `ImportSessionCleaner` pass (until `maintenance` takes it, M11b).
 *
 * The lane is mixed, not network-only: restores and cleanup are local work and must run
 * while offline. Fetches return early offline (05 §7 — the network-regained poke wakes the
 * lane again) and only burn `laneAttempts` for retries that ended while online, so offline
 * passes never exhaust the cap.
 *
 * Quit and cancellation: a cancelled run leaves the durable intents alone — the restore
 * request row survives for the next process' first tick, and a fetch's session stays
 * `COMMITTED`/`FETCHING` until it finishes or the repository commits `CANCELLED`.
 * `ImportRepositoryImpl.cancel` reaches the in-flight run through [LaneImportWorkScheduler]'s
 * tracked child job, so the runner stops at the next item boundary instead of waiting out a
 * whole feed round.
 */
@ContributesIntoSet(AppScope::class)
internal class DesktopImportBackupLane
    @Inject
    constructor(
        private val db: NeutrodyneDatabase,
        private val restores: RestoreRunner,
        private val fetches: ImportFetchRunner,
        private val cleaner: ImportSessionCleaner,
        private val work: LaneImportWorkScheduler,
        private val settings: SettingsRepository,
        private val network: NetworkMonitor,
        private val reports: ImportReportPoster,
    ) : JobLane {
        override val name: String = NAME

        override suspend fun run(now: Instant) {
            runPendingRestores()
            runPendingImports()
            cleanupIfDue(now)
        }

        /**
         * Oldest durable intent first (05 Restore arbitration: the oldest `restoreRequestedAt`),
         * one at a time. `Retry` stops the pass — the intent stays durable for the next tick —
         * while the terminal verdicts free the lane for the next request in the same run. The
         * scan is a snapshot: a row whose intent the runner itself re-reads as `NotPending`
         * would otherwise spin this pass forever. An undecodable `optionsJson` fails closed as
         * carrying intent ([hasRestoreIntent]); it sorts oldest so the runner's gate, not this
         * ordering, decides it.
         */
        private suspend fun runPendingRestores() {
            val pending =
                db
                    .importDao()
                    .backupPreviewSessions()
                    .filter { it.hasRestoreIntent() }
                    .sortedBy { it.restoreRequestedAt() }
            for (session in pending) {
                work.restoreStarted(session.id)
                val result =
                    try {
                        runTracked(session.id) { restores.run(session.id) }
                    } finally {
                        work.restoreFinished(session.id)
                    }
                // `null` is the scheduler-cancelled run; Retry parks the lane for its next
                // tick. Completed/Failed/NotPending all leave the row out of PREVIEW intent
                // (or never had it) — the next candidate goes on the same pass.
                if (result == null || result is RestoreRunResult.Retry) return
                if (result is RestoreRunResult.Failed) {
                    val auto = session.optionsJson?.let(ImportOptionsJson::decodeOrNull)?.fromAndroidBackup == true
                    suspendRunCatching { reports.postRestoreFailed(session.id, auto, result.error) }
                        .onFailure { Log.w(TAG, it) { "restore failure notice failed for session ${session.id}" } }
                }
            }
        }

        /**
         * `activeSessions()` (COMMITTED/FETCHING, oldest first), skipped whole while offline —
         * 05 §7: the lane "returns early" and the network-regained poke (the runner's
         * `NETWORK_LANES` set) wakes it again. A `RETRY` (busy engine, a failed run, a
         * mid-pass disconnect) parks the lane for the next tick; online retries bump
         * `laneAttempts`, which the runner's `MAX_LANE_ATTEMPTS` caps on the next pass.
         */
        private suspend fun runPendingImports() {
            if (!network.status.value.isConnected) return
            for (session in db.importDao().activeSessions()) {
                val result =
                    suspendRunCatching {
                        runTracked(session.id) {
                            fetches.run(session.id, session.laneAttempts(), RefreshRequest.NO_DEADLINE)
                        }
                    }.onFailure { Log.w(TAG, it) { "import session ${session.id} failed" } }
                        .getOrNull() ?: continue
                if (result == ImportFetchResult.RETRY) {
                    if (network.status.value.isConnected) bumpLaneAttempts(session.id)
                    return
                }
            }
        }

        /**
         * One runner call under a child job the scheduler can drop on `cancel` — `null` when
         * the run was stopped that way (its durable `CANCELLED` write wins on the next scan).
         * A lane-level cancel still propagates.
         */
        private suspend fun <T> runTracked(
            sessionId: Long,
            block: suspend () -> T,
        ): T? =
            supervisorScope {
                val run = async { block() }
                work.trackJob(sessionId, run)
                try {
                    run.await()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    null
                } finally {
                    work.untrackJob(sessionId, run)
                }
            }

        /** 05 §7: `laneAttempts` counts only retries that ended while the network was up. */
        private suspend fun bumpLaneAttempts(sessionId: Long) {
            suspendRunCatching {
                db.withWriteTransaction {
                    val dao = db.importDao()
                    val session = dao.sessionById(sessionId) ?: return@withWriteTransaction Unit
                    if (session.state != ImportState.COMMITTED && session.state != ImportState.FETCHING) {
                        return@withWriteTransaction Unit
                    }
                    val options =
                        session.optionsJson?.let(ImportOptionsJson::decodeOrNull)
                            ?: return@withWriteTransaction Unit
                    dao.setOptionsJson(
                        sessionId,
                        ImportOptionsJson.encode(options.copy(laneAttempts = options.laneAttempts + 1)),
                    )
                    Unit
                }
            }.onFailure { Log.w(TAG, it) { "laneAttempts write failed for session $sessionId" } }
        }

        /** Once per 24 h; a failed pass or stamp write simply retries on the next run. */
        private suspend fun cleanupIfDue(now: Instant) {
            val last =
                suspendRunCatching { settings.get(DesktopSettingKeys.LAST_IMPORT_CLEANUP_AT) }
                    .getOrNull() ?: return
            if (now.toEpochMilliseconds() - last < CLEANUP_INTERVAL_MS) return

            suspendRunCatching { cleaner.cleanExpired(now.toEpochMilliseconds()) }
                .onSuccess {
                    suspendRunCatching {
                        val saved = settings.set(DesktopSettingKeys.LAST_IMPORT_CLEANUP_AT, now.toEpochMilliseconds())
                        if (saved is Outcome.Failure) Log.w(TAG) { "cleanup timestamp write rejected" }
                    }.onFailure { Log.w(TAG, it) { "cleanup timestamp write failed" } }
                }.onFailure { Log.w(TAG, it) { "import session cleanup failed" } }
        }

        private fun ImportSessionEntity.laneAttempts(): Int =
            optionsJson?.let(ImportOptionsJson::decodeOrNull)?.laneAttempts ?: 0

        /** The durable intent stamp; an undecodable row counts as oldest (fails closed). */
        private fun ImportSessionEntity.restoreRequestedAt(): Long =
            optionsJson?.let(ImportOptionsJson::decodeOrNull)?.restoreRequestedAt ?: Long.MIN_VALUE

        internal companion object {
            const val TAG = "ImportBackupLane"
            const val NAME = "import-backup"

            /** 11's lane table: the interim cleanup runs once per 24 h. */
            const val CLEANUP_INTERVAL_MS = 24L * 3_600_000
        }
    }
