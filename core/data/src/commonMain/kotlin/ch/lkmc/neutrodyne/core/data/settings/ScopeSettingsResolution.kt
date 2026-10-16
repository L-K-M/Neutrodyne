// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.settings

import ch.lkmc.neutrodyne.core.database.GroupScopeRow
import ch.lkmc.neutrodyne.core.database.PodcastScopeRow
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

internal fun SettingsRepository.observeScopeDefaults(): Flow<SettingOverrides> =
    combine(
        observe(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES),
        observe(FeedsSettingKeys.NOTIFY_NEW_EPISODES),
    ) { refresh, notify -> SettingOverrides(refresh, notify) }

internal suspend fun SettingsRepository.scopeDefaults(): SettingOverrides =
    SettingOverrides(get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES), get(FeedsSettingKeys.NOTIFY_NEW_EPISODES))

internal fun List<PodcastScopeRow>.resolvePodcast(defaults: SettingOverrides): ScopedSettingsView {
    val own = first().let { SettingOverrides(it.podcastRefresh, it.podcastNotify) }
    val groups =
        mapNotNull { row ->
            row.groupUuid?.let {
                GroupScopeRow(it, requireNotNull(row.groupName), row.groupRefresh, row.groupNotify)
            }
        }
    return resolveScope(own, SettingSource.PodcastOverride, groups, defaults)
}

internal fun GroupScopeRow.resolveGroup(defaults: SettingOverrides): ScopedSettingsView =
    resolveScope(
        SettingOverrides(refreshIntervalMinutes, notifyNewEpisodes),
        SettingSource.Group(uuid, name),
        emptyList(),
        defaults,
    )

private fun resolveScope(
    own: SettingOverrides,
    ownSource: SettingSource,
    groups: List<GroupScopeRow>,
    defaults: SettingOverrides,
): ScopedSettingsView {
    // Zero is infinity when merging groups, but remains an explicit Manual only override.
    val timed = groups.filter { (it.refreshIntervalMinutes ?: 0) > 0 }
    val minimum = timed.minOfOrNull { requireNotNull(it.refreshIntervalMinutes) }
    val refreshGroups =
        if (minimum == null) {
            groups.filter { it.refreshIntervalMinutes == 0 }
        } else {
            timed.filter { it.refreshIntervalMinutes == minimum }
        }
    val refresh =
        own.refreshIntervalMinutes ?: minimum ?: refreshGroups.firstOrNull()?.refreshIntervalMinutes
            ?: defaults.refreshIntervalMinutes
    val notifyGroups =
        groups.filter { it.notifyNewEpisodes == true }.ifEmpty {
            groups.filter { it.notifyNewEpisodes == false }
        }
    val notify =
        own.notifyNewEpisodes ?: notifyGroups.firstOrNull()?.notifyNewEpisodes
            ?: requireNotNull(defaults.notifyNewEpisodes)

    return ScopedSettingsView(
        own = own,
        effective = SettingOverrides(refresh?.takeIf { it > 0 }, notify),
        sources =
            mapOf(
                SettingField.REFRESH_INTERVAL_MINUTES to source(own.refreshIntervalMinutes, ownSource, refreshGroups),
                SettingField.NOTIFY_NEW_EPISODES to source(own.notifyNewEpisodes, ownSource, notifyGroups),
            ),
    )
}

private fun source(
    own: Any?,
    ownSource: SettingSource,
    deciding: List<GroupScopeRow>,
): SettingSource {
    if (own != null) return ownSource
    val sources = deciding.map { SettingSource.Group(it.uuid, it.name) }
    return when (sources.size) {
        0 -> SettingSource.AppDefault
        1 -> sources.single()
        else -> SettingSource.Groups(sources)
    }
}
