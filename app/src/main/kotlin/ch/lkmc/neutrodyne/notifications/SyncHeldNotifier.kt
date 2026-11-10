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
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.group_count
import ch.lkmc.neutrodyne.core.ui.resources.held_changes_message
import ch.lkmc.neutrodyne.core.ui.resources.held_changes_unknown_device
import ch.lkmc.neutrodyne.core.ui.resources.notification_sync_held_title
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * The `sync_held` channel and its stable notification id (10 Scheduling on Android, 08 Sync
 * notices): one notification while a held batch waits, posted beside the update/import/episode
 * ids — a tap opens Settings › Sync.
 */
internal object SyncHeldChannels {
    const val CHANNEL = "sync_held"
    const val ID = 4300
    const val TAP_REQUEST = 4300
    const val ROUTE = "neutrodyne://open/settings/sync"

    fun ensure(application: Application) {
        val manager = application.getSystemService(NotificationManager::class.java)
        val name = application.getString(R.string.channel_sync_held)
        val channel =
            manager.getNotificationChannel(CHANNEL)?.apply { this.name = name }
                ?: NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
    }
}

/** Channel creation at band 10 (01 start-up table), beside the other channel initializers. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class SyncHeldChannelsInitializer(
    private val application: Application,
) : AppInitializer {
    override val order: Int = 10

    override suspend fun run() = SyncHeldChannels.ensure(application)
}

/**
 * 10 Scheduling on Android and 08 Sync notices: a new HELD batch while linked posts one
 * notification on [SyncHeldChannels.CHANNEL] ("Sync is waiting for you", tap →
 * `neutrodyne://open/settings/sync`). The collection is the app's job — the injected scope owns
 * cancellation with the process lifetime. [controller] stays a thunk (01 DI rule 7): the
 * singletons are constructed before band 100 opens the database, so nothing may resolve the
 * controller at injection time.
 *
 * A batch counts as surfaced only once per link profile: `delivered` keys the profile
 * (server, account, device) plus the batch id, so a dismissed notification never re-alarms on
 * status churn, the same batch id under another link is a new notice, and a refused post
 * (permission denied, channel blocked) is not remembered as delivered.
 */
@ContributesIntoSet(AppScope::class)
@Inject
internal class SyncHeldNotifier(
    private val application: Application,
    private val controller: () -> SyncController,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : AppInitializer {
    override val order: Int = 230

    private val manager get() = application.getSystemService(NotificationManager::class.java)

    override suspend fun run() {
        scope.launch(io) {
            val sync = controller()
            // Confined to this coroutine: collectLatest serializes each emission, so a stale
            // observation can never post or cancel across a profile switch.
            val delivered = mutableSetOf<DeliveredKey>()
            combine(sync.status, sync.heldChanges, ::Pair).collectLatest { (status, held) ->
                if (status !is SyncStatus.Linked || held.isEmpty()) {
                    manager.cancel(SyncHeldChannels.ID)
                    return@collectLatest
                }
                val batch = held.firstOrNull { DeliveredKey(status, it) !in delivered }
                if (batch != null && post(batch)) {
                    delivered += DeliveredKey(status, batch)
                }
            }
        }
    }

    private suspend fun post(batch: MassChangePrompt): Boolean {
        if (!canPost()) return false
        SyncHeldChannels.ensure(application)
        val device = batch.deviceName ?: getString(Res.string.held_changes_unknown_device)
        val podcasts =
            getPluralString(Res.plurals.podcast_count, batch.podcastTitles.size, batch.podcastTitles.size)
        val groups = getPluralString(Res.plurals.group_count, batch.groupNames.size, batch.groupNames.size)
        val notification =
            Notification
                .Builder(application, SyncHeldChannels.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_neutrodyne)
                .setContentTitle(getString(Res.string.notification_sync_held_title))
                .setContentText(getString(Res.string.held_changes_message, device, podcasts, groups))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(syncSettingsIntent())
                .build()
        try {
            manager.notify(SyncHeldChannels.ID, notification)
            return true
        } catch (error: SecurityException) {
            // Permission may be revoked between the check and the post.
            Log.i(TAG) { "Skipped held-sync notification: permission revoked" }
            return false
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

                manager.getNotificationChannel(SyncHeldChannels.CHANNEL)?.importance ==
                    NotificationManager.IMPORTANCE_NONE -> {
                    "channel blocked"
                }

                else -> {
                    return true
                }
            }
        Log.i(TAG) { "Skipped held-sync notification: $reason" }
        return false
    }

    private fun syncSettingsIntent(): PendingIntent =
        PendingIntent.getActivity(
            application,
            SyncHeldChannels.TAP_REQUEST,
            Intent().setClassName(application, INTERNAL_MAIN_ACTIVITY).apply {
                action = Intent.ACTION_VIEW
                data = SyncHeldChannels.ROUTE.toUri()
                addCategory("ch.lkmc.neutrodyne.sync_held")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The link a batch surfaced under — volatile fields (`lastSyncAtMs`, `activity`) stay out. */
    private data class DeliveredKey(
        val serverUrl: String,
        val accountId: String,
        val deviceId: String,
        val batchId: Long,
    ) {
        constructor(status: SyncStatus.Linked, batch: MassChangePrompt) : this(
            status.serverUrl,
            status.accountId,
            status.deviceId,
            batch.id,
        )
    }

    private companion object {
        const val TAG = "SyncHeldNotifier"
    }
}
