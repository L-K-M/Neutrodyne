// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.R
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.NewEpisodePoster
import ch.lkmc.neutrodyne.core.model.NewEpisodeLine
import ch.lkmc.neutrodyne.core.model.NewEpisodeNotice
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.notification_episode_line
import ch.lkmc.neutrodyne.core.ui.resources.notification_new_episodes
import ch.lkmc.neutrodyne.core.ui.resources.notification_other_podcasts
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/** Active notifications are the source of truth, including after process death or dismissal. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AndroidNewEpisodePoster(
    private val application: Application,
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
) : NewEpisodePoster {
    private val manager get() = application.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()

    override suspend fun post(notices: List<NewEpisodeNotice>) =
        mutex.withLock {
            if (notices.isEmpty()) return@withLock

            ensureChannels()
            val active = shown().associateBy { it.tag }
            for (notice in notices) {
                if (notice.episodes.isEmpty()) continue

                val channel = ensureGroupChannel(notice)
                if (!canPost(channel)) continue

                val previous =
                    active[channel]
                        ?.notification
                        ?.extras
                        ?.getLongArray(EPISODE_IDS) ?: longArrayOf()
                val episodes = loadLines(previous.toList() + notice.episodes.map { it.episodeId })
                if (episodes.isEmpty()) continue

                publish(notice.copy(episodes = episodes), Posting.ARRIVALS, clock.now())
            }
        }

    override suspend fun removePodcasts(podcastIds: List<Long>) =
        mutex.withLock {
            if (podcastIds.isEmpty()) return@withLock

            val removed = podcastIds.toSet()
            for (shown in shown()) {
                val notification = shown.notification
                val ids = notification.extras.getLongArray(EPISODE_IDS) ?: longArrayOf()
                val episodes = loadLines(ids.toList()).filterNot { it.podcastId in removed }
                if (episodes.isEmpty()) {
                    manager.cancel(shown.tag, NOTIFICATION_ID)
                    continue
                }
                if (episodes.map { it.episodeId } == ids.toList()) continue

                val uuid = notification.extras.getString(GROUP_UUID)
                val name = manager.getNotificationChannel(shown.tag)?.name?.toString()
                if (canPost(shown.tag)) {
                    if (!publish(NewEpisodeNotice(uuid, name, episodes), Posting.CLEANUP, notification.`when`)) {
                        manager.cancel(shown.tag, NOTIFICATION_ID)
                    }
                } else {
                    // A blocked channel cannot be updated; cancel rather than leave unsubscribed lines.
                    manager.cancel(shown.tag, NOTIFICATION_ID)
                }
            }
        }

    private fun shown() =
        manager.activeNotifications.filter {
            it.id == NOTIFICATION_ID && (it.tag == DEFAULT_CHANNEL || it.tag?.startsWith(GROUP_CHANNEL_PREFIX) == true)
        }

    private suspend fun loadLines(ids: List<Long>): List<NewEpisodeLine> =
        db.episodeDao().notificationRows(ids).distinctBy { it.episodeId }.take(MAX_EPISODES).map {
            NewEpisodeLine(it.episodeId, it.podcastId, it.podcastTitle, it.episodeTitle, it.artworkKey)
        }

    private fun ensureChannels() {
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(CHANNEL_GROUP, application.getString(R.string.channel_group_new_episodes)),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                DEFAULT_CHANNEL,
                application.getString(R.string.channel_new_episodes_default),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { group = CHANNEL_GROUP },
        )
    }

    private fun ensureGroupChannel(notice: NewEpisodeNotice): String {
        val uuid = notice.groupUuid ?: return DEFAULT_CHANNEL
        val id = GROUP_CHANNEL_PREFIX + uuid
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(
                NotificationChannel(id, requireNotNull(notice.groupName), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { group = CHANNEL_GROUP },
            )
        }
        return id
    }

    private fun canPost(channel: String): Boolean {
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

                manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE -> {
                    "channel blocked"
                }

                else -> {
                    return true
                }
            }
        Log.i(TAG) { "Skipped 1 new-episode notification: $reason" }
        return false
    }

    private suspend fun publish(
        notice: NewEpisodeNotice,
        posting: Posting,
        whenMs: Long,
    ): Boolean {
        val channel = notice.groupUuid?.let { GROUP_CHANNEL_PREFIX + it } ?: DEFAULT_CHANNEL
        val single = notice.episodes.singleOrNull()
        val builder =
            Notification
                .Builder(application, channel)
                .setSmallIcon(R.drawable.ic_stat_neutrodyne)
                .setAutoCancel(true)
                .setOnlyAlertOnce(posting == Posting.CLEANUP)
                .setWhen(whenMs)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentIntent(contentIntent(notice, channel))
                .addExtras(
                    Bundle().apply {
                        putLongArray(EPISODE_IDS, notice.episodes.map { it.episodeId }.toLongArray())
                        putString(GROUP_UUID, notice.groupUuid)
                    },
                )
        if (single != null) {
            builder.setContentTitle(single.podcastTitle).setContentText(single.episodeTitle)
        } else {
            val title =
                getPluralString(Res.plurals.notification_new_episodes, notice.episodes.size, notice.episodes.size)
            val style = Notification.InboxStyle().setBigContentTitle(title)
            for (episode in notice.episodes.take(MAX_INBOX_LINES)) {
                style.addLine(
                    getString(Res.string.notification_episode_line, episode.podcastTitle, episode.episodeTitle),
                )
            }
            builder
                .setContentTitle(title)
                .setSubText(notice.groupName ?: getString(Res.string.notification_other_podcasts))
                .setStyle(style)
        }

        try {
            manager.notify(channel, NOTIFICATION_ID, builder.build())
            return true
        } catch (error: SecurityException) {
            // Permission may be revoked after the check, while resources or rows are loading.
            Log.i(TAG) { "Skipped 1 new-episode notification: permission revoked" }
            return false
        }
    }

    private fun contentIntent(
        notice: NewEpisodeNotice,
        channel: String,
    ): PendingIntent {
        val route =
            notice.episodes.singleOrNull()?.let { "episode/${it.episodeId}" }
                ?: notice.groupUuid?.let { "group/$it" }
        val intent =
            Intent(application, MainActivity::class.java).apply {
                if (route == null) {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                } else {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("neutrodyne://open/$route")
                }
                // Categories participate in PendingIntent identity even when channel hashes collide.
                addCategory("ch.lkmc.neutrodyne.new_episodes.$channel")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        return PendingIntent.getActivity(
            application,
            channel.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private enum class Posting { ARRIVALS, CLEANUP }

    private companion object {
        const val TAG = "NewEpisodePoster"
        const val DEFAULT_CHANNEL = "new_episodes"
        const val GROUP_CHANNEL_PREFIX = "new_episodes_"
        const val CHANNEL_GROUP = "grp_new_episodes"
        const val NOTIFICATION_ID = 5000
        const val EPISODE_IDS = "nd.episode_ids"
        const val GROUP_UUID = "nd.group_uuid"
        const val MAX_EPISODES = 50
        const val MAX_INBOX_LINES = 6
    }
}
