// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.playback.api

import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SleepTimerMode

/**
 * Where a play command originates (10 pull before play). Visible-UI starts run MS3's bounded
 * pull so a newer synced position lands before planning; OS surfaces must react at once.
 */
enum class PlayOrigin {
    /** The app's visible UI (the default): the start may wait briefly on a sync pull. */
    UI,

    /** OS surfaces — media sessions, transport buttons: the start never waits on a pull. */
    SYSTEM,
}

/**
 * M4 commands shared by both players (06). Call from the UI/main thread; adapters serialize
 * dispatch on their player thread without blocking. Suspend calls complete after the matching
 * commit and dispatch, never after audio finishes. Cancellation propagates; failed starts return
 * a [PlayResult] and must not issue play. Transports are ordered fire-and-forget; failures appear
 * in [PlaybackStateSource]. Restore-Replace may always call [pause], even without a loaded item.
 */
interface PlaybackController {
    /** Explicit item; keep context/anchor if queued, otherwise use EXTERNAL. Current resumes. */
    suspend fun playEpisode(episodeId: Long): PlayResult

    /** Explicit timestamp, non-negative milliseconds; no smart rewind. Same start gates apply. */
    suspend fun playEpisodeAt(
        episodeId: Long,
        positionMs: Long,
    ): PlayResult

    /** Completed downloads, newest first; implementation arrives with M6 playback integration. */
    suspend fun playDownloads(startEpisodeId: Long? = null): PlayResult

    /**
     * Explicit row preserves that item and anchors there. Without a row, select the first playable
     * Up next entry (keep refused rows), then eligible context. Queue starts use a null anchor.
     * Gates run before any mutation. Starting a context preserves the remaining Up next order.
     */
    suspend fun playFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
        startEpisodeId: Long? = null,
    ): PlayResult

    /**
     * Resume current through gates; without current, use the automatic queue/context rule.
     * [origin] selects the pull-before-play behavior; the default is the UI path.
     */
    suspend fun play(origin: PlayOrigin = PlayOrigin.UI): PlayResult

    fun pause()

    /**
     * Resume through the same gates, or pause; refused starts are published in state/events.
     * When a resume wins, [origin] has the same pull-before-play meaning as in [play].
     */
    fun togglePlayPause(origin: PlayOrigin = PlayOrigin.UI)

    /** Clamp to zero/known duration. A user seek below 1 s explicitly resets stored position. */
    fun seekTo(positionMs: Long)

    /** Use the current configured backward/forward increment, clamping as for [seekTo]. */
    fun skipBack()

    fun skipForward()

    /** Episode next, independent of hardware-button mapping; save departure before arrival. */
    fun skipToNext()

    /** After 3 s restart current, otherwise use the process's bounded previous-item history. */
    fun skipToPrevious()

    /** Ordered main-thread commands. Invalid minutes leave timer/volume unchanged.
     * Active state is not persisted; callers store only the last chosen minutes.
     * EndOfEpisode follows manual item changes; without a current item it stays Off. Off,
     * replacement, expiry and teardown restore normal volume and clear only sleep's arbiter reason.
     * Buffering holds fade; explicit pause restores volume. Expiry emits SleepTimerFired once. */
    fun setSleepTimer(mode: SleepTimerMode)

    /** Add 1..240 minutes to a running minute timer, cap remaining at 240 minutes; otherwise no-op. */
    fun extendSleepTimer(minutes: Int)

    /** Seek within winning visible chapters; empty/no next is a no-op, never wrap or smart rewind. */
    fun nextChapter()

    /** >3 seconds into current: restart it; otherwise previous visible start, no previous: no-op. */
    fun previousChapter()

    /** Clamp/round finite speed to 0.5..3.0 in 0.05 steps; clear more-specific chain overrides. */
    suspend fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ): ScopeWriteResult

    /** Same scope/membership and override-clearing rules as [setSpeed]. */
    suspend fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ): ScopeWriteResult

    /** Queue edits only, equivalent to QueueRepository.addNext/addLast; never start audio. */
    suspend fun playNext(episodeIds: List<Long>): AddResult

    suspend fun playLast(episodeIds: List<Long>): AddResult

    /** Consent for this pending metered start, not a persistent policy change. Recheck gates. */
    fun grantMeteredStreaming()

    /** Flush departure, clear current/context and notification; retain Up next. Failures propagate.
     * May throw CancellationException when superseded by a newer playback intent. */
    suspend fun dismiss()
}

/** Started means the accepted start was committed/projected and play dispatched, not audible proof. */
sealed interface PlayResult {
    data object Started : PlayResult

    data class NothingToPlay(
        val externalOnly: Boolean = false,
    ) : PlayResult

    data class NeedsMeteredConsent(
        val episodeId: Long,
    ) : PlayResult

    data object MeteredBlocked : PlayResult

    data object Offline : PlayResult

    data class NotPlayable(
        val episodeId: Long,
        val reason: UnplayableReason,
    ) : PlayResult

    /** Missing adapter, invisible Android process, disconnect, timeout or failed start commit. */
    data object ServiceUnavailable : PlayResult
}

/** APPLIED requires a durable write; failures must never be presented as a successful scope write. */
enum class ScopeWriteResult { APPLIED, NO_CONTEXT_GROUP, NOTHING_PLAYING, FAILED }
