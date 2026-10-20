// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** Device-local policies (PO-37): portable keys travel in backups, never in sync. */
object DownloadSettingKeys {
    val MANUAL_METERED =
        SettingKey.Choice(
            "downloads.manual_metered",
            ManualMeteredPolicy.ASK,
            persistentListOf(ManualMeteredPolicy.ASK, ManualMeteredPolicy.ALWAYS, ManualMeteredPolicy.NEVER),
        )
    val AUTO_DOWNLOAD = SettingKey.Bool("downloads.auto_download", false)
    val AUTO_DOWNLOAD_KEEP_LATEST = SettingKey.Int32("downloads.auto_download_keep_latest", 3)
    val AUTO_DOWNLOAD_NETWORK =
        SettingKey.Choice(
            "downloads.auto_download_network",
            NetworkPolicy.UNMETERED,
            persistentListOf(NetworkPolicy.UNMETERED, NetworkPolicy.ANY),
        )
    val AUTO_DOWNLOAD_REQUIRE_CHARGING = SettingKey.Bool("downloads.auto_download_require_charging", false)
    val AUTO_DOWNLOAD_INCLUDE_VIDEO = SettingKey.Bool("downloads.auto_download_include_video", false)
    val DELETE_AFTER_PLAYED =
        SettingKey.Choice(
            "downloads.delete_after_played",
            DeleteAfter.AFTER_24H,
            persistentListOf(DeleteAfter.IMMEDIATELY, DeleteAfter.AFTER_24H, DeleteAfter.NEVER),
        )
    val STORAGE_CAP_MB = SettingKey.Int32("downloads.storage_cap_mb", 0)
    val PLANNER_BASELINE_AT = SettingKey.Int64("downloads.planner_baseline_at", 0L, SettingsFile.DEVICE)
    val MOVE_TARGET = SettingKey.Text("downloads.move_target", "", SettingsFile.DEVICE)
    val CLEANUP_LAST_AT = SettingKey.Int64("downloads.cleanup_last_at", 0L, SettingsFile.DEVICE)
    val DESKTOP_ROOTS = SettingKey.TextSet("downloads.desktop_roots", persistentSetOf(), SettingsFile.DEVICE)

    val ROOT_ID = SettingKey.Text("downloads.root_id", "ext:primary", SettingsFile.DEVICE)
    val NOTIFICATION_PROMPTED = SettingKey.Bool("downloads.notification_prompted", false, SettingsFile.DEVICE)

    /** Split at the first two separators: paths may themselves contain '|'. */
    val DEFERRED_DELETES = SettingKey.TextSet("downloads.deferred_deletes", persistentSetOf(), SettingsFile.DEVICE)
    val ALL: List<SettingKey<*>> =
        listOf(
            MANUAL_METERED,
            AUTO_DOWNLOAD,
            AUTO_DOWNLOAD_KEEP_LATEST,
            AUTO_DOWNLOAD_NETWORK,
            AUTO_DOWNLOAD_REQUIRE_CHARGING,
            AUTO_DOWNLOAD_INCLUDE_VIDEO,
            DELETE_AFTER_PLAYED,
            STORAGE_CAP_MB,
            PLANNER_BASELINE_AT,
            MOVE_TARGET,
            CLEANUP_LAST_AT,
            DESKTOP_ROOTS,
            ROOT_ID,
            NOTIFICATION_PROMPTED,
            DEFERRED_DELETES,
        )

    val KEEP_LATEST_RANGE = 1..10
    val STORAGE_CAP_MB_VALUES: Set<Int> = setOf(0, 1_024, 2_048, 5_120, 10_240, 20_480)

    internal fun acceptsRange(
        key: SettingKey<*>,
        value: Any,
    ): Boolean =
        when (key) {
            AUTO_DOWNLOAD_KEEP_LATEST -> value is Int && value in KEEP_LATEST_RANGE
            STORAGE_CAP_MB -> value is Int && value in STORAGE_CAP_MB_VALUES
            PLANNER_BASELINE_AT, CLEANUP_LAST_AT -> value is Long && value >= 0L
            else -> true
        }
}
