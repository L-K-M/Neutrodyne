// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.youtube.api.YouTubeTransferSnapshot

/** One attempt; resolved URLs are memory-only. Engine owns every database transition. */
internal interface TransferSource {
    val kind: SourceKind

    suspend fun prepare(
        row: DownloadEntity,
        part: PartFile,
    ): Prepared

    suspend fun transfer(
        plan: TransferPlan,
        part: PartFile,
        sink: ProgressSink,
    ): TransferResult
}

internal data class TransferPlan(
    val episodeId: Long,
    val url: String,
    val offset: Long,
    val expectedTotal: Long?,
    val ifRange: String?,
    val itag: Int? = null,
    val mimeType: String? = null,
    val integrity: String? = null,
    /** In-runner retries may use a memory-only CDN URL; fresh prepare always uses the source. */
    val originalUrl: String = url,
    /** W8 owns the YouTube source. This stays in memory, never optionsJson or a signed URL row. */
    val youtube: YouTubeTransferSnapshot? = null,
) {
    override fun toString(): String = "TransferPlan(episodeId=$episodeId, offset=$offset)"
}

internal sealed interface Prepared {
    /** Rename survived a crash; engine commits COMPLETED without another request or move. */
    data class Adopted(
        val complete: TransferResult.Complete,
        val relativePath: String,
        val finalUri: String,
    ) : Prepared

    data class Go(
        val plan: TransferPlan,
        val rootId: String,
        val tempPath: String,
        val sourceRef: String,
    ) : Prepared

    data class Wait(
        val reason: WaitReason,
        val error: DownloadError?,
        val untilMs: Long?,
        val countsAsAttempt: Boolean,
    ) : Prepared

    data class Fail(
        val error: DownloadError,
        val httpStatus: Int?,
    ) : Prepared
}

internal enum class MediaKind { MP3, M4B, MP4, OGG, FLAC, WAV, WEBM, AAC, HLS, NOT_MEDIA, UNKNOWN_BINARY }

internal data class TransferMetadata(
    val offset: Long,
    val total: Long?,
    val mimeType: String?,
    val etag: String?,
    val lastModified: String?,
    val resolvedItag: Int? = null,
)

internal interface ProgressSink {
    /** Await the engine's token-owned DOWNLOADING commit before writing the first accepted byte.
     * The engine commits first acceptance and changed representation/part-placement proof under the
     * current state and token. Equal proof, including after Restart, and byte ticks add no writes. */
    suspend fun started(metadata: TransferMetadata)

    fun bytes(
        downloaded: Long,
        total: Long?,
    )
}

internal sealed interface TransferResult {
    data class Complete(
        val total: Long,
        val mimeType: String?,
        val etag: String?,
        val lastModified: String?,
        val media: MediaKind,
    ) : TransferResult

    data class Restart(
        val reason: String,
    ) : TransferResult

    data class Transient(
        val error: DownloadError,
        val httpStatus: Int?,
        val retryAfterMs: Long?,
        /** Engine may copy this into the plan for an in-runner retry, never into a row. */
        val retryUrl: String? = null,
    ) : TransferResult

    data class Permanent(
        val error: DownloadError,
        val httpStatus: Int?,
    ) : TransferResult

    data class Wait(
        val reason: WaitReason,
        val error: DownloadError?,
        val untilMs: Long?,
        /** Long HTTP Retry-After consumes an attempt; storage/network gates do not. */
        val countsAsAttempt: Boolean = false,
        val httpStatus: Int? = null,
    ) : TransferResult
}

internal sealed interface TransferEnd {
    data class Finished(
        val result: TransferResult,
    ) : TransferEnd

    data class Stopped(
        val intent: StopIntent,
    ) : TransferEnd

    data object Unknown : TransferEnd
}
