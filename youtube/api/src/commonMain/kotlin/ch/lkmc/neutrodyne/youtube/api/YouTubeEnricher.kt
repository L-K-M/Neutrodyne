// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

import ch.lkmc.neutrodyne.core.model.Availability

/** External implementations return Unsupported without IO. Cancellation propagates. */
interface YouTubeEnricher {
    suspend fun enrich(
        channelId: String,
        videoIds: Set<String>,
        variants: Int,
    ): EnrichResult

    suspend fun uploadsPage(
        channelId: String,
        variant: Int,
        cursor: UploadsCursor?,
    ): UploadsPageResult
}

data class VideoFacts(
    val videoId: String,
    val title: String?,
    val publishedAtMs: Long?,
    val publishedApprox: Boolean,
    val durationMs: Long?,
    val availability: Availability?,
    val isShort: Boolean?,
    val description: String?,
)

sealed interface EnrichResult {
    data class Ok(
        val facts: List<VideoFacts>,
    ) : EnrichResult

    data object Unsupported : EnrichResult

    data class Failed(
        val kind: TransientKind,
        val cause: Throwable?,
    ) : EnrichResult
}

/** Opaque and host-owned, never stored in Room or optionsJson. */
data class UploadsCursor(
    val token: String,
)

sealed interface UploadsPageResult {
    data class Ok(
        val items: List<VideoFacts>,
        val next: UploadsCursor?,
    ) : UploadsPageResult

    data object Unsupported : UploadsPageResult

    data class Failed(
        val kind: TransientKind,
    ) : UploadsPageResult
}
