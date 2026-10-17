// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.settings

import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.ScopeSettingsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class ScopeSettingsRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsRepository,
    private val refresh: Provider<RefreshController>,
    private val channelSync: GroupChannelSync,
) : ScopeSettingsRepository {
    private val dao get() = db.scopeSettingsDao()

    override fun observePodcast(podcastId: Long): Flow<ScopedSettingsView?> =
        combine(dao.observePodcastScope(podcastId), settings.observeScopeDefaults()) { rows, defaults ->
            rows.takeIf { it.isNotEmpty() }?.resolvePodcast(defaults)
        }.distinctUntilChanged()

    override fun observeGroup(groupId: Long): Flow<ScopedSettingsView?> =
        combine(dao.observeGroupScope(groupId), settings.observeScopeDefaults()) { row, defaults ->
            row?.resolveGroup(defaults)
        }.distinctUntilChanged()

    override suspend fun updatePodcast(
        podcastId: Long,
        change: (SettingOverrides) -> SettingOverrides,
    ): Outcome<Unit, SettingsError> =
        write {
            if (db.podcastDao().byId(podcastId) == null) return@write Outcome.Success(false)
            val previous = dao.forPodcast(podcastId)?.o ?: ScopeOverrides()
            val next = change(previous.toModel())
            if (!next.hasSupportedRefreshInterval()) return@write invalidRefresh()
            val stored = previous.withM2Fields(next)
            if (stored == ScopeOverrides()) {
                dao.deletePodcast(podcastId)
            } else if (stored != previous) {
                dao.upsertPodcast(PodcastSettingsEntity(podcastId, stored))
            }
            Outcome.Success(previous.refreshIntervalMinutes != stored.refreshIntervalMinutes)
        }

    override suspend fun updateGroup(
        groupId: Long,
        change: (SettingOverrides) -> SettingOverrides,
    ): Outcome<Unit, SettingsError> =
        write(afterCommit = ::syncGroupChannels) {
            if (db.groupDao().groupById(groupId) == null) return@write Outcome.Success(false)
            val previous = dao.forGroup(groupId)?.o ?: ScopeOverrides()
            val next = change(previous.toModel())
            if (!next.hasSupportedRefreshInterval()) return@write invalidRefresh()
            val stored = previous.withM2Fields(next)
            if (stored == ScopeOverrides()) {
                dao.deleteGroup(groupId)
            } else if (stored != previous) {
                dao.upsertGroup(PodcastGroupSettingsEntity(groupId, stored))
            }
            Outcome.Success(previous.refreshIntervalMinutes != stored.refreshIntervalMinutes)
        }

    // Reconcile channels after committing and releasing the Room writer.
    private suspend fun syncGroupChannels() {
        suspendRunCatching { channelSync.sync() }
            .onFailure { Log.w("ScopeSettings", it) { "Group channel sweep failed after settings write" } }
    }

    /**
     * One write transaction, then the effects it owes: a reschedule when the interval changed and
     * [afterCommit]. Both finish with the commit as one unit even if the screen closes: Room
     * re-checks cancellation as a transaction returns, so a cancellable caller would drop them.
     */
    private suspend fun write(
        afterCommit: suspend () -> Unit = {},
        block: suspend () -> Outcome<Boolean, SettingsError>,
    ): Outcome<Unit, SettingsError> =
        withContext(NonCancellable) {
            val result: Outcome<Boolean, SettingsError> =
                try {
                    db.withWriteTransaction { block() }
                } catch (error: SQLiteException) {
                    Log.w("ScopeSettings", error) { "Scoped settings write failed" }
                    return@withContext Outcome.Failure(SettingsError.WriteFailed)
                }

            val intervalChanged =
                when (result) {
                    is Outcome.Failure -> return@withContext result
                    is Outcome.Success -> result.value
                }
            if (intervalChanged) {
                suspendRunCatching { refresh().reschedulePeriodic() }
                    .onFailure { Log.w("ScopeSettings", it) { "Post-write refresh reschedule failed" } }
            }
            afterCommit()

            Outcome.Success(Unit)
        }

    private fun ScopeOverrides.toModel(): SettingOverrides = SettingOverrides(refreshIntervalMinutes, notifyNewEpisodes)

    private fun ScopeOverrides.withM2Fields(value: SettingOverrides): ScopeOverrides =
        copy(refreshIntervalMinutes = value.refreshIntervalMinutes, notifyNewEpisodes = value.notifyNewEpisodes)

    private fun invalidRefresh(): Outcome<Nothing, SettingsError> =
        Outcome.Failure(SettingsError.OutOfRange("refreshIntervalMinutes"))
}
