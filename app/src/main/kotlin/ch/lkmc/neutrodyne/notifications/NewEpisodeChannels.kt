// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import ch.lkmc.neutrodyne.R

/** Channel identities shared by posting and reconciliation (03, 05). */
internal object NewEpisodeChannels {
    const val DEFAULT_CHANNEL = "new_episodes"
    const val GROUP_CHANNEL_PREFIX = "new_episodes_"
    const val CHANNEL_GROUP = "grp_new_episodes"
    const val NOTIFICATION_ID = 5000

    fun ensureChannels(application: Application) {
        val manager = application.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(CHANNEL_GROUP, application.getString(R.string.channel_group_new_episodes)),
        )
        val name = application.getString(R.string.channel_new_episodes_default)
        val channel =
            manager.getNotificationChannel(DEFAULT_CHANNEL)?.apply { this.name = name }
                ?: NotificationChannel(DEFAULT_CHANNEL, name, NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { group = CHANNEL_GROUP }
        manager.createNotificationChannel(channel)
    }
}
