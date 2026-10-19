// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

@ContributesIntoSet(AppScope::class)
@Inject
internal class PlaybackChannels(
    private val application: Application,
) : AppInitializer {
    override val order: Int = 10

    override suspend fun run() {
        create(application)
    }

    companion object {
        const val PLAYBACK = "playback"
        const val ALERTS = "alerts"
        const val NOTIF_ID_PLAYBACK = 4000
        const val NOTIF_ID_TAP_TO_RESUME = 4001

        fun create(application: Application) {
            application.getSystemService(NotificationManager::class.java).createNotificationChannels(
                listOf(
                    NotificationChannel(
                        PLAYBACK,
                        application.getString(R.string.channel_playback),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                    NotificationChannel(
                        ALERTS,
                        application.getString(R.string.channel_alerts),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                ),
            )
        }
    }
}
