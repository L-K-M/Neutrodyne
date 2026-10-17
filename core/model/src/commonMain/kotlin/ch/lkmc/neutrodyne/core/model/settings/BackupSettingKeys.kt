// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.ExportLayout
import kotlinx.collections.immutable.persistentListOf

/**
 * 05 Settings: the `backup.*` keys M3 registers. The export-dialog memories and
 * `auto_snapshot_enabled` live in the portable `settings` file — they travel in backups
 * but never sync ("Export-dialog memories, snapshot and scheduled-backup keys stay per
 * device", 05 Sync classification). The snapshot bookkeeping and `installation_id` are
 * device-local and never travel: `installation_id` is the foreign-snapshot guard of 05
 * First-launch restore, so a copy on another device would mislabel its own snapshot.
 * `backup.scheduled_*` lands with M15.
 */
object BackupSettingKeys {
    /** Export dialog › layout (05; `Choice` of the export's ExportLayout). */
    val OPML_LAYOUT: SettingKey.Choice<ExportLayout> =
        SettingKey.Choice(
            name = "backup.opml_layout",
            default = ExportLayout.GROUPED,
            values = persistentListOf(ExportLayout.GROUPED, ExportLayout.FLAT),
        )

    /** Export dialog › "Include YouTube channels" (05). */
    val OPML_INCLUDE_YOUTUBE: SettingKey.Bool =
        SettingKey.Bool(name = "backup.opml_include_youtube", default = true)

    /**
     * Settings › Backup › "Include your library in Android backup" (05). Android only;
     * a restored value is stored and ignored on the desktop.
     */
    val AUTO_SNAPSHOT_ENABLED: SettingKey.Bool =
        SettingKey.Bool(name = "backup.auto_snapshot_enabled", default = true)

    /** UUID generated on first read; guards foreign snapshots (05 Snapshot production). */
    val INSTALLATION_ID: SettingKey.Text =
        SettingKey.Text(name = "backup.installation_id", default = "", file = SettingsFile.DEVICE)

    /** Last snapshot write time, byte size, drop level and error — the Backup status line. */
    val LAST_SNAPSHOT_AT: SettingKey.Int64 =
        SettingKey.Int64(name = "backup.last_snapshot_at", default = 0L, file = SettingsFile.DEVICE)

    val LAST_SNAPSHOT_BYTES: SettingKey.Int64 =
        SettingKey.Int64(name = "backup.last_snapshot_bytes", default = 0L, file = SettingsFile.DEVICE)

    val LAST_SNAPSHOT_LEVEL: SettingKey.Int32 =
        SettingKey.Int32(name = "backup.last_snapshot_level", default = 0, file = SettingsFile.DEVICE)

    val LAST_SNAPSHOT_ERROR: SettingKey.Text =
        SettingKey.Text(name = "backup.last_snapshot_error", default = "", file = SettingsFile.DEVICE)

    /** Settings › Backup "Last backup: …" (05). */
    val LAST_MANUAL_BACKUP_AT: SettingKey.Int64 =
        SettingKey.Int64(name = "backup.last_manual_backup_at", default = 0L, file = SettingsFile.DEVICE)

    val ALL: List<SettingKey<*>> =
        listOf(
            OPML_LAYOUT,
            OPML_INCLUDE_YOUTUBE,
            AUTO_SNAPSHOT_ENABLED,
            INSTALLATION_ID,
            LAST_SNAPSHOT_AT,
            LAST_SNAPSHOT_BYTES,
            LAST_SNAPSHOT_LEVEL,
            LAST_SNAPSHOT_ERROR,
            LAST_MANUAL_BACKUP_AT,
        )
}
