// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.settings

import ch.lkmc.neutrodyne.core.database.GroupScopeRow
import ch.lkmc.neutrodyne.core.database.PodcastScopeRow
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.GroupPlaybackHint
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

internal fun SettingsRepository.observeScopeDefaults(): Flow<SettingOverrides> =
    combine(
        combine(
            observe(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES),
            observe(FeedsSettingKeys.NOTIFY_NEW_EPISODES),
            observe(PlaybackSettingKeys.SPEED),
            observe(PlaybackSettingKeys.SKIP_SILENCE),
        ) { refresh, notify, speed, silence -> SettingOverrides(refresh, notify, speed, silence) },
        combine(
            observe(DownloadSettingKeys.AUTO_DOWNLOAD),
            observe(DownloadSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST),
            observe(DownloadSettingKeys.AUTO_DOWNLOAD_NETWORK),
            observe(DownloadSettingKeys.AUTO_DOWNLOAD_REQUIRE_CHARGING),
            observe(DownloadSettingKeys.AUTO_DOWNLOAD_INCLUDE_VIDEO),
        ) { enabled, keepLatest, network, charging, video ->
            SettingOverrides(
                autoDownload = enabled,
                autoDownloadKeepLatest = keepLatest,
                autoDownloadNetwork = network,
                autoDownloadRequireCharging = charging,
                includeVideoInAutoDownload = video,
            )
        },
        observe(DownloadSettingKeys.DELETE_AFTER_PLAYED),
    ) { basic, auto, delete ->
        basic.copy(
            autoDownload = auto.autoDownload,
            autoDownloadKeepLatest = auto.autoDownloadKeepLatest,
            autoDownloadNetwork = auto.autoDownloadNetwork,
            autoDownloadRequireCharging = auto.autoDownloadRequireCharging,
            includeVideoInAutoDownload = auto.includeVideoInAutoDownload,
            deleteAfterPlayed = delete,
        )
    }

internal suspend fun SettingsRepository.scopeDefaults(): SettingOverrides =
    SettingOverrides(
        get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES),
        get(FeedsSettingKeys.NOTIFY_NEW_EPISODES),
        get(PlaybackSettingKeys.SPEED),
        get(PlaybackSettingKeys.SKIP_SILENCE),
        get(DownloadSettingKeys.AUTO_DOWNLOAD),
        get(DownloadSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST),
        get(DownloadSettingKeys.AUTO_DOWNLOAD_NETWORK),
        get(DownloadSettingKeys.AUTO_DOWNLOAD_REQUIRE_CHARGING),
        get(DownloadSettingKeys.AUTO_DOWNLOAD_INCLUDE_VIDEO),
        get(DownloadSettingKeys.DELETE_AFTER_PLAYED),
    )

