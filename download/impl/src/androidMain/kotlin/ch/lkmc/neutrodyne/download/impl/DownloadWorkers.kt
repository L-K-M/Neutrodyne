// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.DownloadLane
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class DownloadLaneWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val scheduler: Lazy<AndroidDownloadScheduler>,
        private val runtime: Lazy<AndroidDownloadRuntime>,
        private val notifications: Lazy<DownloadNotifications>,
        private val visibility: DownloadAppVisibility,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        private var expeditedForeground = false
        internal var promote: suspend (ForegroundInfo) -> Unit = { setForeground(it) }

        @AssistedFactory interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): DownloadLaneWorker
        }

        override suspend fun doWork(): Result {
            val lane = AndroidDownloadWork.inputLane(inputData) ?: return Result.failure()
            val token =
                RunnerToken("work:$id:${clock.now()}:${java.util.UUID.randomUUID()}").also {
                    scheduler.value.workerStarted(it, lane)
                }
            val exit = scheduler.value.exit(lane)
            try {
                exit.register(token, setOf(lane))
                var foreground = lane == DownloadLane.MANUAL && expeditedForeground
                if (lane == DownloadLane.MANUAL && !foreground && Build.VERSION.SDK_INT < 34 &&
                    visibility.isVisible()
                ) {
                    foreground =
                        try {
                            promote(notifications.value.foregroundInfo())
                            true
                        } catch (
                            cancelled: CancellationException,
                        ) {
                            throw cancelled
                        } catch (_: IllegalStateException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                }
                val deadline = if (foreground) null else clock.elapsedRealtime() + SOFT_DEADLINE_MS
                runtime.value.reconcile()
                runtime.value.drain(
                    DrainRequest(
                        setOf(lane),
                        token,
                        deadline,
                        WORK_HORIZON_MS,
                        if (lane == DownloadLane.MANUAL) {
                            notifications.value.reporter { notification, _, _, _ ->
                                if (foreground) promoteNotification(notification)
                            }
                        } else {
                            SilentReporter
                        },
                        exit,
                        claimsAllowed = { lane == DownloadLane.AUTO || !scheduler.value.registry.uidtRunning() },
                        cancellationIntent = { StopIntent.RunnerStopped(stopReason) },
                    ),
                )
                return Result.success()
            } catch (cancelled: CancellationException) {
                runCatching { runtime.value.stop(token, StopIntent.RunnerStopped(stopReason)) }
                    .onFailure { Log.e("Downloads", it) { "Could not deliver runner stop" } }
                throw cancelled
            } catch (failure: Exception) {
                Log.e("Downloads", failure) { "Download lane failed" }
                return Result.retry()
            } finally {
                withContext(NonCancellable) { exit.unregister(token) }
            }
        }

        // Progress updates use the existing FGS notification, never start a new foreground service.
        private fun promoteNotification(notification: android.app.Notification) {
            applicationContext
                .getSystemService(android.app.NotificationManager::class.java)
                .notify(DownloadNotifications.PROGRESS_ID, notification)
        }

        override suspend fun getForegroundInfo(): ForegroundInfo {
            check(AndroidDownloadWork.inputLane(inputData) == DownloadLane.MANUAL) { "AUTO cannot run in foreground" }
            val info = notifications.value.foregroundInfo()
            expeditedForeground = true
            return info
        }

        internal companion object {
            private val SilentReporter =
                object : DrainReporter {
                    override fun active(episodeIds: Set<Long>) {}

                    override fun bytes(
                        downloaded: Long,
                        total: Long?,
                    ) {}
                }
            const val SOFT_DEADLINE_MS = 8 * 60_000L
            const val WORK_HORIZON_MS = 60_000L
        }
    }

class DownloadWakeWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val scheduler: Lazy<AndroidDownloadScheduler>,
    ) : CoroutineWorker(context, params) {
        @AssistedFactory interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): DownloadWakeWorker
        }

        override suspend fun doWork(): Result =
            withContext(NonCancellable) {
                try {
                    val lane = AndroidDownloadWork.inputLane(inputData) ?: return@withContext Result.failure()
                    scheduler.value.ensureScheduled(lane)
                    Result.success()
                } catch (failure: Exception) {
                    Log.e("Downloads", failure) { "Download wake failed" }
                    Result.retry()
                }
            }
    }

class DownloadReconcileWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val runtime: Lazy<AndroidDownloadRuntime>,
        private val scheduler: Lazy<AndroidDownloadScheduler>,
    ) : CoroutineWorker(context, params) {
        @AssistedFactory interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): DownloadReconcileWorker
        }

        override suspend fun doWork(): Result {
            try {
                runtime.value.startup()
                return Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("Downloads", failure) { "Download reconciliation failed" }
                return Result.retry()
            }
        }
    }
