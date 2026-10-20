// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.ScopeSettingsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.PlaybackScopeWrite
import ch.lkmc.neutrodyne.core.model.PlaybackSettingChange
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Per-player settings, confined to the adapter's player dispatcher (06). The adapter prepares
 * the window before prepare/transition, then runs [followCurrent] in a child job, cancelling and
 * joining that job before following another item. Publish [effectivePlayback] through the state
 * hub and apply its values without seeking or restarting audio. Command handlers delegate to
 * this same player-owned instance; injecting a separate instance loses the followed target.
 */
@Inject
class EffectivePlaybackSettings(
    private val resolver: EffectiveSettingsResolver,
    private val scopes: ScopeSettingsRepository,
    private val settings: SettingsRepository,
) {
    private data class Target(
        val podcastId: Long,
        val contextGroupId: Long?,
    )

    private val values = mutableMapOf<Target, EffectivePlayback>()
    private var current: Target? = null
    private val effective = MutableStateFlow<EffectivePlayback?>(null)
    val effectivePlayback: StateFlow<EffectivePlayback?> = effective.asStateFlow()

    /** Resolve once per distinct podcast; callers pass every window item's podcast ID. */
    suspend fun prepareWindow(
        podcastIds: Collection<Long>,
        contextGroupId: Long?,
    ): Map<Long, EffectivePlayback> {
        val resolved = podcastIds.distinct().associateWith { resolver.playback(it, contextGroupId) }
        values.clear()
        resolved.forEach { (id, value) -> values[Target(id, contextGroupId)] = value }
        return resolved
    }

    /** Synchronous transition input after [prepareWindow]; context is part of the cache identity. */
    fun cached(
        podcastId: Long,
        contextGroupId: Long?,
    ): EffectivePlayback? = values[Target(podcastId, contextGroupId)]

    /** Null clears the published value on dismissal. The caller owns collection/lifetime. */
    suspend fun followCurrent(
        podcastId: Long?,
        contextGroupId: Long?,
    ) {
        val target = podcastId?.let { Target(it, contextGroupId) }
        current = target
        effective.value = target?.let { values[it] }
        if (target == null) return

        resolver.observePlayback(target.podcastId, target.contextGroupId).collect { value ->
            values[target] = value
            if (current == target) effective.value = value
        }
    }

    /** Adapters may apply a finite normalized speed immediately; this result confirms durability. */
    suspend fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ): ScopeWriteResult = writeScope(PlaybackSettingChange.Speed(speed), scope)

    suspend fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ): ScopeWriteResult = writeScope(PlaybackSettingChange.SkipSilence(enabled), scope)

    suspend fun writeScope(
        change: PlaybackSettingChange,
        scope: SettingScope,
    ): ScopeWriteResult {
        val target = current ?: return ScopeWriteResult.NOTHING_PLAYING
        val normalized =
            when (change) {
                is PlaybackSettingChange.Speed -> {
                    if (!change.value.isFinite()) return ScopeWriteResult.FAILED
                    PlaybackSettingChange.Speed(PlaybackSettingKeys.normalizeSpeed(change.value))
                }

                is PlaybackSettingChange.SkipSilence -> {
                    change
                }
            }
        return writeScope(target, normalized, scope)
    }

    private suspend fun writeScope(
        target: Target,
        change: PlaybackSettingChange,
        scope: SettingScope,
    ): ScopeWriteResult =
        when (val result = scopes.writePlayback(target.podcastId, target.contextGroupId, scope, change)) {
            is Outcome.Failure -> {
                ScopeWriteResult.FAILED
            }

            is Outcome.Success -> {
                when (result.value) {
                    PlaybackScopeWrite.NO_CONTEXT_GROUP -> {
                        ScopeWriteResult.NO_CONTEXT_GROUP
                    }

                    PlaybackScopeWrite.MISSING_PODCAST -> {
                        ScopeWriteResult.NOTHING_PLAYING
                    }

                    PlaybackScopeWrite.APPLIED -> {
                        val value = resolver.playback(target.podcastId, target.contextGroupId)
                        values[target] = value
                        if (current == target) effective.value = value
                        ScopeWriteResult.APPLIED
                    }
                }
            }
        }

    /** Read live attribution/presets: edits from another screen or sync also govern the cycle. */
    suspend fun cycleSpeed(): ScopeWriteResult {
        val target = current ?: return ScopeWriteResult.NOTHING_PLAYING
        val speed = resolver.playback(target.podcastId, target.contextGroupId).speed
        val presets =
            requireNotNull(PlaybackSettingKeys.parseSpeedPresets(settings.get(PlaybackSettingKeys.SPEED_PRESETS)))
        val next = presets.firstOrNull { it > speed.value } ?: presets.first()
        val scope =
            when (speed.source) {
                SettingSource.PodcastOverride -> SettingScope.PODCAST
                is SettingSource.Group -> SettingScope.GROUP
                SettingSource.AppDefault -> SettingScope.GLOBAL
                SettingSource.NotSupported -> error("Playback resolution cannot be unsupported")
                is SettingSource.Groups -> error("Playback resolution cannot merge context groups")
            }
        return writeScope(target, PlaybackSettingChange.Speed(next), scope)
    }

    /** Runtime-only preferences; speed and silence use attributed per-item resolution above. */
    fun observePreferences(): Flow<PlaybackPreferences> =
        combine(
            settings.observe(PlaybackSettingKeys.SKIP_BACK_MS),
            settings.observe(PlaybackSettingKeys.SKIP_FORWARD_MS),
            settings.observe(PlaybackSettingKeys.PAUSE_FOR_NAVIGATION),
            settings.observe(PlaybackSettingKeys.STREAM_ON_METERED),
            settings.observe(PlaybackSettingKeys.STREAM_CACHE_MB),
        ) { back, forward, navigation, metered, cache ->
            PlaybackPreferences(back, forward, navigation, metered, cache)
        }.combine(settings.observe(PlaybackSettingKeys.SPEED_PRESETS)) { preferences, presets ->
            preferences.copy(speedPresets = requireNotNull(PlaybackSettingKeys.parseSpeedPresets(presets)))
        }.distinctUntilChanged()
}

/** Adapter snapshot: update seek increments, focus policy and cache size without a reload. */
data class PlaybackPreferences(
    val skipBackMs: Long,
    val skipForwardMs: Long,
    val pauseForNavigation: Boolean,
    val streamOnMetered: StreamOnMetered,
    val streamCacheMb: Int,
    val speedPresets: List<Float> =
        requireNotNull(PlaybackSettingKeys.parseSpeedPresets(PlaybackSettingKeys.SPEED_PRESETS.default)),
)
