// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import android.app.Application
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.backup.RestoreWorkData
import ch.lkmc.neutrodyne.core.data.backup.RestoreWorker
import com.google.common.util.concurrent.ListenableFuture
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit

/**
 * The Android `ImportWorkScheduler` (05 §6 step 4, §8 fix-ups, Restore arbitration): unique
 * work `import-{sessionId}` for the fetch lane — network-constrained, expedited on API ≥ 31
 * (the quota-fallback demotes to ordinary work instead of demanding a foreground-service
 * notification), linear 30 s backoff — and unique work `backup-restore` for restores.
 *
 * [scheduleRestore] owns 05's typed acknowledgment. WorkManager `KEEP` silently drops a new
 * request while an unfinished spec holds the name, and the enqueue `Operation` cannot say
 * which side won — so the request carries a `restore-session-{id}` tag and the stored
 * `WorkInfo` proves the answer: an unfinished (or already-succeeded) spec for *this* session
 * is `ENQUEUED`, a name held by a different session's spec is `ALREADY_RUNNING`, and anything
 * else — a vanished request, a failed/cancelled spec — throws so the caller keeps the durable
 * intent for start-up reconciliation.
 */
@SingleIn(AppScope::class)
internal class WorkManagerImportWorkScheduler
    @Inject
    constructor(
        private val application: Application,
    ) : ImportWorkScheduler {
        private val workManager get() = WorkManager.getInstance(application)

        override suspend fun schedule(
            sessionId: Long,
            policy: ImportWorkPolicy,
        ) {
            suspendRunCatching {
                workManager
                    .enqueueUniqueWork(
                        ImportWorkData.workName(sessionId),
                        policy.toExistingWorkPolicy(),
                        importRequest(sessionId),
                    ).await()
            }.onFailure { Log.w(TAG, it) { "fetch enqueue failed for session $sessionId" } }
        }

        override suspend fun cancel(sessionId: Long) {
            suspendRunCatching {
                workManager.cancelUniqueWork(ImportWorkData.workName(sessionId)).await()
            }.onFailure { Log.w(TAG, it) { "fetch cancel failed for session $sessionId" } }
        }

        override suspend fun hasRestoreWork(): Boolean =
            workManager
                .getWorkInfosForUniqueWork(RestoreWorkData.WORK_RESTORE)
                .awaitResult()
                .any { !it.state.isFinished }

        override suspend fun scheduleRestore(
            sessionId: Long,
            auto: Boolean,
        ): RestoreEnqueue {
            // A failed Operation is ambiguous — throw and keep the durable intent.
            workManager
                .enqueueUniqueWork(
                    RestoreWorkData.WORK_RESTORE,
                    ExistingWorkPolicy.KEEP,
                    restoreRequest(sessionId, auto),
                ).await()
            return restoreAdmission(sessionId)
        }

        /**
         * `RestoreWorker`'s follow-up (05's safe re-enqueue): `APPEND_OR_REPLACE` chains the
         * request behind the still-running spec — `KEEP` would drop it outright.
         */
        internal suspend fun enqueueRestoreFollowUp(
            sessionId: Long,
            auto: Boolean,
        ) {
            workManager
                .enqueueUniqueWork(
                    RestoreWorkData.WORK_RESTORE,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    restoreRequest(sessionId, auto),
                ).await()
        }

        /**
         * The admission proof behind [scheduleRestore]: which session the surviving spec(s)
         * belong to, from their `restore-session-{id}` tags.
         */
        private suspend fun restoreAdmission(sessionId: Long): RestoreEnqueue {
            val infos =
                workManager
                    .getWorkInfosForUniqueWork(RestoreWorkData.WORK_RESTORE)
                    .awaitResult()
            val sessionTag = RestoreWorkData.sessionTag(sessionId)
            val ours = infos.filter { sessionTag in it.tags }
            if (ours.any { !it.state.isFinished }) return RestoreEnqueue.ENQUEUED
            if (ours.any { it.state == WorkInfo.State.SUCCEEDED }) return RestoreEnqueue.ENQUEUED
            if (ours.isNotEmpty()) {
                throw IllegalStateException(
                    "restore spec for session $sessionId finished as ${ours.first().state} — admission unknown",
                )
            }
            // No spec of ours: KEEP dropped the request because another session's spec held the
            // name at enqueue time — or the request vanished entirely, which must not be
            // reported as a definitive decline.
            if (infos.isNotEmpty()) return RestoreEnqueue.ALREADY_RUNNING
            throw IllegalStateException("restore request for session $sessionId left no spec")
        }

        private fun ImportWorkPolicy.toExistingWorkPolicy(): ExistingWorkPolicy =
            when (this) {
                ImportWorkPolicy.KEEP -> ExistingWorkPolicy.KEEP
                ImportWorkPolicy.APPEND_OR_REPLACE -> ExistingWorkPolicy.APPEND_OR_REPLACE
            }

        private fun importRequest(sessionId: Long): OneTimeWorkRequest {
            val builder =
                OneTimeWorkRequestBuilder<ImportFetchWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    ).setBackoffCriteria(
                        BackoffPolicy.LINEAR,
                        ImportWorkData.IMPORT_BACKOFF_MS,
                        TimeUnit.MILLISECONDS,
                    ).setInputData(ImportWorkData.dataOf(sessionId))
                    .addTag(ImportWorkData.TAG_IMPORT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            return builder.build()
        }

        private fun restoreRequest(
            sessionId: Long,
            auto: Boolean,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<RestoreWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    RestoreWorkData.RESTORE_BACKOFF_MS,
                    TimeUnit.MILLISECONDS,
                ).setInputData(RestoreWorkData.dataOf(sessionId, auto))
                .addTag(RestoreWorkData.TAG_BACKUP)
                .addTag(RestoreWorkData.sessionTag(sessionId))
                .build()

        private suspend fun <T> ListenableFuture<T>.awaitResult(): T =
            suspendCancellableCoroutine { continuation ->
                // The listener runs only after completion, so get() cannot block the caller.
                addListener({ continuation.resumeWith(runCatching { get() }) }, { it.run() })
                continuation.invokeOnCancellation { cancel(true) }
            }

        private companion object {
            const val TAG = "ImportWorkScheduler"
        }
    }
