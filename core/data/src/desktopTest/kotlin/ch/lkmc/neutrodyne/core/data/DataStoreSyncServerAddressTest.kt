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

    /** A stored name reads back, and a failed write preserves the previous value. */
    @Test
    fun deviceNameWriteFailurePreservesTheStoredValue() =
        runTest {
            val repository = FakeSettingsRepository()
            val address = DataStoreSyncServerAddress(repository)

            val stored: Any? = address.setDeviceName("Office")

            assertEquals<Any?>(Outcome.Success(Unit), stored)
            assertEquals("Office", address.deviceName())
            repository.failNextSet = SettingsError.WriteFailed
            val failed: Any? = address.setDeviceName("New")
            assertEquals<Any?>(Outcome.Failure(SettingsError.WriteFailed), failed)
            assertEquals("Office", address.deviceName())
        }

    /** `null` and blank names reset the setting, so the next link falls back to the default. */
    @Test
    fun blankDeviceNameResetsTheSetting() =
        runTest {
            val repository = FakeSettingsRepository()
            val address = DataStoreSyncServerAddress(repository)
            repository.set(SyncSettingKeys.DEVICE_NAME, "Office")

            val nullResult: Any? = address.setDeviceName(null)

            assertEquals<Any?>(Outcome.Success(Unit), nullResult)
            assertEquals(null, address.deviceName())

            repository.set(SyncSettingKeys.DEVICE_NAME, "Office")
            val blankResult: Any? = address.setDeviceName("  ")
            assertEquals<Any?>(Outcome.Success(Unit), blankResult)
            assertEquals(null, address.deviceName())
            assertEquals(
                listOf("reset(sync.device_name)", "reset(sync.device_name)"),
                repository.calls.filter { it.startsWith("reset(") },
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
