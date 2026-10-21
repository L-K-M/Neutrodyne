// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoGroupChannels
import ch.lkmc.neutrodyne.core.domain.ScopeSettingsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.PlaybackScopeWrite
import ch.lkmc.neutrodyne.core.model.PlaybackSettingChange
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.hasSupportedPlaybackSpeed
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** In-memory writes over seeded inheritance; fixtures supply group/global resolutions. */
class FakeScopeSettingsRepository(
    private val channelSync: GroupChannelSync = NoGroupChannels,
    private val settings: SettingsRepository = FakeSettingsRepository(),
) : ScopeSettingsRepository {
    private data class Scope(
        val inherited: ScopedSettingsView,
        val ownSource: SettingSource,
        val own: SettingOverrides = SettingOverrides(),
    ) {
        fun view(
            globalSpeed: Float,
            globalSilence: Boolean,
        ): ScopedSettingsView {
            val speedSource = inherited.sources[SettingField.PLAYBACK_SPEED] ?: SettingSource.AppDefault
            val silenceSource = inherited.sources[SettingField.SKIP_SILENCE] ?: SettingSource.AppDefault
            val speed = if (speedSource == SettingSource.AppDefault) globalSpeed else inherited.effective.playbackSpeed
            val silence =
                if (silenceSource ==
                    SettingSource.AppDefault
                ) {
                    globalSilence
                } else {
                    inherited.effective.skipSilence
                }
            return ScopedSettingsView(
                own,
                SettingOverrides(
                    (own.refreshIntervalMinutes ?: inherited.effective.refreshIntervalMinutes)?.takeIf { it > 0 },
                    own.notifyNewEpisodes ?: inherited.effective.notifyNewEpisodes,
                    own.playbackSpeed ?: speed ?: globalSpeed,
                    own.skipSilence ?: silence ?: globalSilence,
                ),
                mapOf(
                    SettingField.PLAYBACK_SPEED to if (own.playbackSpeed != null) ownSource else speedSource,
                    SettingField.SKIP_SILENCE to if (own.skipSilence != null) ownSource else silenceSource,
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
                inherited.groupHints,
            )
        }
    }

    private val podcasts = MutableStateFlow<Map<Long, Scope>>(emptyMap())
    private val groups = MutableStateFlow<Map<Long, Scope>>(emptyMap())
    private val mutex = Mutex()
    private val memberships = mutableSetOf<Pair<Long, Long>>()
    var failNextWrite: SettingsError? = null

    fun seedMembership(
        podcastId: Long,
        groupId: Long,
    ) {
        memberships += podcastId to groupId
    }

    fun removeMembership(
        podcastId: Long,
        groupId: Long,
    ) {
        memberships -= podcastId to groupId
    }

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
        combine(
            podcasts,
            settings.observe(PlaybackSettingKeys.SPEED),
            settings.observe(PlaybackSettingKeys.SKIP_SILENCE),
        ) { rows, speed, silence ->
            rows[podcastId]?.view(speed, silence)
        }.distinctUntilChanged()

    override fun observeGroup(groupId: Long): Flow<ScopedSettingsView?> =
        combine(
            groups,
            settings.observe(PlaybackSettingKeys.SPEED),
            settings.observe(PlaybackSettingKeys.SKIP_SILENCE),
        ) { rows, speed, silence ->
            rows[groupId]?.view(speed, silence)
        }.distinctUntilChanged()

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

    override suspend fun writePlayback(
        podcastId: Long,
        contextGroupId: Long?,
        scope: SettingScope,
        change: PlaybackSettingChange,
    ): Outcome<PlaybackScopeWrite, SettingsError> =
        mutex.withLock {
            if (change is PlaybackSettingChange.Speed && !PlaybackSettingKeys.isValidSpeed(change.value)) {
                return@withLock Outcome.Failure(SettingsError.OutOfRange("playbackSpeed"))
            }
            val podcast = podcasts.value[podcastId]
            if (podcast == null) return@withLock Outcome.Success(PlaybackScopeWrite.MISSING_PODCAST)
            val groupId = contextGroupId?.takeIf { (podcastId to it) in memberships && it in groups.value }
            if (scope == SettingScope.GROUP && groupId == null) {
                return@withLock Outcome.Success(PlaybackScopeWrite.NO_CONTEXT_GROUP)
            }
            failNextWrite?.let {
                failNextWrite = null
                return@withLock Outcome.Failure(it)
            }
            if (scope == SettingScope.GLOBAL) {
                val result =
                    when (change) {
                        is PlaybackSettingChange.Speed -> {
                            settings.set(PlaybackSettingKeys.SPEED, change.value)
                        }

                        is PlaybackSettingChange.SkipSilence -> {
                            settings.set(PlaybackSettingKeys.SKIP_SILENCE, change.value)
                        }
                    }
                if (result is Outcome.Failure) return@withLock result
            }
            val podcastEdit = if (scope == SettingScope.PODCAST) OverrideEdit.SET else OverrideEdit.CLEAR
            podcasts.value = podcasts.value + (podcastId to podcast.copy(own = podcast.own.edit(change, podcastEdit)))
            if (scope != SettingScope.PODCAST && groupId != null) {
                val group = groups.value.getValue(groupId)
                val groupEdit = if (scope == SettingScope.GROUP) OverrideEdit.SET else OverrideEdit.CLEAR
                groups.value = groups.value + (groupId to group.copy(own = group.own.edit(change, groupEdit)))
            }
            Outcome.Success(PlaybackScopeWrite.APPLIED)
        }

    private enum class OverrideEdit { SET, CLEAR }

    private fun SettingOverrides.edit(
        change: PlaybackSettingChange,
        edit: OverrideEdit,
    ): SettingOverrides =
        when (change) {
            is PlaybackSettingChange.Speed -> copy(playbackSpeed = change.value.takeIf { edit == OverrideEdit.SET })
            is PlaybackSettingChange.SkipSilence -> copy(skipSilence = change.value.takeIf { edit == OverrideEdit.SET })
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
            if (!next.hasSupportedPlaybackSpeed()) {
                return@withLock Outcome.Failure(SettingsError.OutOfRange("playbackSpeed"))
            }
            failNextWrite?.let {
                failNextWrite = null
                return@withLock Outcome.Failure(it)
            }
            rows.value = rows.value + (id to current.copy(own = next))
            Outcome.Success(Unit)
        }

    private companion object {
        fun appDefaults(): ScopedSettingsView =
            ScopedSettingsView(
                SettingOverrides(),
                SettingOverrides(FeedsSettingKeys.DEFAULT_REFRESH_INTERVAL_MINUTES, false, 1f, false),
                SettingField.entries.associateWith { SettingSource.AppDefault },
            )
    }
}
