// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.WaitReason

/**
 * `episode_state`: low-churn user state (02, D15). Rows are created lazily on the first state
 * change (`INSERT OR IGNORE`, then a targeted UPDATE).
 */
@Entity(
    tableName = "episode_state",
    indices = [Index("playedAt")],
    foreignKeys = [
        ForeignKey(
            EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodeStateEntity(
    @PrimaryKey val episodeId: Long,
    /** Set once when the position first becomes > 0; cleared on reset. */
    val startedAt: Long? = null,
    val playedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    /** Tombstone: the user deleted the download (07). */
    val downloadDismissedAt: Long? = null,
    /** Written back by 06. */
    val measuredDurationMs: Long? = null,
    /** Last user-state change (backup merge). */
    val updatedAt: Long,
)

/**
 * `episode_position`: high-churn (a save every 5 s while playing). **Never joined by paged or
 * list queries**; read only through `IN (:ids)` queries and by 06 (02).
 */
@Entity(
    tableName = "episode_position",
    foreignKeys = [
        ForeignKey(
            EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodePositionEntity(
    @PrimaryKey val episodeId: Long,
    val positionMs: Long,
    val durationMs: Long? = null,
    val positionSource: PositionSource,
    val updatedAt: Long,
)

/** `queue_entry`: Up next (02, D38); `orderKey` is the mergeable fractional index. */
@Entity(
    tableName = "queue_entry",
    indices = [Index("episodeId", unique = true), Index("orderKey")],
    foreignKeys = [
        ForeignKey(
            EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class QueueEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val orderKey: String,
    val addedAt: Long,
)

/**
 * `play_session`: singleton (`id = 0`), inserted by `Callback.onCreate` as
 * `INSERT OR IGNORE INTO play_session(id, generation, updatedAt) VALUES (0, 0, :now)` (02). Every
 * other write is an `UPDATE … WHERE id = 0`.
 */
@Entity(
    tableName = "play_session",
    indices = [Index("currentEpisodeId")],
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["currentEpisodeId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class PlaySessionEntity(
    @PrimaryKey val id: Int = 0,
    val currentEpisodeId: Long? = null,
    /** Null = no context. */
    val contextType: ContextType? = null,
    /** groupId or podcastId; no FK (polymorphic). */
    val contextId: Long? = null,
    @ColumnInfo(defaultValue = "'NEWEST_FIRST'") val contextOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    @ColumnInfo(defaultValue = "0") val contextFilterFlags: Int = 0,
    @ColumnInfo(defaultValue = "'ALL'") val contextMediaFilter: MediaFilter = MediaFilter.ALL,
    /** Fixed when the context starts. */
    val contextMinSortDate: Long? = null,
    /** No FK: the anchor may be deleted. */
    val contextAnchorEpisodeId: Long? = null,
    /** Keyset needs (sortDate, id) even after the anchor row is deleted. */
    val contextAnchorSortDate: Long? = null,
    @ColumnInfo(defaultValue = "0") val generation: Long = 0,
    val updatedAt: Long,
)

/**
 * `download`: one row per episode with a download in any state (02). `downloadedBytes` is persisted
 * only on state transitions; no stream or CDN URL columns (D50).
 */
@Entity(
    tableName = "download",
    indices = [Index("state", "lane", "priority", "requestedAt")],
    foreignKeys = [
        ForeignKey(
            EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadEntity(
    @PrimaryKey val episodeId: Long,
    val lane: DownloadLane,
    val state: DownloadState,
    @ColumnInfo(defaultValue = "'NONE'") val waitReason: WaitReason = WaitReason.NONE,
    /** MANUAL 100, AUTO 0, "download next" 200. */
    val priority: Int,
    val requestedAt: Long,
    /** `sourceRef`: the enclosure URL as in the feed, or the YouTube video ID. */
    val sourceKind: SourceKind,
    val sourceRef: String,
    val formatPref: String? = null,
    val resolvedItag: Int? = null,
    /** Android `ext:primary` | `ext:{volumeUuid}` | `int` (| `saf:…` v1.x); desktop: 07's IDs. */
    val rootId: String,
    val tempPath: String? = null,
    val relativePath: String? = null,
    val finalUri: String? = null,
    val totalBytes: Long? = null,
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0,
    val estimatedBytes: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val mimeType: String? = null,
    val allowMetered: Boolean,
    @ColumnInfo(defaultValue = "0") val requireCharging: Boolean = false,
    @ColumnInfo(defaultValue = "0") val attempt: Int = 0,
    @ColumnInfo(defaultValue = "0") val integrityFailures: Int = 0,
    val nextAttemptAt: Long? = null,
    val lastError: DownloadError? = null,
    val lastHttpStatus: Int? = null,
    val lastStopReason: Int? = null,
    val completedAt: Long? = null,
    val runnerToken: String? = null,
)

/**
 * `artwork`: pinned artwork metadata (02, D42/D57). Keys are deterministic; `podcast.artworkKey`/
 * `episode.artworkKey` reference it without an FK, and a row may be missing.
 */
@Entity(tableName = "artwork")
data class ArtworkEntity(
    /** `u-…`, `m-…`, `g-…` (08). */
    @PrimaryKey val key: String,
    /** Source descriptor of the stored bytes; null = never synced. */
    val url: String? = null,
    /** Relative to the artwork root; null = not pinned. */
    val localPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** M10 fills them. */
    val seedArgb: Int? = null,
    val avgArgb: Int? = null,
    /** Bumps when bytes change (memory-key busting). */
    @ColumnInfo(defaultValue = "0") val version: Int = 0,
    val fetchedAt: Long? = null,
    /** Cache of the reference count (02 Artwork references). */
    @ColumnInfo(defaultValue = "0") val pinCount: Int = 0,
    val lastError: String? = null,
)
