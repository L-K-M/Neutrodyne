// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.WorkManagerImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.hasRunnableRestoreIntent
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ImportReportPoster
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * The host behind unique work `backup-restore` (05 Restore): hands the session to
 * [RestoreRunner] under a child bound — an 8-minute overrun answers `Result.retry()` while an
 * external stop still propagates as cancellation and leaves the durable `PREVIEW` intent for
 * the next run.
 *
 * Terminal mapping: `Completed` and `NotPending` are plain successes (`NotPending` posts
 * nothing — there was no restore to report), `Failed` posts the restore-failure notice and
 * succeeds, `Retry` reschedules under the request's backoff. Before succeeding, the worker
 * re-probes `backupPreviewSessions` for a durable intent a killed or refused enqueue left
 * behind and re-enqueues it through `APPEND_OR_REPLACE` — `KEEP` would drop the follow-up
 * while this spec still owns the name.
 */
class RestoreWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val runner: RestoreRunner,
        private val workScheduler: WorkManagerImportWorkScheduler,
        private val reports: ImportReportPoster,
        private val db: NeutrodyneDatabase,
    ) : CoroutineWorker(context, params) {
        /** The `MetroWorkerFactory` entry point; `:app` module's generated graph code calls it. */
        @AssistedFactory
        interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): RestoreWorker
        }

        override suspend fun doWork(): Result {
            val sessionId = RestoreWorkData.sessionId(inputData) ?: return Result.success()
            val result =
                suspendRunCatching {
                    withTimeoutOrNull(BOUND_MS) { runner.run(sessionId) }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, error) { "restore run failed for session $sessionId" }
                    return Result.retry()
                }
                    // The bound stopped the run — replayable by the durable intent.
                    ?: return Result.retry()
            when (result) {
                is RestoreRunResult.Retry -> {
                    return Result.retry()
                }

                is RestoreRunResult.Failed -> {
                    suspendRunCatching {
                        reports.postRestoreFailed(sessionId, RestoreWorkData.auto(inputData), result.error)
                    }.onFailure { Log.w(TAG, it) { "restore failure notice failed for session $sessionId" } }
                }

                else -> {
                }
            }
            enqueueFollowUp()
            return Result.success()
        }

        /**
         * The follow-up re-probe (05's durable-intent contract): the runner consumes at most
         * one intent per run, so a leftover `restoreRequestedAt` row gets its own spec
         * appended behind this one instead of waiting for the next cold start.
         */
        private suspend fun enqueueFollowUp() {
            val next =
                suspendRunCatching {
                    db.importDao().backupPreviewSessions().firstOrNull { it.hasRunnableRestoreIntent() }
                }.getOrElse {
                    Log.w(TAG, it) { "restore follow-up probe failed" }
                    return
                } ?: return
            val auto =
                next.optionsJson?.let(ImportOptionsJson::decodeOrNull)?.fromAndroidBackup == true
            suspendRunCatching {
                workScheduler.enqueueRestoreFollowUp(next.id, auto)
            }.onFailure { Log.w(TAG, it) { "restore follow-up enqueue failed for session ${next.id}" } }
        }

        private companion object {
            const val TAG = "RestoreWorker"

            /** The host bound a run gets before it answers `Result.retry()` — replayable. */
            const val BOUND_MS = 8L * 60_000
        }
    }
