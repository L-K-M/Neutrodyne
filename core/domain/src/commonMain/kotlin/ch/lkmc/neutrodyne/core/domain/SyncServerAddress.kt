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

    suspend fun shareFeedPasswords(): Boolean

    suspend fun syncPlaybackSettings(): Boolean
}
