// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/**
 * 11 Settings: the `desktop.*` keys. All device-local engine bookkeeping — never user
 * choices, so they travel in no backup and sync nowhere (the `desktop.` prefix is
 * device-only by registry rule).
 */
object DesktopSettingKeys {
    /**
     * The `import-backup` lane's last `ImportSessionCleaner` pass (11's lane table: once per
     * 24 h until `maintenance` takes it over in M11b). Independent of the automatic-backup
     * setting — the desktop has none (D34).
     */
    val LAST_IMPORT_CLEANUP_AT: SettingKey.Int64 =
        SettingKey.Int64(name = "desktop.import_cleanup_at", default = 0L, file = SettingsFile.DEVICE)

    /** Empty text represents the default Downloads folder. */
    val DOWNLOADS_DIR = SettingKey.Text("desktop.downloads_dir", "", SettingsFile.DEVICE)

    val ALL: List<SettingKey<*>> =
        listOf(
            LAST_IMPORT_CLEANUP_AT,
            DOWNLOADS_DIR,
        )
}