internal fun List<PodcastScopeRow>.resolvePodcast(defaults: SettingOverrides): ScopedSettingsView {
    val own =
        first().let {
            SettingOverrides(
                it.podcastRefresh,
                it.podcastNotify,
                it.podcastSpeed.validSpeed(),
                it.podcastSkipSilence,
                autoDownload = it.podcastAutoDownload,
                autoDownloadKeepLatest = it.podcastAutoDownloadKeepLatest,
                autoDownloadNetwork = it.podcastAutoDownloadNetwork,
                autoDownloadRequireCharging = it.podcastAutoDownloadRequireCharging,
                includeVideoInAutoDownload = it.podcastIncludeVideoInAutoDownload,
                deleteAfterPlayed = it.podcastDeleteAfterPlayed,
            )
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
                    autoDownload = row.groupAutoDownload,
                    autoDownloadKeepLatest = row.groupAutoDownloadKeepLatest,
                    autoDownloadNetwork = row.groupAutoDownloadNetwork,
                    autoDownloadRequireCharging = row.groupAutoDownloadRequireCharging,
                    includeVideoInAutoDownload = row.groupIncludeVideoInAutoDownload,
                    deleteAfterPlayed = row.groupDeleteAfterPlayed,
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
        SettingOverrides(
            refreshIntervalMinutes,
            notifyNewEpisodes,
            playbackSpeed.validSpeed(),
            skipSilence,
            autoDownload = autoDownload,
            autoDownloadKeepLatest = autoDownloadKeepLatest,
            autoDownloadNetwork = autoDownloadNetwork,
            autoDownloadRequireCharging = autoDownloadRequireCharging,
            includeVideoInAutoDownload = includeVideoInAutoDownload,
            deleteAfterPlayed = deleteAfterPlayed,
        ),
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

    val auto = resolveAuto(own, ownSource, groups, defaults)
    return ScopedSettingsView(
        own = own,
        effective =
            SettingOverrides(
                refresh?.takeIf { it > 0 },
                notify,
                own.playbackSpeed ?: defaults.playbackSpeed,
                own.skipSilence ?: defaults.skipSilence,
                autoDownload = auto.first.autoDownload,
                autoDownloadKeepLatest = auto.first.autoDownloadKeepLatest,
                autoDownloadNetwork = auto.first.autoDownloadNetwork,
                autoDownloadRequireCharging = auto.first.autoDownloadRequireCharging,
                includeVideoInAutoDownload = auto.first.includeVideoInAutoDownload,
                deleteAfterPlayed = auto.first.deleteAfterPlayed,
            ),
        sources =
            auto.second +
                mapOf(
                    SettingField.REFRESH_INTERVAL_MINUTES to
                        source(own.refreshIntervalMinutes, ownSource, refreshGroups),
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

/** D45: only enabled groups contribute dependent fields; deletion policy is independent. */
private fun resolveAuto(
    own: SettingOverrides,
    ownSource: SettingSource,
    groups: List<GroupScopeRow>,
    defaults: SettingOverrides,
): Pair<SettingOverrides, Map<SettingField, SettingSource>> {
    val enabled = groups.filter { it.autoDownload == true }
    val sources = mutableMapOf<SettingField, SettingSource>()

    fun <T> pick(
        field: SettingField,
        explicit: T?,
        fallback: T?,
        rows: List<GroupScopeRow>,
        value: (GroupScopeRow) -> T?,
        merge: (List<T>) -> T?,
    ): T? {
        val merged = merge(rows.mapNotNull(value))
        sources[field] = source(explicit, ownSource, rows.filter { value(it) != null && value(it) == merged })
        return explicit ?: merged ?: fallback
    }
    val result =
        SettingOverrides(
            autoDownload =
                pick(
                    SettingField.AUTO_DOWNLOAD,
                    own.autoDownload,
                    defaults.autoDownload,
                    groups,
                    { it.autoDownload },
                    { values -> values.takeIf { it.isNotEmpty() }?.any { it } },
                ),
            autoDownloadKeepLatest =
                pick(
                    SettingField.AUTO_DOWNLOAD_KEEP_LATEST,
                    own.autoDownloadKeepLatest,
                    defaults.autoDownloadKeepLatest,
                    enabled,
                    { it.autoDownloadKeepLatest },
                    { it.maxOrNull() },
                ),
            autoDownloadNetwork =
                pick(
                    SettingField.AUTO_DOWNLOAD_NETWORK,
                    own.autoDownloadNetwork,
                    defaults.autoDownloadNetwork,
                    enabled,
                    { it.autoDownloadNetwork },
                    { values ->
                        if (NetworkPolicy.UNMETERED in values) NetworkPolicy.UNMETERED else values.firstOrNull()
                    },
                ),
            autoDownloadRequireCharging =
                pick(
                    SettingField.AUTO_DOWNLOAD_REQUIRE_CHARGING,
                    own.autoDownloadRequireCharging,
                    defaults.autoDownloadRequireCharging,
                    enabled,
                    { it.autoDownloadRequireCharging },
                    { values ->
                        values.takeIf { it.isNotEmpty() }?.any { it }
                    },
                ),
            includeVideoInAutoDownload =
                pick(
                    SettingField.INCLUDE_VIDEO_IN_AUTO_DOWNLOAD,
                    own.includeVideoInAutoDownload,
                    defaults.includeVideoInAutoDownload,
                    enabled,
                    { it.includeVideoInAutoDownload },
                    { values ->
                        values.takeIf { it.isNotEmpty() }?.all { it }
                    },
                ),
            deleteAfterPlayed =
                pick(
                    SettingField.DELETE_AFTER_PLAYED,
                    own.deleteAfterPlayed,
                    defaults.deleteAfterPlayed,
                    groups,
                    { it.deleteAfterPlayed },
                    { values ->
                        listOf(DeleteAfter.NEVER, DeleteAfter.AFTER_24H, DeleteAfter.IMMEDIATELY)
                            .firstOrNull { it in values }
                    },
                ),
        )
    return result to sources
}

internal fun ScopedSettingsView.autoDownload(): EffectiveAutoDownload =
    EffectiveAutoDownload(
        requireNotNull(effective.autoDownload),
        requireNotNull(effective.autoDownloadKeepLatest),
        requireNotNull(effective.autoDownloadNetwork),
        requireNotNull(effective.autoDownloadRequireCharging),
        requireNotNull(effective.includeVideoInAutoDownload),
        requireNotNull(effective.deleteAfterPlayed),
        sources.getValue(SettingField.AUTO_DOWNLOAD),
    )
