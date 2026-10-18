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
import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler
import ch.lkmc.neutrodyne.core.model.PlaybackScopeWrite
import ch.lkmc.neutrodyne.core.model.PlaybackSettingChange
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.hasSupportedPlaybackSpeed
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
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
    private val snapshots: SnapshotScheduler,
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
            if (db.podcastDao().byId(podcastId) == null) return@write Outcome.Success(ScopeWrite.UNCHANGED)
            val previous = dao.forPodcast(podcastId)?.o ?: ScopeOverrides()
            val next = change(previous.toModel())
            if (!next.hasSupportedRefreshInterval()) return@write invalidRefresh()
            if (!next.hasSupportedPlaybackSpeed()) return@write invalidSpeed()
            val stored = previous.withEditableFields(next)
            if (stored == ScopeOverrides()) {
                dao.deletePodcast(podcastId)
            } else if (stored != previous) {
                dao.upsertPodcast(PodcastSettingsEntity(podcastId, stored))
            }
            Outcome.Success(
                ScopeWrite(
                    intervalChanged = previous.refreshIntervalMinutes != stored.refreshIntervalMinutes,
                    mutated = stored != previous,
                ),
            )
        }

    override suspend fun updateGroup(
        groupId: Long,
        change: (SettingOverrides) -> SettingOverrides,
    ): Outcome<Unit, SettingsError> =
        write(afterCommit = ::syncGroupChannels) {
            if (db.groupDao().groupById(groupId) == null) return@write Outcome.Success(ScopeWrite.UNCHANGED)
            val previous = dao.forGroup(groupId)?.o ?: ScopeOverrides()
            val next = change(previous.toModel())
            if (!next.hasSupportedRefreshInterval()) return@write invalidRefresh()
            if (!next.hasSupportedPlaybackSpeed()) return@write invalidSpeed()
            val stored = previous.withEditableFields(next)
            if (stored == ScopeOverrides()) {
                dao.deleteGroup(groupId)
            } else if (stored != previous) {
                dao.upsertGroup(PodcastGroupSettingsEntity(groupId, stored))
            }
            Outcome.Success(
                ScopeWrite(
                    intervalChanged = previous.refreshIntervalMinutes != stored.refreshIntervalMinutes,
                    mutated = stored != previous,
                ),
            )
        }

    override suspend fun writePlayback(
        podcastId: Long,
        contextGroupId: Long?,
        scope: SettingScope,
        change: PlaybackSettingChange,
    ): Outcome<PlaybackScopeWrite, SettingsError> =
        withContext(NonCancellable) {
            if (change is PlaybackSettingChange.Speed && !PlaybackSettingKeys.isValidSpeed(change.value)) {
                return@withContext invalidSpeed()
            }

            var mutated = false
            val result: PlaybackScopeWrite =
                try {
                    db.withWriteTransaction {
                        val rows = dao.podcastScopes(listOf(podcastId))
                        if (rows.isEmpty()) return@withWriteTransaction PlaybackScopeWrite.MISSING_PODCAST
                        val groupId = rows.mapNotNull { it.groupId }.firstOrNull { it == contextGroupId }
                        if (scope == SettingScope.GROUP && groupId == null) {
                            return@withWriteTransaction PlaybackScopeWrite.NO_CONTEXT_GROUP
                        }

                        val previous = dao.forPodcast(podcastId)?.o ?: ScopeOverrides()
                        val podcastEdit = if (scope == SettingScope.PODCAST) OverrideEdit.SET else OverrideEdit.CLEAR
                        val next = previous.withPlayback(change, podcastEdit)
                        mutated = savePodcast(podcastId, previous, next)
                        if (scope != SettingScope.PODCAST && groupId != null) {
                            val previousGroup = dao.forGroup(groupId)?.o ?: ScopeOverrides()
                            val groupEdit = if (scope == SettingScope.GROUP) OverrideEdit.SET else OverrideEdit.CLEAR
                            val nextGroup = previousGroup.withPlayback(change, groupEdit)
                            mutated = saveGroup(groupId, previousGroup, nextGroup) || mutated
                        }
                        if (scope == SettingScope.GLOBAL) {
                            // Run clears first so SQL faults cannot change the global. A store failure
                            // throws through Room so it rolls back every scoped clear as well.
                            val global =
                                when (change) {
                                    is PlaybackSettingChange.Speed -> {
                                        settings.set(PlaybackSettingKeys.SPEED, change.value)
                                    }

                                    is PlaybackSettingChange.SkipSilence -> {
                                        settings.set(PlaybackSettingKeys.SKIP_SILENCE, change.value)
                                    }
                                }
                            if (global is Outcome.Failure) throw PlaybackWriteFailure(global.error)
                            mutated = true
                        }
                        PlaybackScopeWrite.APPLIED
                    }
                } catch (error: PlaybackWriteFailure) {
                    return@withContext Outcome.Failure(error.error)
                } catch (error: SQLiteException) {
                    Log.w("ScopeSettings", error) { "Playback scope write failed" }
                    return@withContext Outcome.Failure(SettingsError.WriteFailed)
                }
            if (mutated) {
                suspendRunCatching { snapshots.requestSoon() }
                    .onFailure { error ->
                        Log.w("ScopeSettings", error) { "Snapshot request failed after playback settings write" }
                    }
            }
            Outcome.Success(result)
        }

    private suspend fun savePodcast(
        id: Long,
        previous: ScopeOverrides,
        next: ScopeOverrides,
    ): Boolean {
        if (next == previous) return false
        if (next == ScopeOverrides()) dao.deletePodcast(id) else dao.upsertPodcast(PodcastSettingsEntity(id, next))
        return true
    }

    private suspend fun saveGroup(
        id: Long,
        previous: ScopeOverrides,
        next: ScopeOverrides,
    ): Boolean {
        if (next == previous) return false
        if (next == ScopeOverrides()) dao.deleteGroup(id) else dao.upsertGroup(PodcastGroupSettingsEntity(id, next))
        return true
    }

    private enum class OverrideEdit { SET, CLEAR }

    private fun ScopeOverrides.withPlayback(
        change: PlaybackSettingChange,
        edit: OverrideEdit,
    ): ScopeOverrides =
        when (change) {
            is PlaybackSettingChange.Speed -> copy(playbackSpeed = change.value.takeIf { edit == OverrideEdit.SET })
            is PlaybackSettingChange.SkipSilence -> copy(skipSilence = change.value.takeIf { edit == OverrideEdit.SET })
        }

    private class PlaybackWriteFailure(
        val error: SettingsError,
    ) : RuntimeException()

    // Reconcile channels after committing and releasing the Room writer.
    private suspend fun syncGroupChannels() {
        suspendRunCatching { channelSync.sync() }
            .onFailure { Log.w("ScopeSettings", it) { "Group channel sweep failed after settings write" } }
    }

    /**
     * One write transaction, then the effects it owes: a reschedule when the interval changed, the
     * snapshot poke when the row actually moved (05 Writing overrides — a missing scope or an
     * unchanged row writes nothing) and [afterCommit]. All finish with the commit as one unit even
     * if the screen closes: Room re-checks cancellation as a transaction returns, so a cancellable
     * caller would drop them.
     */
    private suspend fun write(
        afterCommit: suspend () -> Unit = {},
        block: suspend () -> Outcome<ScopeWrite, SettingsError>,
    ): Outcome<Unit, SettingsError> =
        withContext(NonCancellable) {
            val result: Outcome<ScopeWrite, SettingsError> =
                try {
                    db.withWriteTransaction { block() }
                } catch (error: SQLiteException) {
                    Log.w("ScopeSettings", error) { "Scoped settings write failed" }
                    return@withContext Outcome.Failure(SettingsError.WriteFailed)
                }

            val write =
                when (result) {
                    is Outcome.Failure -> return@withContext result
                    is Outcome.Success -> result.value
                }
            if (write.intervalChanged) {
                suspendRunCatching { refresh().reschedulePeriodic() }
                    .onFailure { Log.w("ScopeSettings", it) { "Post-write refresh reschedule failed" } }
            }
            afterCommit()
            if (write.mutated) {
                suspendRunCatching { snapshots.requestSoon() }
                    .onFailure { Log.w("ScopeSettings", it) { "Snapshot request failed after settings write" } }
            }

            Outcome.Success(Unit)
        }

    /**
     * What one committed scope write changed: [intervalChanged] drives the refresh reschedule and
     * [mutated] the snapshot request (05's "always → requestSoon" applies to durable mutations).
     */
    private class ScopeWrite(
        val intervalChanged: Boolean,
        val mutated: Boolean,
    ) {
        companion object {
            val UNCHANGED = ScopeWrite(intervalChanged = false, mutated = false)
        }
    }

    private fun ScopeOverrides.toModel(): SettingOverrides =
        SettingOverrides(
            refreshIntervalMinutes,
            notifyNewEpisodes,
            playbackSpeed?.takeIf(PlaybackSettingKeys::isValidSpeed),
            skipSilence,
        )

    private fun ScopeOverrides.withEditableFields(value: SettingOverrides): ScopeOverrides =
        copy(
            refreshIntervalMinutes = value.refreshIntervalMinutes,
            notifyNewEpisodes = value.notifyNewEpisodes,
            playbackSpeed = value.playbackSpeed,
            skipSilence = value.skipSilence,
        )

    private fun invalidSpeed(): Outcome<Nothing, SettingsError> =
        Outcome.Failure(SettingsError.OutOfRange("playbackSpeed"))

    private fun invalidRefresh(): Outcome<Nothing, SettingsError> =
        Outcome.Failure(SettingsError.OutOfRange("refreshIntervalMinutes"))
}
