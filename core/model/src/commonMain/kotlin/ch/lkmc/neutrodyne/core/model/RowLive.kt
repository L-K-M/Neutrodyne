// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * The live overlay on a paged episode (08 Live row state). The pipeline arrives in M2,
 * playback producers in M4 and download producers in M6. Absent fields use the paged row.
 */
public data class RowLive(
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val downloadState: DownloadState? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val waitReason: WaitReason? = null,
    val nextAttemptAt: Long? = null,
    val lastError: DownloadError? = null,
    /** In the player as the current item. */
    val isNowPlaying: Boolean = false,
    /** And currently advancing (vs paused). */
    val isPlaying: Boolean = false,
) {
    /** Progress as 0..1, `null` when indeterminate or not downloading. */
    public fun downloadProgress(): Float? =
        downloadedBytes?.let { done ->
            val total = totalBytes ?: return null
            if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
        }
}
