// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
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

    /** One-shot storage failure, consumed by the next switch write. */
    var nextPreferenceError: SettingsError? = null

    /** Suspends after the durable value changes, before the write returns to its caller. */
    var afterShareWrite: (suspend (Boolean) -> Unit)? = null

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

    override suspend fun setShareFeedPasswords(enabled: Boolean): Outcome<Unit, SettingsError> {
        nextPreferenceError?.let {
            nextPreferenceError = null
            return Outcome.Failure(it)
        }
        writtenShareFeedPasswords += enabled
        storedShareFeedPasswords = enabled
        afterShareWrite?.invoke(enabled)
        return Outcome.Success(Unit)
    }

    override suspend fun setSyncPlaybackSettings(enabled: Boolean): Outcome<Unit, SettingsError> {
        nextPreferenceError?.let {
            nextPreferenceError = null
            return Outcome.Failure(it)
        }
        writtenSyncPlaybackSettings += enabled
        storedSyncPlaybackSettings = enabled
        return Outcome.Success(Unit)
    }
}
