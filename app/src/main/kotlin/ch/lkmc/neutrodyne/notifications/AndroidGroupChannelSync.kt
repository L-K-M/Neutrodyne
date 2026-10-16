// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.notifications.NewEpisodeChannels.CHANNEL_GROUP
import ch.lkmc.neutrodyne.notifications.NewEpisodeChannels.GROUP_CHANNEL_PREFIX
import ch.lkmc.neutrodyne.notifications.NewEpisodeChannels.NOTIFICATION_ID
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AndroidGroupChannelSync(
    private val application: Application,
    private val db: NeutrodyneDatabase,
    // The repository calls this port after writes; resolve it only when a sweep needs undo tokens.
    private val groups: () -> GroupRepository,
) : GroupChannelSync {
    private val manager get() = application.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()

    override suspend fun sync() = mutex.withLock { reconcile() }

    private suspend fun reconcile() {
        NewEpisodeChannels.ensureChannels(application)
        val enabled = db.scopeSettingsDao().groupScopes().filter { it.notifyNewEpisodes == true }
        // Read groups before tokens: delete holds the undo mutex from its commit through token registration.
        val retained = enabled.mapTo(HashSet()) { it.uuid } + groups().pendingUndoUuids()
        for (group in enabled) {
            val id = GROUP_CHANNEL_PREFIX + group.uuid
            val channel =
                manager.getNotificationChannel(id)?.apply { name = group.name }
                    ?: NotificationChannel(id, group.name, NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { this.group = CHANNEL_GROUP }
            manager.createNotificationChannel(channel)
        }
        for (channel in manager.notificationChannels) {
            if (!channel.id.startsWith(GROUP_CHANNEL_PREFIX)) continue
            if (channel.id.removePrefix(GROUP_CHANNEL_PREFIX) in retained) continue

            deleteChannel(channel.id)
        }
    }

    override suspend fun onUuidChanged(
        oldUuid: String,
        newUuid: String,
    ) = mutex.withLock {
        if (oldUuid == newUuid) return@withLock

        val group = db.scopeSettingsDao().groupScopes().find { it.uuid == newUuid }
        val old = manager.getNotificationChannel(GROUP_CHANNEL_PREFIX + oldUuid)
        if (group?.notifyNewEpisodes == true && old != null) {
            NewEpisodeChannels.ensureChannels(application)
            manager.createNotificationChannel(
                NotificationChannel(GROUP_CHANNEL_PREFIX + newUuid, group.name, old.importance).apply {
                    this.group = CHANNEL_GROUP
                    setSound(old.sound, old.audioAttributes)
                    lightColor = old.lightColor
                    enableLights(old.shouldShowLights())
                    vibrationPattern = old.vibrationPattern
                    enableVibration(old.shouldVibrate())
                    setShowBadge(old.canShowBadge())
                },
            )
        }
        deleteChannel(GROUP_CHANNEL_PREFIX + oldUuid)
        reconcile()
    }

    // A sweep rechecks live groups and tokens before deleting and also covers missed expiry callbacks.
    override suspend fun onDeleted(uuid: String) = sync()

    private fun deleteChannel(id: String) {
        manager.cancel(id, NOTIFICATION_ID)
        manager.deleteNotificationChannel(id)
    }
}
