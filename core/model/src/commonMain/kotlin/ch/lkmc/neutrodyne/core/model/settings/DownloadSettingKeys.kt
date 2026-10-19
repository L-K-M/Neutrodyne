// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** M6a keys only. Unsynced; only manual policy is portable in backups (05). */
object DownloadSettingKeys {
    val MANUAL_METERED =
        SettingKey.Choice(
            "downloads.manual_metered",
            ManualMeteredPolicy.ASK,
            persistentListOf(ManualMeteredPolicy.ASK, ManualMeteredPolicy.ALWAYS, ManualMeteredPolicy.NEVER),
        )
    val ROOT_ID = SettingKey.Text("downloads.root_id", "ext:primary", SettingsFile.DEVICE)
    val NOTIFICATION_PROMPTED = SettingKey.Bool("downloads.notification_prompted", false, SettingsFile.DEVICE)

    /** Split at the first two separators: paths may themselves contain '|'. */
    val DEFERRED_DELETES = SettingKey.TextSet("downloads.deferred_deletes", persistentSetOf(), SettingsFile.DEVICE)
    val ALL: List<SettingKey<*>> = listOf(MANUAL_METERED, ROOT_ID, NOTIFICATION_PROMPTED, DEFERRED_DELETES)
}
