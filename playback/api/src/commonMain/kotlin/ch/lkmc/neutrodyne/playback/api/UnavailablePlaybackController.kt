// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.api

import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.RejectReason
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/** Shared unavailable policy for platform stubs; contributes no binding and performs no IO. */
open class UnavailablePlaybackController :
    PlaybackController,
    PlaybackStateSource,
    PlaybackMaintenance {
    override val sleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    override val currentChapter = MutableStateFlow<CurrentChapter?>(null)
    override val pendingMeteredConsent: StateFlow<PlayResult.NeedsMeteredConsent?> = MutableStateFlow(null)
    override val nowPlaying: StateFlow<NowPlaying?> = MutableStateFlow(null)
    override val positionTicks: Flow<PositionSnapshot> = emptyFlow()
    override val effectivePlayback: StateFlow<EffectivePlayback?> = MutableStateFlow(null)
    private val refused = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 1)
    override val events: Flow<PlaybackEvent> = refused.asSharedFlow()

    override suspend fun playEpisode(episodeId: Long): PlayResult = PlayResult.ServiceUnavailable

    override suspend fun playEpisodeAt(
        episodeId: Long,
        positionMs: Long,
    ): PlayResult = PlayResult.ServiceUnavailable

    override suspend fun playDownloads(startEpisodeId: Long?): PlayResult = PlayResult.ServiceUnavailable

    override suspend fun playFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
        startEpisodeId: Long?,
    ): PlayResult = PlayResult.ServiceUnavailable

    override suspend fun play(): PlayResult = PlayResult.ServiceUnavailable

    override fun pause() = Unit

    override fun togglePlayPause() {
        refused.tryEmit(PlaybackEvent.StartRefused(PlayResult.ServiceUnavailable))
    }

    override fun seekTo(positionMs: Long) = Unit

    override fun skipBack() = Unit

    override fun skipForward() = Unit

    override fun skipToNext() = Unit

    override fun skipToPrevious() = Unit

    override fun setSleepTimer(mode: SleepTimerMode) = Unit

    override fun extendSleepTimer(minutes: Int) = Unit

    override fun nextChapter() = Unit

    override fun previousChapter() = Unit

    override suspend fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ): ScopeWriteResult = ScopeWriteResult.NOTHING_PLAYING

    override suspend fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ): ScopeWriteResult = ScopeWriteResult.NOTHING_PLAYING

    override suspend fun playNext(episodeIds: List<Long>): AddResult = AddResult.Rejected(RejectReason.UNAVAILABLE)

    override suspend fun playLast(episodeIds: List<Long>): AddResult = AddResult.Rejected(RejectReason.UNAVAILABLE)

    override fun grantMeteredStreaming() = Unit

    override suspend fun dismiss() = Unit

    override suspend fun streamingCacheBytes(): Long = 0

    override suspend fun clearStreamingCache() = Unit
}
