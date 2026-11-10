// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.settings.SyncSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sync switch adapter must preserve the settings repository's failed-write outcome. */
class DataStoreSyncServerAddressTest {
    @Test
    fun passwordSharingFailurePreservesBothDirections() =
        runTest {
            for (before in listOf(false, true)) {
                val repository = FakeSettingsRepository()
                repository.set(SyncSettingKeys.SHARE_FEED_PASSWORDS, before)
                val address = DataStoreSyncServerAddress(repository)
                repository.failNextSet = SettingsError.WriteFailed

                val result: Any? = address.setShareFeedPasswords(!before)

                assertEquals<Any?>(Outcome.Failure(SettingsError.WriteFailed), result)
                assertEquals(before, address.shareFeedPasswords())
            }
        }

    @Test
    fun playbackSettingsFailurePreservesBothDirections() =
        runTest {
            for (before in listOf(false, true)) {
                val repository = FakeSettingsRepository()
                repository.set(SyncSettingKeys.SYNC_PLAYBACK_SETTINGS, before)
                val address = DataStoreSyncServerAddress(repository)
                repository.failNextSet = SettingsError.WriteFailed

                val result: Any? = address.setSyncPlaybackSettings(!before)

                assertEquals<Any?>(Outcome.Failure(SettingsError.WriteFailed), result)
                assertEquals(before, address.syncPlaybackSettings())
            }
        }

    /** "Forget server" clears the address pair in one batched write, never two resets. */
    @Test
    fun clearServerIdentityClearsBothInOneWrite() =
        runTest {
            val repository = FakeSettingsRepository()
            repository.set(SyncSettingKeys.SERVER_URL, "https://sync.example")
            repository.set(SyncSettingKeys.USERNAME, "alice")
            val address = DataStoreSyncServerAddress(repository)

            val result: Any? = address.clearServerIdentity()

            assertEquals<Any?>(Outcome.Success(Unit), result)
            assertEquals(null, address.serverUrl())
            assertEquals(null, address.username())
            assertEquals(
                listOf("resetAll(sync.server_url,sync.username)"),
                repository.calls.filter { it.startsWith("resetAll") },
            )
        }

    /** A failed batched write keeps both stored values — no partial forget. */
    @Test
    fun clearServerIdentityFailureKeepsBothValues() =
        runTest {
            val repository = FakeSettingsRepository()
            repository.set(SyncSettingKeys.SERVER_URL, "https://sync.example")
            repository.set(SyncSettingKeys.USERNAME, "alice")
            val address = DataStoreSyncServerAddress(repository)
            repository.failNextSet = SettingsError.WriteFailed

            val result: Any? = address.clearServerIdentity()

            assertEquals<Any?>(Outcome.Failure(SettingsError.WriteFailed), result)
            assertEquals("https://sync.example", address.serverUrl())
            assertEquals("alice", address.username())
        }
}
