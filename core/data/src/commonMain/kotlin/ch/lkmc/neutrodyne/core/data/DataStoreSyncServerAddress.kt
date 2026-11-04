// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.model.settings.SyncSettingKeys
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** [SyncServerAddress] over the two settings files (10 Settings). */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
internal class DataStoreSyncServerAddress(
    private val settings: SettingsRepository,
) : SyncServerAddress {
    override suspend fun serverUrl(): String? = settings.get(SyncSettingKeys.SERVER_URL).takeIf { it.isNotBlank() }

    override suspend fun username(): String? = settings.get(SyncSettingKeys.USERNAME).takeIf { it.isNotBlank() }

    override suspend fun shareFeedPasswords(): Boolean = settings.get(SyncSettingKeys.SHARE_FEED_PASSWORDS)

    override suspend fun syncPlaybackSettings(): Boolean = settings.get(SyncSettingKeys.SYNC_PLAYBACK_SETTINGS)
}
