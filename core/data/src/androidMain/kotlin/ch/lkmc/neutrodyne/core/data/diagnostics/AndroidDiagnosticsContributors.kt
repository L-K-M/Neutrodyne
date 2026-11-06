// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.data.maintenance.DbMaintenanceScheduler
import ch.lkmc.neutrodyne.core.data.refresh.WorkManagerRefreshScheduler
import ch.lkmc.neutrodyne.core.domain.DiagnosticsContributor
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsLine
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSeverity
import com.google.common.util.concurrent.ListenableFuture
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Android BACKGROUND card (08): app-standby bucket and battery-optimisation state.
 * `isIgnoringBatteryOptimizations` is a read — 09's hard rule stands: diagnostics never
 * *requests* the exemption, it only reports it.
 */
@ContributesIntoSet(AppScope::class)
internal class AndroidBackgroundContributor
    @Inject
    constructor(
        private val application: Application,
        private val platformInfo: PlatformInfo,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.BACKGROUND

        override suspend fun collect(): List<DiagnosticsLine> {
            val lines = mutableListOf<DiagnosticsLine>()
            val usage = application.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usage != null && (platformInfo.androidSdkInt ?: 0) >= 28) {
                lines += DiagnosticsLine("standbyBucket", bucketName(usage.appStandbyBucket))
            }
            val power = application.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power != null) {
                lines +=
                    DiagnosticsLine(
                        "batteryOptimizationIgnored",
                        power.isIgnoringBatteryOptimizations(application.packageName).toString(),
                    )
            }
            return lines
        }

        private fun bucketName(bucket: Int): String =
            when (bucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
                UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
                else -> "bucket_$bucket"
            }
    }

/**
 * The Android JOBS card (08): every app's WorkManager work with state and stop reason —
 * the "why did my periodic work not run" answer.
 */
@ContributesIntoSet(AppScope::class)
internal class AndroidJobsContributor
    @Inject
    constructor(
        private val application: Application,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.JOBS

        override suspend fun collect(): List<DiagnosticsLine> {
            val workManager = WorkManager.getInstance(application)
            val infos =
                workManager
                    .getWorkInfosByTag(DbMaintenanceScheduler.TAG_MAINTENANCE)
                    .awaitResult() +
                    workManager.getWorkInfosByTag(WorkManagerRefreshScheduler.TAG_REFRESH).awaitResult()
            if (infos.isEmpty()) return listOf(DiagnosticsLine("jobs", "none"))
            return infos.map { info ->
                DiagnosticsLine(
                    info.tags.firstOrNull { it != WorkInfo::class.qualifiedName && !it.startsWith("ch.lkmc") }
                        ?: info.id.toString().take(8),
                    "${info.state} stop=${stopReasonName(info.stopReason)}",
                    if (info.state == WorkInfo.State.FAILED) DiagnosticsSeverity.PROBLEM else DiagnosticsSeverity.INFO,
                )
            }
        }

        private fun stopReasonName(reason: Int): String =
            when (reason) {
                WorkInfo.STOP_REASON_NOT_STOPPED -> "not_stopped"
                WorkInfo.STOP_REASON_UNKNOWN -> "unknown"
                WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled_by_app"
                WorkInfo.STOP_REASON_PREEMPT -> "preempt"
                WorkInfo.STOP_REASON_TIMEOUT -> "timeout"
                WorkInfo.STOP_REASON_DEVICE_STATE -> "device_state"
                WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery_not_low"
                WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "charging"
                WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "connectivity"
                WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "device_idle"
                WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage_not_low"
                WorkInfo.STOP_REASON_QUOTA -> "quota"
                WorkInfo.STOP_REASON_USER -> "user"
                WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system_processing"
                WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "launch_time_changed"
                else -> "reason_$reason"
            }

        /** `WorkManager` futures carry no coroutine adapter; the listener fires post-completion. */
        private suspend fun <T> ListenableFuture<T>.awaitResult(): T =
            suspendCancellableCoroutine { continuation ->
                addListener(
                    {
                        try {
                            continuation.resume(get())
                        } catch (throwable: Throwable) {
                            continuation.resumeWithException(throwable)
                        }
                    },
                    { it.run() },
                )
                continuation.invokeOnCancellation { cancel(true) }
            }
    }
