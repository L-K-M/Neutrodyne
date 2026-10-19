// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import ch.lkmc.neutrodyne.INTERNAL_MAIN_ACTIVITY
import ch.lkmc.neutrodyne.R
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.data.update.UpdateNotificationAdapter
import ch.lkmc.neutrodyne.core.data.update.UpdatePostResult
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

/** Background checks never request permission. The update card remains available when blocked. */
@ContributesBinding(AppScope::class)
@Inject
internal class UpdateNotifier(
    private val application: Application,
) : UpdateNotificationAdapter {
    private val manager get() = application.getSystemService(NotificationManager::class.java)

    override suspend fun post(info: UpdateInfo): UpdatePostResult {
        UpdateChannels.ensure(application)
        if (info.minSdk > Build.VERSION.SDK_INT || !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(UpdateChannels.CHANNEL).importance == NotificationManager.IMPORTANCE_NONE ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            )
        ) {
            return UpdatePostResult.UNAVAILABLE
        }

        val notification =
            Notification
                .Builder(application, UpdateChannels.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_neutrodyne)
                .setContentTitle(application.getString(R.string.notification_app_update_title, info.versionName))
                .setContentText(
                    info.notes
                        .lineSequence()
                        .firstOrNull()
                        .orEmpty(),
                ).setContentIntent(intent(UPDATES_ROUTE, TAP_REQUEST))
                .addAction(
                    Notification.Action
                        .Builder(
                            null,
                            application.getString(R.string.notification_app_update_open),
                            intent(RELEASE_ROUTE, RELEASE_REQUEST),
                        ).build(),
                ).setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        return try {
            manager.notify(UpdateChannels.ID, notification)
            UpdatePostResult.ACCEPTED
        } catch (_: SecurityException) {
            // Permission can change between the check and the binder call.
            UpdatePostResult.UNAVAILABLE
        }
    }

    override suspend fun cancel() = manager.cancel(UpdateChannels.ID)

    private fun intent(
        route: String,
        request: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            application,
            request,
            Intent(Intent.ACTION_VIEW, route.toUri())
                .setClassName(application, INTERNAL_MAIN_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val UPDATES_ROUTE = "neutrodyne://open/settings/updates"
        const val RELEASE_ROUTE = "$UPDATES_ROUTE/release"
        const val TAP_REQUEST = 4200
        const val RELEASE_REQUEST = 4201
    }
}

internal object UpdateChannels {
    const val CHANNEL = "updates"
    const val ID = 4200

    fun ensure(application: Application) {
        val manager = application.getSystemService(NotificationManager::class.java)
        val name = application.getString(R.string.channel_updates)
        val channel =
            manager.getNotificationChannel(CHANNEL)?.apply { this.name = name }
                ?: NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
    }
}

@ContributesIntoSet(AppScope::class)
@Inject
internal class UpdateChannelsInitializer(
    private val application: Application,
) : AppInitializer {
    override val order: Int = 10

    override suspend fun run() = UpdateChannels.ensure(application)
}
