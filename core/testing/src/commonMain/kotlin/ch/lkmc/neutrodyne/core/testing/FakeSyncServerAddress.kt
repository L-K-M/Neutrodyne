// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.SyncServerAddress

/**
 * A scripted [SyncServerAddress] for consumer tests: mutable fields instead of DataStore, with
 * the writes recorded so a test can assert what the controller persisted.
 */
class FakeSyncServerAddress(
    serverUrl: String? = null,
    username: String? = null,
    deviceName: String? = null,
) : SyncServerAddress {
    var serverUrl: String? = serverUrl
        private set
    var username: String? = username
        private set
    var deviceName: String? = deviceName
        private set

    private var storedShareFeedPasswords: Boolean = false
    private var storedSyncPlaybackSettings: Boolean = true

    val writtenShareFeedPasswords = mutableListOf<Boolean>()
    val writtenSyncPlaybackSettings = mutableListOf<Boolean>()
    val writtenServerUrls = mutableListOf<String?>()
    val writtenUsernames = mutableListOf<String?>()

    fun updateServerUrl(value: String?) {
        serverUrl = value
    }

    fun updateUsername(value: String?) {
        username = value
    }

    fun updateDeviceName(value: String?) {
        deviceName = value
    }

    fun updateShareFeedPasswords(value: Boolean) {
        storedShareFeedPasswords = value
    }

    fun updateSyncPlaybackSettings(value: Boolean) {
        storedSyncPlaybackSettings = value
    }

    override suspend fun serverUrl(): String? = serverUrl

    override suspend fun username(): String? = username

    override suspend fun deviceName(): String? = deviceName

    override suspend fun shareFeedPasswords(): Boolean = storedShareFeedPasswords

    override suspend fun syncPlaybackSettings(): Boolean = storedSyncPlaybackSettings

    override suspend fun setServerUrl(url: String?) {
        writtenServerUrls += url
        serverUrl = url
    }

    override suspend fun setUsername(username: String?) {
        writtenUsernames += username
        this.username = username
    }

    override suspend fun setShareFeedPasswords(enabled: Boolean) {
        writtenShareFeedPasswords += enabled
        storedShareFeedPasswords = enabled
    }

    override suspend fun setSyncPlaybackSettings(enabled: Boolean) {
        writtenSyncPlaybackSettings += enabled
        storedSyncPlaybackSettings = enabled
    }
}
