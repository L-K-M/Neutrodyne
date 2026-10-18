// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ch.lkmc.neutrodyne.R
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

/**
 * The `import_backup` channel and its stable notification IDs (05 §8, Restore): 3001 is the
 * import DONE report, 3002 the ready-to-review notice, 3003 the restore failure,
 * 3004 the foreign snapshot's pending offer.
 */
internal object ImportBackupChannels {
    const val CHANNEL = "import_backup"
    const val ID_IMPORT_DONE = 3001
    const val ID_IMPORT_READY = 3002
    const val ID_RESTORE_FAILED = 3003
    const val ID_FOREIGN_SNAPSHOT = 3004

    fun ensureChannels(application: Application) {
        val manager = application.getSystemService(NotificationManager::class.java)
        val name = application.getString(R.string.channel_import_backup)
        val channel =
            manager.getNotificationChannel(CHANNEL)?.apply { this.name = name }
                ?: NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
    }
}

/** Channel creation at band 10 (01 start-up table), beside `NewEpisodeChannelsInitializer`. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class ImportBackupChannelsInitializer(
    private val application: Application,
) : AppInitializer {
    override val order: Int = 10

    override suspend fun run() = ImportBackupChannels.ensureChannels(application)
}
