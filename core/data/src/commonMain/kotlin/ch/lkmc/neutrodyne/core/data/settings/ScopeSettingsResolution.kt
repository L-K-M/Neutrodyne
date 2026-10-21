// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.settings

import ch.lkmc.neutrodyne.core.database.GroupScopeRow
import ch.lkmc.neutrodyne.core.database.PodcastScopeRow
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.GroupPlaybackHint
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

internal fun SettingsRepository.observeScopeDefaults(): Flow<SettingOverrides> =
    combine(
        observe(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES),
        observe(FeedsSettingKeys.NOTIFY_NEW_EPISODES),
        observe(PlaybackSettingKeys.SPEED),
        observe(PlaybackSettingKeys.SKIP_SILENCE),
    ) { refresh, notify, speed, silence -> SettingOverrides(refresh, notify, speed, silence) }

internal suspend fun SettingsRepository.scopeDefaults(): SettingOverrides =
    SettingOverrides(
        get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES),
        get(FeedsSettingKeys.NOTIFY_NEW_EPISODES),
        get(PlaybackSettingKeys.SPEED),
        get(PlaybackSettingKeys.SKIP_SILENCE),
    )

internal fun List<PodcastScopeRow>.resolvePodcast(defaults: SettingOverrides): ScopedSettingsView {
    val own =
        first().let {
            SettingOverrides(it.podcastRefresh, it.podcastNotify, it.podcastSpeed.validSpeed(), it.podcastSkipSilence)
        }
    val groups =
        mapNotNull { row ->
            row.groupUuid?.let {
                GroupScopeRow(
                    it,
                    requireNotNull(row.groupName),
                    row.groupRefresh,
                    row.groupNotify,
                    row.groupSpeed.validSpeed(),
                    row.groupSkipSilence,
                    requireNotNull(row.groupId),
                )
            }
        }
    return resolveScope(own, SettingSource.PodcastOverride, groups, defaults).copy(
        groupHints =
            groups.filter { it.playbackSpeed != null || it.skipSilence != null }.map {
                GroupPlaybackHint(it.groupId, it.name, it.playbackSpeed, it.skipSilence)
            },
    )
}

internal fun GroupScopeRow.resolveGroup(defaults: SettingOverrides): ScopedSettingsView =
    resolveScope(
        SettingOverrides(refreshIntervalMinutes, notifyNewEpisodes, playbackSpeed.validSpeed(), skipSilence),
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
        effective =
            SettingOverrides(
                refresh?.takeIf { it > 0 },
                notify,
                own.playbackSpeed ?: defaults.playbackSpeed,
                own.skipSilence ?: defaults.skipSilence,
            ),
        sources =
            mapOf(
                SettingField.REFRESH_INTERVAL_MINUTES to source(own.refreshIntervalMinutes, ownSource, refreshGroups),
                SettingField.NOTIFY_NEW_EPISODES to source(own.notifyNewEpisodes, ownSource, notifyGroups),
                SettingField.PLAYBACK_SPEED to source(own.playbackSpeed, ownSource, emptyList()),
                SettingField.SKIP_SILENCE to source(own.skipSilence, ownSource, emptyList()),
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

/** The selected context group is deliberately independent of M2's merge over all groups. */
internal fun List<PodcastScopeRow>.resolvePlayback(
    contextGroupId: Long?,
    speed: Float,
    skipSilence: Boolean,
): EffectivePlayback {
    val podcast = firstOrNull()
    val group = firstOrNull { contextGroupId != null && it.groupId == contextGroupId }
    val groupSource = group?.let { SettingSource.Group(requireNotNull(it.groupUuid), requireNotNull(it.groupName)) }
    val ownSpeed = podcast?.podcastSpeed.validSpeed()
    val groupSpeed = group?.groupSpeed.validSpeed()
    return EffectivePlayback(
        speed =
            Effective(
                ownSpeed ?: groupSpeed ?: speed,
                when {
                    ownSpeed != null -> SettingSource.PodcastOverride
                    groupSpeed != null -> requireNotNull(groupSource)
                    else -> SettingSource.AppDefault
                },
            ),
        skipSilence =
            Effective(
                podcast?.podcastSkipSilence ?: group?.groupSkipSilence ?: skipSilence,
                when {
                    podcast?.podcastSkipSilence != null -> SettingSource.PodcastOverride
                    group?.groupSkipSilence != null -> requireNotNull(groupSource)
                    else -> SettingSource.AppDefault
                },
            ),
    )
}

private fun Float?.validSpeed(): Float? = this?.takeIf(PlaybackSettingKeys::isValidSpeed)
