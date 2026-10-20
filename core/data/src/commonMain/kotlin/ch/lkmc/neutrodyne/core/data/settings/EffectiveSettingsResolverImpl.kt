// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.settings

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastScopeRow
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class EffectiveSettingsResolverImpl(
    db: NeutrodyneDatabase,
    private val settings: SettingsRepository,
    private val capabilities: YouTubeCapabilitiesSource? = null,
) : EffectiveSettingsResolver {
    private val dao = db.scopeSettingsDao()

    override suspend fun playback(
        podcastId: Long,
        contextGroupId: Long?,
    ): EffectivePlayback =
        dao.podcastScopes(listOf(podcastId)).resolvePlayback(
            contextGroupId,
            settings.get(PlaybackSettingKeys.SPEED),
            settings.get(PlaybackSettingKeys.SKIP_SILENCE),
        )

    override fun observePlayback(
        podcastId: Long,
        contextGroupId: Long?,
    ): Flow<EffectivePlayback> =
        combine(
            dao.observePodcastScope(podcastId),
            settings.observe(PlaybackSettingKeys.SPEED),
            settings.observe(PlaybackSettingKeys.SKIP_SILENCE),
        ) { rows, speed, silence -> rows.resolvePlayback(contextGroupId, speed, silence) }.distinctUntilChanged()

    override suspend fun notifications(podcastIds: Collection<Long>): Map<Long, Effective<Boolean>> =
        podcastIds
            .distinct()
            .chunked(NOTIFICATION_BIND_CHUNK)
            .flatMap { dao.podcastScopes(it) }
            .resolve(settings.scopeDefaults())
            .mapValues { it.value.notification() }

    override suspend fun autoDownload(podcastIds: Collection<Long>): Map<Long, EffectiveAutoDownload> =
        dao.podcastScopes(podcastIds).resolveAutoDownload(
            settings.scopeDefaults(),
            capabilities?.capabilities?.value?.downloads == true,
            youtubeDefaults(),
        )

    override fun observeAutoDownload(): Flow<Map<Long, EffectiveAutoDownload>> =
        combine(
            combine(
                dao.observeAllPodcastScopes(),
                settings.observeScopeDefaults(),
                capabilities?.capabilities?.map { it.downloads }?.distinctUntilChanged()
                    ?: kotlinx.coroutines.flow.flowOf(false),
            ) { rows, defaults, downloads -> Triple(rows, defaults, downloads) },
            settings.observe(YouTubeSettingKeys.AUTO_DOWNLOAD),
            settings.observe(YouTubeSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST),
        ) { (rows, defaults, downloads), enabled, keep ->
            rows.resolveAutoDownload(defaults, downloads, enabled to keep)
        }.distinctUntilChanged()

    private suspend fun youtubeDefaults(): Pair<Boolean, Int> =
        settings.get(YouTubeSettingKeys.AUTO_DOWNLOAD) to
            settings.get(YouTubeSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST)

    private fun List<PodcastScopeRow>.resolveAutoDownload(
        defaults: SettingOverrides,
        downloads: Boolean,
        youtubeDefaults: Pair<Boolean, Int>,
    ): Map<Long, EffectiveAutoDownload> =
        groupBy { it.podcastId }.mapValues { (_, rows) ->
            if (rows.first().sourceType == SourceType.YOUTUBE_CHANNEL) {
                if (!downloads) {
                    EffectiveAutoDownload(
                        false,
                        youtubeDefaults.second,
                        requireNotNull(defaults.autoDownloadNetwork),
                        requireNotNull(defaults.autoDownloadRequireCharging),
                        requireNotNull(defaults.includeVideoInAutoDownload),
                        requireNotNull(defaults.deleteAfterPlayed),
                        SettingSource.NotSupported,
                    )
                } else {
                    rows
                        .resolvePodcast(
                            defaults.copy(
                                autoDownload = youtubeDefaults.first,
                                autoDownloadKeepLatest = youtubeDefaults.second,
                            ),
                        ).autoDownload()
                }
            } else {
                rows.resolvePodcast(defaults).autoDownload()
            }
        }

    override suspend fun refreshIntervals(): Map<Long, Effective<Int?>> =
        dao.allPodcastScopes().resolve(settings.scopeDefaults()).mapValues { it.value.refresh() }

    override fun observeNotifications(podcastIds: Collection<Long>): Flow<Map<Long, Effective<Boolean>>> =
        observe(dao.observePodcastScopes(podcastIds.toList()))
            .map { views ->
                views.mapValues { it.value.notification() }
            }.distinctUntilChanged()

    override fun observeRefreshIntervals(): Flow<Map<Long, Effective<Int?>>> =
        observe(dao.observeAllPodcastScopes())
            .map { views ->
                views.mapValues { it.value.refresh() }
            }.distinctUntilChanged()

    private fun observe(rows: Flow<List<PodcastScopeRow>>): Flow<Map<Long, ScopedSettingsView>> =
        combine(rows, settings.observeScopeDefaults()) { current, defaults -> current.resolve(defaults) }

    private fun List<PodcastScopeRow>.resolve(defaults: SettingOverrides): Map<Long, ScopedSettingsView> =
        groupBy { it.podcastId }.mapValues { it.value.resolvePodcast(defaults) }

    private fun ScopedSettingsView.notification(): Effective<Boolean> =
        Effective(requireNotNull(effective.notifyNewEpisodes), sources.getValue(SettingField.NOTIFY_NEW_EPISODES))

    private companion object {
        // The notifier resolves a whole run, which can exceed SQLite 3.18's variable limit.
        const val NOTIFICATION_BIND_CHUNK = 500
    }

    private fun ScopedSettingsView.refresh(): Effective<Int?> =
        Effective(effective.refreshIntervalMinutes, sources.getValue(SettingField.REFRESH_INTERVAL_MINUTES))
}
