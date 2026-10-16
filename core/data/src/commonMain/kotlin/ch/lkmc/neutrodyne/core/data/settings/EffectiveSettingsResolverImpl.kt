// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.settings

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastScopeRow
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
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
) : EffectiveSettingsResolver {
    private val dao = db.scopeSettingsDao()

    override suspend fun notifications(podcastIds: Collection<Long>): Map<Long, Effective<Boolean>> =
        podcastIds
            .distinct()
            .chunked(NOTIFICATION_BIND_CHUNK)
            .flatMap { dao.podcastScopes(it) }
            .resolve(settings.scopeDefaults())
            .mapValues { it.value.notification() }

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
