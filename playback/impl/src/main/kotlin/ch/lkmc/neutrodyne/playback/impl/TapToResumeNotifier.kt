// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.session.PlaybackPendingIntentBuilder

/** A refused FGS start must leave audio paused even if posting the recovery action is denied. */
internal class TapToResumeNotifier(
    private val application: Application,
) {
    fun post(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        PlaybackChannels.create(application)
        val resume =
            PlaybackPendingIntentBuilder(application, Player.COMMAND_PLAY_PAUSE, NeutrodynePlaybackService::class.java)
                .setStartAsForegroundService(true)
                .setSessionId(NeutrodynePlaybackService.SESSION_ID)
                .build()
        val notification =
            NotificationCompat
                .Builder(application, PlaybackChannels.ALERTS)
                .setSmallIcon(
                    R.drawable.ic_stat_neutrodyne,
                ).setContentTitle(application.getString(R.string.playback_paused))
                .setContentText(application.getString(R.string.tap_to_resume))
                .setContentIntent(openPlayer(application))
                .addAction(0, application.getString(R.string.resume_playback), resume)
                .setAutoCancel(true)
                .build()
        application
            .getSystemService(
                NotificationManager::class.java,
            ).notify(PlaybackChannels.NOTIF_ID_TAP_TO_RESUME, notification)
        return true
    }

    fun cancel() {
        application.getSystemService(NotificationManager::class.java).cancel(PlaybackChannels.NOTIF_ID_TAP_TO_RESUME)
    }

    companion object {
        fun openPlayer(application: Application): PendingIntent =
            PendingIntent.getActivity(
                application,
                0,
                Intent(Intent.ACTION_VIEW, "neutrodyne://open/player".toUri())
                    .setClassName(application.packageName, "ch.lkmc.neutrodyne.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
