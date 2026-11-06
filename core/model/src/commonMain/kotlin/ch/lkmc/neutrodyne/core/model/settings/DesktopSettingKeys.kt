// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import kotlinx.collections.immutable.persistentListOf

/**
 * 11 Settings: the `desktop.*` keys. All device-local — never synced and part of no backup
 * (the `desktop.` prefix is device-only by registry rule); that includes the Settings ›
 * Desktop user choices, which travel with no device.
 */
object DesktopSettingKeys {
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

    val ALL: List<SettingKey<*>> =
        listOf(
            DOWNLOADS_DIR,
            CLOSE_BEHAVIOUR,
            START_AT_LOGIN,
            TRAY_HINT_SHOWN,
        )
}
