// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoGroupChannels
import ch.lkmc.neutrodyne.core.domain.ScopeSettingsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** In-memory writes over seeded inheritance; fixtures supply group/global resolutions. */
class FakeScopeSettingsRepository(
    private val channelSync: GroupChannelSync = NoGroupChannels,
) : ScopeSettingsRepository {
    private data class Scope(
        val inherited: ScopedSettingsView,
        val ownSource: SettingSource,
        val own: SettingOverrides = SettingOverrides(),
    ) {
        fun view(): ScopedSettingsView =
            ScopedSettingsView(
                own,
                SettingOverrides(
                    (own.refreshIntervalMinutes ?: inherited.effective.refreshIntervalMinutes)?.takeIf { it > 0 },
                    own.notifyNewEpisodes ?: inherited.effective.notifyNewEpisodes,
                ),
                mapOf(
                    SettingField.REFRESH_INTERVAL_MINUTES to
                        if (own.refreshIntervalMinutes != null) {
                            ownSource
                        } else {
                            inherited.sources.getValue(SettingField.REFRESH_INTERVAL_MINUTES)
                        },
                    SettingField.NOTIFY_NEW_EPISODES to
                        if (own.notifyNewEpisodes != null) {
                            ownSource
                        } else {
                            inherited.sources.getValue(SettingField.NOTIFY_NEW_EPISODES)
                        },
                ),
            )
    }

    private val podcasts = MutableStateFlow<Map<Long, Scope>>(emptyMap())
    private val groups = MutableStateFlow<Map<Long, Scope>>(emptyMap())
    private val mutex = Mutex()

    fun seedPodcast(
        podcastId: Long,
        inherited: ScopedSettingsView = appDefaults(),
    ) {
        podcasts.value = podcasts.value + (podcastId to Scope(inherited, SettingSource.PodcastOverride))
    }

    fun seedGroup(
        groupId: Long,
        uuid: String,
        name: String,
        inherited: ScopedSettingsView = appDefaults(),
    ) {
        groups.value = groups.value + (groupId to Scope(inherited, SettingSource.Group(uuid, name)))
    }

    fun removePodcast(podcastId: Long) {
        podcasts.value = podcasts.value - podcastId
    }

    fun removeGroup(groupId: Long) {
        groups.value = groups.value - groupId
    }

    override fun observePodcast(podcastId: Long): Flow<ScopedSettingsView?> =
        podcasts.map { it[podcastId]?.view() }.distinctUntilChanged()

    override fun observeGroup(groupId: Long): Flow<ScopedSettingsView?> =
        groups.map { it[groupId]?.view() }.distinctUntilChanged()

    override suspend fun updatePodcast(
        podcastId: Long,
        change: (SettingOverrides) -> SettingOverrides,
    ): Outcome<Unit, SettingsError> = update(podcasts, podcastId, change)

    override suspend fun updateGroup(
        groupId: Long,
        change: (SettingOverrides) -> SettingOverrides,
    ): Outcome<Unit, SettingsError> =
        update(groups, groupId, change).also { outcome ->
            if (outcome is Outcome.Success) {
                withContext(NonCancellable) {
                    suspendRunCatching { channelSync.sync() }
                        .onFailure { Log.w("ScopeSettings", it) { "Group channel sweep failed after settings write" } }
                }
            }
        }

    private suspend fun update(
        rows: MutableStateFlow<Map<Long, Scope>>,
        id: Long,
        change: (SettingOverrides) -> SettingOverrides,
    ): Outcome<Unit, SettingsError> =
        mutex.withLock {
            val current = rows.value[id] ?: return@withLock Outcome.Success(Unit)
            val next = change(current.own)
            if (!next.hasSupportedRefreshInterval()) {
                return@withLock Outcome.Failure(SettingsError.OutOfRange("refreshIntervalMinutes"))
            }
            rows.value = rows.value + (id to current.copy(own = next))
            Outcome.Success(Unit)
        }

    private companion object {
        fun appDefaults(): ScopedSettingsView =
            ScopedSettingsView(
                SettingOverrides(),
                SettingOverrides(FeedsSettingKeys.DEFAULT_REFRESH_INTERVAL_MINUTES, false),
                SettingField.entries.associateWith { SettingSource.AppDefault },
            )
    }
}
