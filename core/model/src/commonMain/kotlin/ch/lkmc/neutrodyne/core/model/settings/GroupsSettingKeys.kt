// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.MediaFilter
import kotlinx.collections.immutable.persistentListOf

/** 05 Settings: virtual feed views sync; visits stay on this device. */
object GroupsSettingKeys {
    val SHOW_UNGROUPED_TAB = SettingKey.Bool("groups.show_ungrouped_tab", false, synced = true)
    val ALL_FILTER_FLAGS = SettingKey.Int32("groups.all_filter_flags", 0, synced = true)
    val ALL_MEDIA_FILTER =
        SettingKey.Choice(
            "groups.all_media_filter",
            MediaFilter.ALL,
            persistentListOf(MediaFilter.ALL, MediaFilter.AUDIO, MediaFilter.VIDEO),
            synced = true,
        )

    /** Zero is off; writers validate the supported day choices (05). */
    val ALL_HIDE_OLDER_THAN_DAYS = SettingKey.Int32("groups.all_hide_older_than_days", 0, synced = true)
    val UNGROUPED_FILTER_FLAGS = SettingKey.Int32("groups.ungrouped_filter_flags", 0, synced = true)
    val UNGROUPED_MEDIA_FILTER =
        SettingKey.Choice(
            "groups.ungrouped_media_filter",
            MediaFilter.ALL,
            persistentListOf(MediaFilter.ALL, MediaFilter.AUDIO, MediaFilter.VIDEO),
            synced = true,
        )
    val UNGROUPED_HIDE_OLDER_THAN_DAYS = SettingKey.Int32("groups.ungrouped_hide_older_than_days", 0, synced = true)

    /** Zero requests initialization to now on first read. */
    val ALL_LAST_VIEWED_AT = SettingKey.Int64("groups.all_last_viewed_at", 0L, SettingsFile.DEVICE)
    val UNGROUPED_LAST_VIEWED_AT = SettingKey.Int64("groups.ungrouped_last_viewed_at", 0L, SettingsFile.DEVICE)

    val ALL: List<SettingKey<*>> =
        listOf(
            SHOW_UNGROUPED_TAB,
            ALL_FILTER_FLAGS,
            ALL_MEDIA_FILTER,
            ALL_HIDE_OLDER_THAN_DAYS,
            UNGROUPED_FILTER_FLAGS,
            UNGROUPED_MEDIA_FILTER,
            UNGROUPED_HIDE_OLDER_THAN_DAYS,
            ALL_LAST_VIEWED_AT,
            UNGROUPED_LAST_VIEWED_AT,
        )
}
