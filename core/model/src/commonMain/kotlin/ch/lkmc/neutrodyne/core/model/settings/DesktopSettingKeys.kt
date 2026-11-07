// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import kotlinx.collections.immutable.persistentListOf

/**
 * 11 Settings: the `desktop.*` keys. All device-local — never synced and part of no backup
 * (the `desktop.` prefix is device-only by registry rule); that includes the Settings ›
 * Desktop user choices, which travel with no device.
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

    /** Settings › Desktop "When the window is closed" (11 Desktop settings). */
    val CLOSE_BEHAVIOUR: SettingKey.Choice<CloseBehaviour> =
        SettingKey.Choice(
            name = "desktop.close_behaviour",
            default = CloseBehaviour.QUIT_WHEN_IDLE,
            values = persistentListOf(CloseBehaviour.QUIT_WHEN_IDLE, CloseBehaviour.KEEP_RUNNING),
            file = SettingsFile.DEVICE,
        )

    /**
     * Settings › Desktop "Start at login" (11 Start at login). This records the user's intent;
     * the OS registration read back through `LoginItemRegistrar` is the truth the row shows.
     */
    val START_AT_LOGIN: SettingKey.Bool =
        SettingKey.Bool(name = "desktop.start_at_login", default = false, file = SettingsFile.DEVICE)

    /**
     * Whether the one-time "the desktop has no tray; the close iconifies instead" hint was
     * already shown (11 Window and tray behaviour, No tray available).
     */
    val TRAY_HINT_SHOWN: SettingKey.Bool =
        SettingKey.Bool(name = "desktop.tray_hint_shown", default = false, file = SettingsFile.DEVICE)

    /**
     * The in-app volume 0..1 (11 Menus: Volume up/down; Ctrl/Cmd+↑/↓). Device-local like every
     * `desktop.*` key; the Android app keeps the system volume.
     */
    val VOLUME: SettingKey.Float32 =
        SettingKey.Float32(name = "desktop.volume", default = 1.0f, file = SettingsFile.DEVICE)

    val ALL: List<SettingKey<*>> =
        listOf(
            LAST_IMPORT_CLEANUP_AT,
            DOWNLOADS_DIR,
            CLOSE_BEHAVIOUR,
            START_AT_LOGIN,
            TRAY_HINT_SHOWN,
            VOLUME,
        )
}
