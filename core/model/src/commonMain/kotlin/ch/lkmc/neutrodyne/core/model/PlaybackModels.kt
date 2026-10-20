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

/** Smaller differences do not justify a duration metadata write or player-source reload. */
const val MEASURED_DURATION_TOLERANCE_MS: Long = 1_000L

/** Low-churn player input without exposing Room to shared playback rules. */
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
    /** Keyset anchor for explicit/context starts; media lookups populate the episode sort date. */
    val sortDate: Long? = null,
    /** System surfaces use podcast art when episode art is not pinned (06 Artwork rule). */
    val podcastArtwork: ArtworkRef = artwork,
    /** Optional system-surface metadata from the existing media lookup projection. */
    val podcastAuthor: String? = null,
    val pubDate: Long? = null,
    val enclosureType: String? = null,
    val chaptersUrl: String? = null,
    val chaptersType: String? = null,
    /** Audio candidate only; null means use the original enclosure. isVideo remains feed metadata. */
    val audioAlternate: AudioAlternate? = null,
    /** System surfaces map this saved status; null is not downloaded until M6 supplies files. */
    val downloadState: DownloadState? = null,
)

data class AudioAlternate(
    val url: String,
    val type: String,
    val length: Long?,
)

/**
 * The undo token of `QueueRepository.removeWithUndo`/`clearWithUndo` (06 Up next operations): the
 * episode ids actually deleted and the complete stored Up next order before deletion, captured in
 * the same write transaction. `undoRemoval` re-inserts the still eligible ids where the surviving
 * order places them; it is not a snapshot rollback — concurrent edits keep their positions.
 */
data class QueueRemoval(
    /** Queued ids the call deleted, in their original queue order (a subset of the request). */
    val removedIds: List<Long>,
    /** The full stored Up next order from before the delete, in queue order. */
    val originalOrder: List<Long>,
)
