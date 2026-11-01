// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.DownloadEntryRow
import ch.lkmc.neutrodyne.core.model.DownloadState

internal class DownloadNotifications(
    private val context: Context,
    private val clock: Clock,
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Volatile private var titles = emptyMap<Long, String>()

    @Volatile private var waiting = 0

    fun queue(entries: List<DownloadEntryRow>) {
        titles = entries.associate { it.download.episodeId to it.episodeTitle }
        waiting = entries.count { it.download.state == DownloadState.QUEUED }
    }

    private var failureIds = emptySet<Long>()
    private var dismissedFailures = emptySet<Long>()
    private var storageWaiting = false
    private var foregroundWaiting = false

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ERROR_CHANNEL,
                context.getString(R.string.download_error_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun progress(
        active: Int = 0,
        downloaded: Long = 0,
        total: Long? = null,
        episodeTitle: String? = null,
    ): Notification {
        ensureChannels()
        val title =
            if (episodeTitle != null) {
                context.getString(R.string.download_episode, episodeTitle)
            } else if (active == 0) {
                context.getString(R.string.download_starting)
            } else {
                context.resources.getQuantityString(R.plurals.download_active, active, active)
            }
        val percent =
            if (total != null &&
                total > 0
            ) {
                (downloaded.toDouble() / total * PERCENT).toInt().coerceIn(0, PERCENT)
            } else {
                0
            }
        val bytes =
            android.text.format.Formatter
                .formatShortFileSize(context, downloaded)
        val text =
            if (active <= 1) {
                if (total == null) {
                    bytes
                } else {
                    context.getString(
                        R.string.download_bytes,
                        bytes,
                        android.text.format.Formatter
                            .formatShortFileSize(context, total),
                    )
                }
            } else {
                val pending = context.resources.getQuantityString(R.plurals.download_waiting, waiting, waiting)
                if (total == null) pending else context.getString(R.string.download_aggregate, percent, pending)
            }
        return base(CHANNEL, title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(PERCENT, percent, total == null || total <= 0)
            .addAction(0, context.getString(R.string.download_pause_all), action(DownloadActionReceiver.PAUSE_ALL))
            .build()
    }

    fun foregroundInfo(): ForegroundInfo {
        check(Build.VERSION.SDK_INT < 34) { "Download dataSync is forbidden on API34+" }
        return if (Build.VERSION.SDK_INT >=
            29
        ) {
            ForegroundInfo(PROGRESS_ID, progress(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, progress())
        }
    }

    fun reporter(post: (Notification, Long, Long?, Long) -> Unit): DrainReporter =
        object : DrainReporter {
            private var transferred = 0L

            @Synchronized override fun transferred(bytes: Long) {
                require(bytes >= 0)
                transferred += bytes.coerceAtMost(Long.MAX_VALUE - transferred)
            }

            private var active = emptySet<Long>()
            private var lastPost: Long? = null

            @Synchronized override fun active(episodeIds: Set<Long>) {
                active = episodeIds
            }

            @Synchronized override fun bytes(
                downloaded: Long,
                total: Long?,
            ) {
                val now = clock.elapsedRealtime()
                if (lastPost?.let { now - it < UPDATE_MS } == true) return
                lastPost = now
                post(
                    progress(
                        active.size,
                        downloaded,
                        total,
                        active.singleOrNull()?.let {
                            titles[it]
                        },
                    ),
                    downloaded,
                    total,
                    transferred,
                )
            }
        }

    @Synchronized fun failures(
        ids: Set<Long>,
        title: String,
        error: String,
    ) {
        val newFailure = (ids - failureIds).isNotEmpty()
        failureIds = ids
        if (newFailure) dismissedFailures = emptySet()
        if (ids.isEmpty()) {
            dismissedFailures = emptySet()
            manager.cancel(ERROR_ID)
            return
        }
        if (ids == dismissedFailures) return
        post(
            ERROR_ID,
            base(ERROR_CHANNEL, context.resources.getQuantityString(R.plurals.download_failed, ids.size, ids.size))
                .setContentText("$title: $error")
                .setDeleteIntent(action(DownloadActionReceiver.DISMISS_ERRORS))
                .addAction(
                    0,
                    context.getString(R.string.download_retry),
                    action(DownloadActionReceiver.RETRY_FAILED),
                ).build(),
        )
    }

    @Synchronized fun dismissErrors() {
        dismissedFailures = failureIds
        manager.cancel(ERROR_ID)
    }

    @Synchronized fun storage(waiting: Int) {
        if (waiting == 0) {
            storageWaiting = false
            manager.cancel(STORAGE_ID)
            return
        }
        if (storageWaiting) return
        storageWaiting = true
        val manage = Intent(StorageManager.ACTION_MANAGE_STORAGE).setPackage("com.android.settings")
        val intent =
            if (manage.resolveActivity(context.packageManager) !=
                null
            ) {
                PendingIntent.getActivity(context, STORAGE_ID, manage, FLAGS)
            } else {
                openDownloads()
            }
        post(
            STORAGE_ID,
            base(ERROR_CHANNEL, context.getString(R.string.download_storage_full))
                .setContentText(context.resources.getQuantityString(R.plurals.download_waiting, waiting, waiting))
                .addAction(0, context.getString(R.string.download_manage_storage), intent)
                .build(),
        )
    }

    @Synchronized fun needsForeground(waiting: Boolean) {
        if (!waiting) {
            foregroundWaiting = false
            manager.cancel(WAITING_ID)
            return
        }
        if (foregroundWaiting) return
        foregroundWaiting = true
        post(
            WAITING_ID,
            base(CHANNEL, context.getString(R.string.download_paused))
                .setContentText(context.getString(R.string.download_open_to_continue))
                .build(),
        )
    }

    private fun base(
        channel: String,
        title: String,
    ): NotificationCompat.Builder {
        ensureChannels()
        return NotificationCompat
            .Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(openDownloads())
            .setAutoCancel(true)
    }

    private fun post(
        id: Int,
        notification: Notification,
    ) {
        // The UIDT/FGS initial notification bypasses this optional-notification permission gate.
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(id, notification)
    }

    private fun openDownloads(): PendingIntent =
        PendingIntent.getActivity(
            context,
            PROGRESS_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse("neutrodyne://open/downloads"))
                .setClassName(context, "ch.lkmc.neutrodyne.InternalMainActivity"),
            FLAGS,
        )

    private fun action(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DownloadActionReceiver::class.java).setAction(action),
            FLAGS,
        )

    companion object {
        const val CHANNEL = "downloads"
        const val ERROR_CHANNEL = "download_errors"
        const val PROGRESS_ID = 2001
        const val ERROR_ID = 2002
        const val STORAGE_ID = 2003
        const val WAITING_ID = 2004
        private const val PERCENT = 100
        private const val UPDATE_MS = 1_000L
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
