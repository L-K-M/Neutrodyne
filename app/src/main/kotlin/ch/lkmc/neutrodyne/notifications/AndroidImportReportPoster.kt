// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.R
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.ImportReportPoster
import ch.lkmc.neutrodyne.core.domain.ImportScreenVisibility
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.notification_import_done
import ch.lkmc.neutrodyne.core.ui.resources.notification_import_done_title
import ch.lkmc.neutrodyne.core.ui.resources.notification_restore_failed
import ch.lkmc.neutrodyne.core.ui.resources.notification_restore_failed_auto
import ch.lkmc.neutrodyne.core.ui.resources.notification_restore_failed_title
import ch.lkmc.neutrodyne.core.ui.resources.notification_snapshot_needs_newer
import ch.lkmc.neutrodyne.core.ui.resources.notification_snapshot_pending
import ch.lkmc.neutrodyne.core.ui.resources.notification_snapshot_pending_title
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * The Android `ImportReportPoster` (05 §8, Restore): one narrow service for the
 * `import_backup` channel — the DONE report (ID 3001, tag `import-{sessionId}`, route
 * `neutrodyne://open/import/{id}`), the restore failure (ID 3003, same route for the
 * cancelled session's report) and the foreign-snapshot offer (ID 3004, posted by C5's
 * first-launch path, routed to Settings › Backup).
 *
 * Posting rules mirror `AndroidNewEpisodePoster`: `POST_NOTIFICATIONS` is only ever
 * self-checked — never requested — and a session's notice is suppressed while that session's
 * screen is the visible one ([ImportScreenVisibility] is the fact, not a foreground guess).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AndroidImportReportPoster(
    private val application: Application,
    private val visibility: ImportScreenVisibility,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : ImportReportPoster {
    private val manager get() = application.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()

    override suspend fun postDone(
        sessionId: Long,
        counts: Map<ImportItemStatus, Int>,
    ) = locked {
        if (visibility.visibleSessionId.value == sessionId) return@locked
        if (!canPost()) return@locked
        val added = counts[ImportItemStatus.SUBSCRIBED] ?: 0
        val attention = counts.filterKeys { it in ATTENTION_STATUSES }.values.sum()
        val text = getString(Res.string.notification_import_done, added, attention)
        post(
            ImportBackupChannels.ID_IMPORT_DONE,
            sessionTag(sessionId),
            getString(Res.string.notification_import_done_title),
            text,
            sessionIntent("import/$sessionId"),
        )
    }

    override suspend fun postRestoreFailed(
        sessionId: Long,
        auto: Boolean,
        error: BackupError,
    ) = locked {
        if (visibility.visibleSessionId.value == sessionId) return@locked
        if (!canPost()) return@locked
        val newer = error is BackupError.NewerFormat
        // 05 First-launch restore: an auto restore that hits NewerFormat keeps the snapshot
        // and answers on ID 3004 with the needs-newer wording; a manual failure keeps the
        // session report's id/route and shows the same truthful text.
        post(
            if (newer && auto) ImportBackupChannels.ID_FOREIGN_SNAPSHOT else ImportBackupChannels.ID_RESTORE_FAILED,
            if (newer && auto) FOREIGN_TAG else sessionTag(sessionId),
            getString(
                if (newer && auto) {
                    Res.string.notification_snapshot_pending_title
                } else {
                    Res.string.notification_restore_failed_title
                },
            ),
            getString(
                when {
                    newer -> Res.string.notification_snapshot_needs_newer
                    auto -> Res.string.notification_restore_failed_auto
                    else -> Res.string.notification_restore_failed
                },
            ),
            sessionIntent(if (newer && auto) "settings/backup" else "import/$sessionId"),
        )
    }

    override suspend fun postForeignSnapshotPending() =
        locked {
            if (!canPost()) return@locked
            post(
                ImportBackupChannels.ID_FOREIGN_SNAPSHOT,
                FOREIGN_TAG,
                getString(Res.string.notification_snapshot_pending_title),
                getString(Res.string.notification_snapshot_pending),
                sessionIntent("settings/backup"),
            )
        }

    private suspend fun locked(block: suspend () -> Unit) =
        withContext(io) {
            mutex.withLock {
                ImportBackupChannels.ensureChannels(application)
                block()
            }
        }

    /** Self-check only (permission, master switch, channel) — the notice is skipped, never asked for. */
    private fun canPost(): Boolean {
        val reason =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED -> {
                    "permission denied"
                }

                !manager.areNotificationsEnabled() -> {
                    "notifications disabled"
                }

                manager.getNotificationChannel(ImportBackupChannels.CHANNEL)?.importance ==
                    NotificationManager.IMPORTANCE_NONE -> {
                    "channel blocked"
                }

                else -> {
                    return true
                }
            }
        Log.i(TAG) { "Skipped import/backup notification: $reason" }
        return false
    }

    private fun post(
        id: Int,
        tag: String,
        title: String,
        text: String,
        contentIntent: PendingIntent,
    ) {
        val notification =
            Notification
                .Builder(application, ImportBackupChannels.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_neutrodyne)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(contentIntent)
                .build()
        try {
            manager.notify(tag, id, notification)
        } catch (error: SecurityException) {
            // Permission may be revoked between the check and the post.
            Log.i(TAG) { "Skipped import/backup notification: permission revoked" }
        }
    }

    private fun sessionIntent(route: String): PendingIntent =
        PendingIntent.getActivity(
            application,
            route.hashCode(),
            Intent(application, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = "neutrodyne://open/$route".toUri()
                addCategory("ch.lkmc.neutrodyne.import_backup.$route")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun sessionTag(sessionId: Long) = "import-$sessionId"

    private companion object {
        const val TAG = "ImportReportPoster"
        const val FOREIGN_TAG = "backup-foreign"

        /** Statuses 05 §8's "M need attention" tallies — problems the report lists. */
        val ATTENTION_STATUSES =
            setOf(
                ImportItemStatus.INVALID_URL,
                ImportItemStatus.NOT_A_FEED,
                ImportItemStatus.NO_MEDIA,
                ImportItemStatus.AUTH_REQUIRED,
                ImportItemStatus.GONE,
                ImportItemStatus.FETCH_FAILED,
                ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
            )
    }
}
