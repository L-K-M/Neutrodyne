// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * The sync server's own settings (10 Settings): the portable address and username, and the two
 * device switches. Implemented beside `SettingsRepository` in `:core:data`; `:sync:impl` reads
 * them through this port so it never touches DataStore directly.
 */
interface SyncServerAddress {
    suspend fun serverUrl(): String?

    suspend fun username(): String?

    /** This device's name as other devices see it; `null`/blank when never set (10 Link methods). */
    suspend fun deviceName(): String?

    suspend fun shareFeedPasswords(): Boolean

    suspend fun syncPlaybackSettings(): Boolean

    /** 10 Settings: the portable address and username a link writes, "Forget server" clears. */
    suspend fun setServerUrl(url: String?)

    suspend fun setUsername(username: String?)

    /** 10 Settings: the device-local switches the Settings › Sync screen writes. */
    suspend fun setShareFeedPasswords(enabled: Boolean)

    suspend fun setSyncPlaybackSettings(enabled: Boolean)
}
