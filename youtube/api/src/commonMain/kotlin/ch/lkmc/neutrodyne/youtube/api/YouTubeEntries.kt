// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

/** Feed-owned mapping supplies published only and D19 sortDate; updated/views never enter this DTO. */
data class YouTubeEntry(
    val videoId: String,
    val title: String,
    val publishedAtMs: Long?,
    val rawPublished: String?,
    val sortDate: Long,
    val description: String,
    val link: String?,
)

/** One result per selected bit, in bit order. Primary 404/410 tries canonical before reporting failure. */
sealed interface VariantResult {
    val bit: Int

    data class Ok(
        override val bit: Int,
        val entries: List<YouTubeEntry>,
        val authorName: String?,
        val fallback: Boolean = false,
    ) : VariantResult

    data class Empty(
        override val bit: Int,
    ) : VariantResult

    data class Failed(
        override val bit: Int,
        val httpCode: Int?,
        val cause: Throwable?,
    ) : VariantResult
}

data class MergedEntry(
    val entry: YouTubeEntry,
    val isShort: Boolean,
    val feedOrder: Int,
)

/** Digest includes normalized authorName so a channel rename does not take the Unchanged path. */
data class YouTubeDigestInput(
    val authorName: String?,
    val entries: List<MergedEntry>,
)

/**
 * absenceFloor is MAX_VALUE on any failed variant; otherwise max of raw, pre-filter variant
 * floors (15 entries: min sortDate; shorter/Empty: MIN_VALUE). digest is lowercase SHA-256.
 * Shorts classification is sticky at persistence, including unchanged-text transitions.
 * This boundary has no feeds dependency in either direction.
 */
data class MergedChannel(
    val entries: List<MergedEntry>,
    val authorName: String?,
    val digest: String,
    val absenceFloor: Long,
)

data class BannerSource(
    val url: String,
    val width: Int,
)

enum class ThumbVariant(
    val fileName: String,
    val letterboxed: Boolean,
) {
    MAXRES("maxresdefault", false),
    HQ720("hq720", false),
    SD("sddefault", true),
    HQ("hqdefault", true),
    MQ("mqdefault", false),
    DEFAULT("default", true),
}
