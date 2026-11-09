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
}
