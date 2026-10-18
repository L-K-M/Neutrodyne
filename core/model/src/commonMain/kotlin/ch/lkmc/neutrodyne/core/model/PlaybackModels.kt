// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.model

/** Fixed start-time predicates; live membership/availability still apply on each tail read (05). */
data class PlayContextSpec(
    val type: ContextType,
    val contextId: Long?,
    val order: FeedOrder,
    val filterFlags: Int,
    val mediaFilter: MediaFilter,
    val minSortDate: Long?,
    val boundStartBySubscription: Boolean,
)

data class UpNextItem(
    val entryId: Long,
    val row: EpisodeRow,
)

enum class QueueOrigin { CURRENT, UP_NEXT, CONTEXT }

data class QueueItem(
    val episodeId: Long,
    val origin: QueueOrigin,
)

/**
 * One coherent read at [generation]. Up next is the complete persisted order, including unplayable
 * rows; only the player projection caps it at 100. Context excludes current/queued IDs and contains
 * at most the requested k eligible entries. Queue edits do not increment generation. No ticks here.
 */
data class VirtualQueue(
    val generation: Long,
    val current: QueueItem?,
    val upNext: List<QueueItem>,
    val contextTail: List<QueueItem>,
)

/** Display metadata, distinct from the persisted predicates and anchor used for tail queries. */
data class PlayContextInfo(
    val type: ContextType,
    val id: Long?,
    val title: String,
    val order: FeedOrder,
    val filterFlags: Int,
    val mediaFilter: MediaFilter,
)

data class PlaySessionInfo(
    val currentEpisodeId: Long?,
    val context: PlayContextInfo?,
    val generation: Long,
)

sealed interface AddResult {
    /** Distinct requested IDs added or moved; already-queued moves count. Empty input gives zero. */
    data class Added(
        val count: Int,
    ) : AddResult

    /** Entire batch rejected without mutations. */
    data class Rejected(
        val reason: RejectReason,
    ) : AddResult
}

enum class RejectReason { ALREADY_PLAYING, YOUTUBE_EXTERNAL, UNAVAILABLE }

/** The selected scope on the current podcast/context chain, not an arbitrary settings row. */
enum class SettingScope { GLOBAL, GROUP, PODCAST }

/** M4 values with existing M2 attribution; boost belongs to M12. */
data class EffectivePlayback(
    val speed: Effective<Float>,
    val skipSilence: Effective<Boolean>,
)

/** Owned by 04; shared here so playback API need not depend on youtube API. */
enum class ExternalReason { NOT_YET_AVAILABLE, NOT_IN_THIS_APK, DISABLED_BY_USER, ENGINE_FAILED }

/** Low-churn player input, mapped by W2 without exposing Room to shared playback rules. */
data class PlaybackMediaInfo(
    val episodeId: Long,
    val podcastId: Long,
    val title: String,
    val podcastTitle: String,
    val artwork: ArtworkRef,
    val sourceType: SourceType,
    val availability: Availability,
    val isVideo: Boolean,
    val enclosureUrl: String?,
    val enclosureLength: Long?,
    val externalMediaId: String?,
    val durationMs: Long?,
    val measuredDurationMs: Long?,
    val playedAt: Long?,
    /** System surfaces use podcast art when episode art is not pinned (06 Artwork rule). */
    val podcastArtwork: ArtworkRef = artwork,
)
