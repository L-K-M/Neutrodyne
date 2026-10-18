// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.api

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-scoped, main-safe observation. Null means no loaded item. Snapshots are immutable; position
 * ticks are separate from metadata (1 Hz while advancing plus state changes). Events are one-shot,
 * with no replay. Collectors must match episode IDs when combining ticks with nowPlaying.
 */
interface PlaybackStateSource {
    val nowPlaying: StateFlow<NowPlaying?>
    val positionTicks: Flow<PositionSnapshot>
    val effectivePlayback: StateFlow<EffectivePlayback?>

    /** Retained while awaiting a decision; clear on grant, replacement start or dismissal. */
    val pendingMeteredConsent: StateFlow<PlayResult.NeedsMeteredConsent?>
    val events: Flow<PlaybackEvent>
}

data class NowPlaying(
    val episodeId: Long,
    val podcastId: Long,
    val title: String,
    val podcastTitle: String,
    val artwork: ArtworkRef,
    val sourceType: SourceType,
    val isVideo: Boolean,
    val phase: PlayerPhase,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val position: PositionSnapshot,
    val hasNext: Boolean,
    val stream: StreamKind?,
    val issue: PlaybackIssue?,
    val context: PlayContextInfo?,
)

/**
 * Milliseconds sampled on Clock.elapsedRealtime, never wall time. Unknown duration is null.
 * Advancing excludes pauses/buffering. UI interpolation does not write state or query storage.
 */
data class PositionSnapshot(
    val episodeId: Long,
    val positionMs: Long,
    val bufferedMs: Long,
    val durationMs: Long?,
    val speed: Float,
    val advancing: Boolean,
    val sampledAtElapsedMs: Long,
) {
    fun positionAt(elapsedMs: Long): Long {
        val delta = if (advancing) ((elapsedMs - sampledAtElapsedMs).coerceAtLeast(0) * speed).toLong() else 0
        val position = (positionMs + delta).coerceAtLeast(0)
        return durationMs?.let { position.coerceAtMost(it) } ?: position
    }
}

enum class PlayerPhase { NOT_LOADED, BUFFERING, READY, ENDED, ERROR }

enum class StreamKind { LOCAL, STREAM, YOUTUBE }

enum class PlaybackIssue {
    NETWORK_LOST,
    METERED_PAUSE,
    METERED_BLOCKED,
    LOCAL_FILE_MISSING,
    YOUTUBE_RATE_LIMITED,
    YOUTUBE_BREAKER_OPEN,
    PLAYER_ERROR,
}

sealed interface UnplayableReason {
    data class Http(
        val status: Int,
    ) : UnplayableReason

    data object AuthRequired : UnplayableReason

    data object UnsupportedFormat : UnplayableReason

    data object NoMedia : UnplayableReason

    data class YouTube(
        val availability: Availability,
    ) : UnplayableReason

    data object YouTubeExtraction : UnplayableReason

    data class YouTubeExternal(
        val reason: ExternalReason,
    ) : UnplayableReason
}

sealed interface PlaybackEvent {
    data class Skipped(
        val episodeId: Long,
        val title: String,
        val reason: UnplayableReason,
    ) : PlaybackEvent

    data class MarkedPlayed(
        val episodeId: Long,
    ) : PlaybackEvent

    /** Failed fire-and-forget resume/toggle, including consent; no fake Started notification. */
    data class StartRefused(
        val result: PlayResult,
    ) : PlaybackEvent
}

/** Main-safe cache maintenance. Implementations move IO off main; failures propagate to the caller. */
interface PlaybackMaintenance {
    suspend fun streamingCacheBytes(): Long

    suspend fun clearStreamingCache()
}
