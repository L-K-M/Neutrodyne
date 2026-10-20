// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.RejectReason
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult

/** Records in dispatch order; configured results never invent successful playback or mutate state. */
class FakePlaybackController(
    val state: FakePlaybackStateSource = FakePlaybackStateSource(),
) : PlaybackController {
    val calls = mutableListOf<Call>()
    var playResult: PlayResult = PlayResult.ServiceUnavailable
    var scopeResult: ScopeWriteResult = ScopeWriteResult.NOTHING_PLAYING
    var addResult: AddResult = AddResult.Rejected(RejectReason.UNAVAILABLE)
    var pauseCalls = 0
        private set

    sealed interface Call {
        data class Episode(
            val id: Long,
            val positionMs: Long? = null,
        ) : Call

        data class Feed(
            val source: FeedSource,
            val filters: FeedFilters,
            val order: FeedOrder,
            val startId: Long?,
        ) : Call

        data class Transport(
            val action: Action,
            val positionMs: Long? = null,
        ) : Call

        data class Speed(
            val speed: Float,
            val scope: SettingScope,
        ) : Call

        data class Silence(
            val enabled: Boolean,
            val scope: SettingScope,
        ) : Call

        data class Queue(
            val placement: Placement,
            val ids: List<Long>,
        ) : Call
    }

    enum class Action { PLAY, PAUSE, TOGGLE, SEEK, BACK, FORWARD, NEXT, PREVIOUS, CONSENT, DISMISS }

    enum class Placement { NEXT, LAST }

    override suspend fun playEpisode(episodeId: Long): PlayResult {
        calls += Call.Episode(episodeId)
        return playResult
    }

    override suspend fun playEpisodeAt(
        episodeId: Long,
        positionMs: Long,
    ): PlayResult {
        calls +=
            Call.Episode(episodeId, positionMs)
        return playResult
    }

    override suspend fun playFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
        startEpisodeId: Long?,
    ): PlayResult {
        calls += Call.Feed(source, filters, order, startEpisodeId)
        return playResult
    }

    override suspend fun play(): PlayResult {
        calls += Call.Transport(Action.PLAY)
        return playResult
    }

    override fun pause() {
        pauseCalls++
        calls += Call.Transport(Action.PAUSE)
    }

    override fun togglePlayPause() {
        calls += Call.Transport(Action.TOGGLE)
    }

    override fun seekTo(positionMs: Long) {
        calls += Call.Transport(Action.SEEK, positionMs)
    }

    override fun skipBack() {
        calls += Call.Transport(Action.BACK)
    }

    override fun skipForward() {
        calls += Call.Transport(Action.FORWARD)
    }

    override fun skipToNext() {
        calls += Call.Transport(Action.NEXT)
    }

    override fun skipToPrevious() {
        calls += Call.Transport(Action.PREVIOUS)
    }

    override suspend fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ): ScopeWriteResult {
        calls +=
            Call.Speed(speed, scope)
        return scopeResult
    }

    override suspend fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ): ScopeWriteResult {
        calls +=
            Call.Silence(enabled, scope)
        return scopeResult
    }

    override suspend fun playNext(episodeIds: List<Long>): AddResult {
        calls +=
            Call.Queue(Placement.NEXT, episodeIds.toList())
        return addResult
    }

    override suspend fun playLast(episodeIds: List<Long>): AddResult {
        calls +=
            Call.Queue(Placement.LAST, episodeIds.toList())
        return addResult
    }

    override fun grantMeteredStreaming() {
        calls += Call.Transport(Action.CONSENT)
    }

    override suspend fun dismiss() {
        calls += Call.Transport(Action.DISMISS)
    }
}
