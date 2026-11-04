// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.YouTubeAlertReceiver
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.youtube_alert_channel
import ch.lkmc.neutrodyne.core.ui.resources.youtube_alert_text
import ch.lkmc.neutrodyne.core.ui.resources.youtube_alert_title
import ch.lkmc.neutrodyne.core.ui.resources.youtube_try_now
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import java.text.DateFormat
import java.util.Date

/** M9a wording promises automatic retry. Runtime engine updates arrive in M9b. */
internal class YouTubeBreakerNotifier(
    private val app: Application,
    private val health: YouTubeHealth,
    private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 200

    override suspend fun run() {
        scope.launch {
            health.awaitLoaded()
            health.state
                .map { it.breaker to it.breakerOpenUntil }
                .distinctUntilChanged()
                .collect { (state, until) ->
                    val manager = app.getSystemService(NotificationManager::class.java)
                    if (state == BreakerState.CLOSED) manager.cancel(NOTIFICATION_ID)
                    if (state != BreakerState.OPEN || until == null) return@collect
                    if (Build.VERSION.SDK_INT >= 33 &&
                        app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        return@collect
                    }
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL,
                            getString(Res.string.youtube_alert_channel),
                            NotificationManager.IMPORTANCE_DEFAULT,
                        ),
                    )
                    val open =
                        PendingIntent.getActivity(
                            app,
                            NOTIFICATION_ID,
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("neutrodyne://open/settings/youtube"),
                                app,
                                MainActivity::class.java,
                            ),
                            FLAGS,
                        )
                    val retry =
                        PendingIntent.getBroadcast(
                            app,
                            NOTIFICATION_ID,
                            Intent(app, YouTubeAlertReceiver::class.java),
                            FLAGS,
                        )
                    val message =
                        getString(
                            Res.string.youtube_alert_text,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until)),
                        )
                    manager.notify(
                        NOTIFICATION_ID,
                        Notification
                            .Builder(app, CHANNEL)
                            .setSmallIcon(android.R.drawable.stat_notify_error)
                            .setContentTitle(getString(Res.string.youtube_alert_title))
                            .setContentText(message)
                            .setStyle(Notification.BigTextStyle().bigText(message))
                            .setContentIntent(open)
                            .addAction(
                                Notification.Action
                                    .Builder(
                                        null,
                                        getString(Res.string.youtube_try_now),
                                        retry,
                                    ).build(),
                            ).setOnlyAlertOnce(true)
                            .setAutoCancel(true)
                            .build(),
                    )
                }
        }
    }

    private companion object {
        const val CHANNEL = "alerts"
        const val NOTIFICATION_ID = 4100
        const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
