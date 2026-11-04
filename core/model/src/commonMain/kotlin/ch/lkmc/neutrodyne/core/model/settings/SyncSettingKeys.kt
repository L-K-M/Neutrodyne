// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/** 10's own keys (10 What syncs, Settings): the server address and the two device switches. */
object SyncSettingKeys {
    /** Portable and never synced: a restored install offers "Reconnect" (R7.9). */
    val SERVER_URL: SettingKey.Text = SettingKey.Text("sync.server_url", "", SettingsFile.PORTABLE)

    val USERNAME: SettingKey.Text = SettingKey.Text("sync.username", "", SettingsFile.PORTABLE)

    val DEVICE_NAME: SettingKey.Text = SettingKey.Text("sync.device_name", "", SettingsFile.DEVICE)

    /** "Sync playback settings" (PO-37); on by default. */
    val SYNC_PLAYBACK_SETTINGS: SettingKey.Bool =
        SettingKey.Bool("sync.sync_playback_settings", true, SettingsFile.DEVICE)

    /** "Share feed passwords" (R1.9); off by default, per device. */
    val SHARE_FEED_PASSWORDS: SettingKey.Bool =
        SettingKey.Bool("sync.share_feed_passwords", false, SettingsFile.DEVICE)

    val ALL: List<SettingKey<*>> =
        listOf(SERVER_URL, USERNAME, DEVICE_NAME, SYNC_PLAYBACK_SETTINGS, SHARE_FEED_PASSWORDS)
}
